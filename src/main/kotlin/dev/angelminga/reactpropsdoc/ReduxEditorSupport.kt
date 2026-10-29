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

/**
 * `useSelector(state => state.│)`: partes del estado; `state.catalog.│` o `catalogState.data.│` (variable que viene
 * de `useSelector`): claves del estado inicial y las que se usan en el código.
 */
class ReduxCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val ref = parameters.position.parent as? JSReferenceExpression ?: return
                val qualifier = ref.qualifier as? JSReferenceExpression ?: return
                val project = ref.project
                val slices = ReduxState.slices(project)
                val parentPath = ReduxState.selectorPath(ref)?.dropLast(1)
                    ?: ReduxState.valuePath(qualifier)
                    ?: return
                if (parentPath.isEmpty()) {
                    slices.values.forEach { slice ->
                        result.addElement(
                            LookupElementBuilder.create(slice.name)
                                .withTypeText(ReduxState.preview(slice.initialState, 40))
                                .withTailText("  (Redux)", true),
                        )
                    }
                    // Se conocen las partes del estado: sin la lista genérica de propiedades del IDE
                    if (slices.isNotEmpty()) result.stopHere()
                    return
                }
                val slice = slices[parentPath.first()] ?: return
                val added = mutableSetOf<String>()
                (ReduxState.valueAt(slice, parentPath.drop(1)) as? JSObjectLiteralExpression)?.properties?.forEach { property ->
                    val name = property.name ?: return@forEach
                    added += name
                    result.addElement(LookupElementBuilder.create(name).withTypeText(ReduxState.preview(property.value, 40)))
                }
                // Lo que llega en tiempo de ejecución (p. ej. catalog.data.entity) se deduce de los usos en el código
                ReduxUsage.observed(project).at(parentPath)?.children?.forEach { (name, node) ->
                    if (name in added || name.contains("IntellijIdeaRulezzz")) return@forEach
                    added += name
                    result.addElement(
                        LookupElementBuilder.create(name).withTypeText("usado ${node.uses}×").withTailText("  (visto en el código)", true),
                    )
                }
                // Con claves conocidas, se ocultan las sugerencias genéricas (constructor, toString, a, aac…)
                if (added.isNotEmpty()) result.stopHere()
            }
        })
    }
}

/** Hover sobre `state.catalog` dentro de `useSelector`, o sobre `catalogState.data` si viene de `useSelector`. */
class ReduxDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val ref = file.findElementAt(offset)?.parent as? JSReferenceExpression ?: return emptyList()
        val path = (ReduxState.selectorPath(ref) ?: ref.qualifier?.let { ReduxState.valuePath(ref) })
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()
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
        val observed = ReduxUsage.observed(ref.project).at(path)
        val rows = buildString {
            append(section("Reducer:", "<code>${esc(slice.name)}</code> — ${esc(slice.reducer?.containingFile?.name ?: "?")}"))
            val obj = value as? JSObjectLiteralExpression
            when {
                obj != null && obj.properties.isNotEmpty() -> append(
                    section(
                        "Estado inicial:",
                        obj.properties.joinToString("<br/>") { p ->
                            "<code>${esc(p.name ?: "?")}</code>: ${esc(ReduxState.preview(p.value))}"
                        },
                    ),
                )
                value != null -> append(section("Valor inicial:", "<code>${esc(ReduxState.preview(value))}</code>"))
                else -> append(section("Valor inicial:", "<i>no está en el estado inicial (llega en tiempo de ejecución)</i>"))
            }
            if (observed != null && observed.children.isNotEmpty()) {
                append(
                    section(
                        "Usado en el código:",
                        ReduxUsage.describe(observed).joinToString("<br/>") { "<code>${esc(it.substringBefore(' '))}</code>${esc(it.removePrefix(it.substringBefore(' ')))}" },
                    ),
                )
            } else if (obj != null && obj.properties.isEmpty() || value?.text == "null") {
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

/** Hover sobre `buttons.cancel` cuando viene de `getLanguagePage(…)`: el texto en cada idioma. */
class TranslationAccessDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!ReactPropsDocSettings.options.showTranslations) return emptyList()
        val ref = file.findElementAt(offset)?.parent as? JSReferenceExpression ?: return emptyList()
        if (ref.qualifier == null) return emptyList()
        val path = Translations.expressionPath(ref)?.takeIf { it.size > 1 } ?: return emptyList()
        val files = Translations.files(file.project) ?: return emptyList()
        if (files.valueAt(files.baseLang, path) == null) return emptyList()
        return listOf(TranslationAccessTarget(ref, path))
    }
}

class TranslationAccessTarget(private val ref: JSReferenceExpression, private val path: List<String>) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(ref)
        return Pointer { pointer.element?.let { TranslationAccessTarget(it, path) } }
    }

    override fun computePresentation() = TargetPresentation.builder(path.drop(1).joinToString(".")).presentation()

    override fun computeDocumentation() =
        DocumentationResult.documentation(ReactPropsDocumentation.translationHtml(ref.project, path))
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
        val path = (ReduxState.selectorPath(ref) ?: ref.qualifier?.let { ReduxState.valuePath(ref) })
            ?.takeIf { it.isNotEmpty() } ?: return null
        val slice = ReduxState.slices(ref.project)[path.first()] ?: return null
        val target = if (path.size == 1) slice.reducer ?: slice.key else ReduxState.propertyAt(slice, path.drop(1))
        return target?.let { arrayOf(it) }
    }

    /**
     * Clave desestructurada (`const { title } = getLanguagePage(…)`, también anidada) o acceso `buttons.cancel`
     * cuyo origen es `getLanguagePage` → la propiedad en el JSON del idioma base.
     * Una variable suelta (`buttons`) sigue yendo a su declaración, como siempre.
     */
    private fun translationKeyTarget(source: PsiElement): PsiElement? {
        val files = Translations.files(source.project) ?: return null
        val path = PsiTreeUtil.getParentOfType(source, JSDestructuringProperty::class.java, false)
            ?.let { Translations.pathOfElement(it) }
            ?: (source.parent as? JSReferenceExpression)?.takeIf { it.qualifier != null }?.let { Translations.expressionPath(it) }
            ?: return null
        if (path.size < 2) return null
        return files.propertyAt(files.baseLang, path)
    }
}
