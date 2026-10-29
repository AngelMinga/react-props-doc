package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.model.Pointer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** `useSelector(state => state.│)`: partes del estado; `state.catalog.│`: claves de su estado inicial. */
class ReduxCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val ref = parameters.position.parent as? JSReferenceExpression ?: return
                if (ref.qualifier == null) return
                val path = ReduxState.selectorPath(ref) ?: return
                val slices = ReduxState.slices(ref.project)
                val parentPath = path.dropLast(1)
                if (parentPath.isEmpty()) {
                    slices.values.forEach { slice ->
                        result.addElement(
                            LookupElementBuilder.create(slice.name)
                                .withTypeText(ReduxState.preview(slice.initialState, 40))
                                .withTailText("  (Redux)", true),
                        )
                    }
                    return
                }
                val slice = slices[parentPath.first()] ?: return
                val obj = ReduxState.valueAt(slice, parentPath.drop(1)) as? JSObjectLiteralExpression ?: return
                obj.properties.forEach { property ->
                    val name = property.name ?: return@forEach
                    result.addElement(LookupElementBuilder.create(name).withTypeText(ReduxState.preview(property.value, 40)))
                }
            }
        })
    }
}

/** Hover sobre `state.catalog` (o `state.catalog.data`) dentro de `useSelector`. */
class ReduxDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val ref = file.findElementAt(offset)?.parent as? JSReferenceExpression ?: return emptyList()
        val path = ReduxState.selectorPath(ref)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (ReduxState.slices(file.project)[path.first()] == null) return emptyList()
        return listOf(ReduxDocumentationTarget(ref, path))
    }
}

class ReduxDocumentationTarget(private val ref: JSReferenceExpression, private val path: List<String>) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(ref)
        return Pointer { pointer.element?.let { ReduxDocumentationTarget(it, path) } }
    }

    override fun computePresentation() = TargetPresentation.builder("state." + path.joinToString(".")).presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val slice = ReduxState.slices(ref.project)[path.first()] ?: return null
        val value = ReduxState.valueAt(slice, path.drop(1))
        val rows = buildString {
            append(section("Reducer:", "<code>${esc(slice.name)}</code> — ${esc(slice.reducer?.containingFile?.name ?: "?")}"))
            val obj = value as? JSObjectLiteralExpression
            if (obj != null && obj.properties.isNotEmpty()) {
                append(
                    section(
                        "Estado inicial:",
                        obj.properties.joinToString("<br/>") { p ->
                            "<code>${esc(p.name ?: "?")}</code>: ${esc(ReduxState.preview(p.value))}"
                        },
                    ),
                )
            } else {
                append(section("Valor inicial:", "<code>${esc(ReduxState.preview(value))}</code>"))
            }
            if (obj != null && obj.properties.isEmpty() || value?.text == "null") {
                append(section("Nota:", "Empieza vacío: su contenido lo pone el reducer al procesar una acción (p. ej. la respuesta del backend)."))
            }
            val actions = ReduxState.actions(slice)
            if (actions.isNotEmpty()) append(section("Acciones:", actions.joinToString(", ") { "<code>${esc(it)}</code>" }))
        }
        val html = DocumentationMarkup.DEFINITION_START + "<b>state." + esc(path.joinToString(".")) + "</b>" +
            DocumentationMarkup.DEFINITION_END + DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
        return DocumentationResult.documentation(html)
    }

    private fun section(header: String, content: String) =
        DocumentationMarkup.SECTION_HEADER_START + header + DocumentationMarkup.SECTION_SEPARATOR + content +
            DocumentationMarkup.SECTION_END

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}

/**
 * Ctrl+clic:
 * - `state.catalog` en `useSelector` → la propiedad en el estado inicial del reducer (o el reducer).
 * - una clave de `const { title } = getLanguagePage('página')` → la clave en el JSON del idioma base.
 */
class ReactPropsGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        source ?: return null
        translationKeyTarget(source)?.let { return arrayOf(it) }
        val ref = source.parent as? JSReferenceExpression ?: return null
        val path = ReduxState.selectorPath(ref)?.takeIf { it.isNotEmpty() } ?: return null
        val slice = ReduxState.slices(ref.project)[path.first()] ?: return null
        val target = if (path.size == 1) slice.reducer ?: slice.key else ReduxState.propertyAt(slice, path.drop(1))
        return target?.let { arrayOf(it) }
    }

    private fun translationKeyTarget(source: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(source, JSDestructuringProperty::class.java, false) ?: return null
        val usage = Translations.keyUsage(source) ?: return null
        val files = Translations.files(source.project) ?: return null
        return files.page(files.baseLang, usage.page)?.findProperty(usage.key ?: return null)
    }
}
