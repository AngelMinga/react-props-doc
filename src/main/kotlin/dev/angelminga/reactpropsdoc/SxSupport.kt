package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunctionExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/** Una clave de estilo: valores de csstype y, si es un atajo o clave del tema de MUI, su nota. */
data class SxEntry(val name: String, val values: List<String>, val muiNote: String?, val kind: String) {
    /** Solo valores concretos (sin `string` / `number` genéricos). */
    val literals get() = values.filter { it != "string" && it != "number" && " " !in it && it.first().isLowerCase() }

    /** Atajo que solo entiende MUI (`mt`, `p`, `bgcolor`…): no es una propiedad CSS. */
    val isMuiOnly get() = kind == "mui"
    val isObsolete get() = kind == "obsoleta"
}

/** `sx` acepta CSS y los atajos de MUI; `style` (DOM) solo CSS. */
enum class StyleKind { SX, STYLE }

object SxData {

    val entries: Map<String, SxEntry> by lazy {
        SxData::class.java.getResourceAsStream("/sx-properties.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { cols ->
                cols[0] to SxEntry(
                    cols[0],
                    cols.getOrElse(1) { "" }.split(" | ").filter { it.isNotBlank() },
                    cols.getOrNull(2)?.ifBlank { null },
                    cols.getOrElse(3) { "css" },
                )
            }
    }

    private val SELECTOR_START = setOf('&', '@', ':', '.', '>', '*', '[', '+', '~')
    private val VENDOR = Regex("^(Webkit|Moz|ms|O)[A-Z].*")

    /**
     * ¿Es `obj` un objeto de estilos de `sx={{…}}` o `style={{…}}`? También anidados en selectores
     * (`'&:hover': { … }`). Un objeto que es valor de una clave CSS (`p: { xs: 1, md: 2 }`) es de breakpoints, no de estilos.
     */
    fun styleKind(obj: JSObjectLiteralExpression): StyleKind? {
        var current: PsiElement = obj
        while (true) {
            // Argumento de una llamada (`getSx({ theme })`, `transitions.create([…], { duration })`): no son estilos
            if (current.parent is JSArgumentList) return null
            val property = current.parent as? JSProperty
            if (property != null) {
                val key = property.name ?: return null
                if (key in entries) return null
                current = property.parent as? JSObjectLiteralExpression ?: return null
                continue
            }
            val attribute = PsiTreeUtil.getParentOfType(current, XmlAttribute::class.java) ?: return null
            // Entre el atributo y el objeto no puede haber otro tag JSX
            if (PsiTreeUtil.getParentOfType(current, XmlTag::class.java) != attribute.parent) return null
            return when (attribute.name) {
                "sx" -> StyleKind.SX
                "style" -> StyleKind.STYLE
                else -> null
            }
        }
    }

    private val KEYFRAME = Regex("^(\\d+(\\.\\d+)?%|from|to)$")

    fun isIgnoredKey(name: String) =
        name.isEmpty() || name.first() in SELECTOR_START || ' ' in name || VENDOR.matches(name) || name.startsWith("--") ||
            KEYFRAME.matches(name)

    fun valuesPreview(entry: SxEntry, max: Int = 4): String {
        val literals = entry.literals
        return when {
            literals.isEmpty() -> entry.values.joinToString(" | ").ifEmpty { "valor" }
            literals.size <= max -> literals.joinToString(" | ")
            else -> literals.take(max).joinToString(" | ") + " | …"
        }
    }

    fun entryDoc(entry: SxEntry, kind: StyleKind): String {
        val rows = buildString {
            if (entry.muiNote != null) append(section("MUI:", esc(entry.muiNote)))
            if (kind == StyleKind.STYLE && entry.isMuiOnly) {
                append(section("Aviso:", "⚠ Es un atajo de MUI: en <code>style</code> no funciona (usa <code>sx</code>)."))
            }
            val literals = entry.literals
            if (literals.isNotEmpty()) {
                append(section("Valores:", literals.take(40).joinToString(", ") { "<code>${esc(it)}</code>" } + if (literals.size > 40) ", …" else ""))
            }
            val generic = entry.values.filter { it !in literals }
            if (generic.isNotEmpty()) append(section("También:", esc(generic.joinToString(" | "))))
            val example = literals.firstOrNull()?.let { "'$it'" } ?: if (entry.muiNote?.contains("spacing") == true) "2" else "…"
            val attr = if (kind == StyleKind.SX) "sx" else "style"
            append(section("Ejemplo:", "<code>${esc("$attr={{ ${entry.name}: $example }}")}</code>"))
        }
        return DocumentationMarkup.DEFINITION_START + "<b>${esc(entry.name)}</b>" +
            (if (entry.isMuiOnly) " (atajo de MUI)" else if (entry.isObsolete) " (CSS obsoleta)" else " (CSS)") +
            DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
    }

    /** Tipo de una clave como unión: `'absolute' | 'fixed' | …` más lo genérico (`string`, `number`). */
    fun typeText(entry: SxEntry, max: Int = 6): String {
        val literals = entry.literals
        val quoted = literals.take(max).map { "'$it'" } + if (literals.size > max) listOf("…") else emptyList()
        val generic = entry.values.filter { it !in literals }.distinct()
        // `número (px) o string | string` → sin el `string` repetido
        val cleaned = generic.filter { g -> generic.none { it != g && g in it.split(' ') } }
        return (quoted + cleaned).joinToString(" | ").ifEmpty { "valor" }
    }

    /** El objeto de estilos del atributo: `sx={{…}}`, `sx={estilos}` (variable) o `sx={(theme) => ({…})}`. */
    fun styleObject(attribute: XmlAttribute): JSObjectLiteralExpression? {
        val expression = PsiTreeUtil.findChildOfType(attribute.valueElement, JSExpression::class.java) ?: return null
        return when (expression) {
            is JSObjectLiteralExpression -> expression
            is JSReferenceExpression -> ((expression.resolve() as? JSVariable)?.initializer as? JSObjectLiteralExpression)
            is JSFunctionExpression -> PsiTreeUtil.findChildOfType(expression, JSObjectLiteralExpression::class.java)
            else -> null
        }
    }

    /** Una fila por clave escrita: su tipo y el valor que tiene. */
    private fun usedKeys(obj: JSObjectLiteralExpression, kind: StyleKind): String =
        obj.properties.mapNotNull { property ->
            val name = property.name ?: return@mapNotNull null
            val value = property.value
            val current = value?.text?.let { StringUtil.shortenTextWithEllipsis(it.replace(Regex("\\s+"), " "), 30, 0) }
            val entry = entries[name]
            val type = when {
                isIgnoredKey(name) || entry == null && value is JSObjectLiteralExpression -> "estilos anidados (selector)"
                entry == null -> "⚠ clave desconocida"
                kind == StyleKind.STYLE && entry.isMuiOnly -> "⚠ atajo de MUI: no funciona en style"
                value is JSObjectLiteralExpression -> "responsive { xs, sm, md… } de " + esc(typeText(entry))
                else -> esc(typeText(entry))
            }
            "<code>${esc(name)}</code>: $type" + (current?.takeIf { value !is JSObjectLiteralExpression }?.let { " &nbsp;= <code>${esc(it)}</code>" } ?: "")
        }.joinToString("<br/>")

    fun attributeDoc(kind: StyleKind, attribute: XmlAttribute? = null): String {
        val all = entries.values
        val shortcuts = all.filter { it.isMuiOnly }.map { it.name }.sorted()
        val rows = buildString {
            append(section("Tipo:", if (kind == StyleKind.SX) "<code>SxProps&lt;Theme&gt;</code> (@mui/system)" else "<code>React.CSSProperties</code>"))
            val used = attribute?.let { styleObject(it) }?.takeIf { it.properties.isNotEmpty() }
            if (used != null) append(section("En este ${if (kind == StyleKind.SX) "sx" else "style"}:", usedKeys(used, kind)))
            if (kind == StyleKind.SX) {
                append(section("Acepta:", "${all.size} claves: propiedades CSS en camelCase (<code>position</code>, <code>cursor</code>…) y atajos de MUI."))
                append(section("Atajos de MUI:", shortcuts.take(30).joinToString(", ") { "<code>${esc(it)}</code>" } + " … (espaciado: 1 = 8px)"))
                append(section("Valores del tema:", "colores <code>'primary.main'</code>, <code>'text.secondary'</code>; <code>borderRadius: 2</code> = 2 × theme.shape.borderRadius"))
                append(section("Responsive:", "<code>{ p: { xs: 1, md: 3 } }</code>"))
                append(section("Selectores:", "<code>{ '&amp;:hover': { color: 'primary.main' } }</code>"))
                append(section("Función:", "<code>sx={(theme) =&gt; ({ … })}</code>"))
            } else {
                append(section("Acepta:", "propiedades CSS en camelCase (sin atajos de MUI ni valores del tema)."))
            }
            append(section("Ayuda:", "Ctrl+Espacio dentro de <code>{{ }}</code> lista las claves y valores; pasa el mouse sobre una clave para ver los suyos."))
        }
        return DocumentationMarkup.DEFINITION_START + "<b>${if (kind == StyleKind.SX) "sx" else "style"}</b>" +
            DocumentationMarkup.DEFINITION_END + DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
    }

    private fun section(header: String, content: String) =
        DocumentationMarkup.SECTION_HEADER_START + header + DocumentationMarkup.SECTION_SEPARATOR + content + DocumentationMarkup.SECTION_END

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}

/** `sx={{ │ }}`: claves de estilo; `sx={{ position: '│' }}`: sus valores. */
class SxCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val literal = position.parent as? JSLiteralExpression
                if (literal != null) {
                    completeValue(literal, parameters, result)
                    return
                }
                // Clave: `{ pos│ }` (abreviada) o `{ pos│: 'x' }`
                val property = (position.parent as? JSReferenceExpression)?.parent as? JSProperty
                    ?: position.parent as? JSProperty
                    ?: return
                if (property.value != null && property.value != position.parent && property.nameIdentifier != position) return
                val obj = property.parent as? JSObjectLiteralExpression ?: return
                val kind = SxData.styleKind(obj) ?: return
                val existing = obj.properties.mapNotNull { it.name }.toSet()
                SxData.entries.values
                    .filter { kind == StyleKind.SX || !it.isMuiOnly }
                    .filter { it.name !in existing }
                    .forEach { entry ->
                        result.addElement(
                            LookupElementBuilder.create(entry.name)
                                .withTypeText(SxData.valuesPreview(entry, 3), true)
                                .withTailText(entry.muiNote?.let { "  ($it)" } ?: "", true)
                                .withBoldness(entry.isMuiOnly),
                        )
                    }
                result.stopHere()
            }

            private fun completeValue(literal: JSLiteralExpression, parameters: CompletionParameters, result: CompletionResultSet) {
                if (!literal.isQuotedLiteral) return
                val property = literal.parent as? JSProperty ?: return
                val obj = property.parent as? JSObjectLiteralExpression ?: return
                SxData.styleKind(obj) ?: return
                val entry = SxData.entries[property.name ?: return] ?: return
                val prefix = parameters.editor.document.charsSequence
                    .subSequence(literal.textRange.startOffset + 1, parameters.offset).toString()
                val values = result.withPrefixMatcher(prefix)
                entry.literals.forEach { values.addElement(LookupElementBuilder.create(it).withTypeText(entry.name)) }
                if (entry.literals.isNotEmpty()) result.stopHere()
            }
        })
    }
}

/** Hover sobre una clave de `sx`/`style` o sobre el propio atributo `sx`/`style`. */
class SxDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val leaf = file.findElementAt(offset) ?: return emptyList()
        // El atributo: `sx=`
        (leaf.parent as? XmlAttribute)?.takeIf { it.nameElement == leaf }?.let { attribute ->
            val kind = when (attribute.name) {
                "sx" -> StyleKind.SX
                "style" -> StyleKind.STYLE
                else -> return emptyList()
            }
            if (PsiTreeUtil.getParentOfType(attribute, XmlTag::class.java)?.name?.firstOrNull()?.isUpperCase() != true && kind == StyleKind.SX) return emptyList()
            return listOf(SxDocTarget(attribute, null, kind))
        }
        // Una clave: `position:` (o `'position':`)
        val property = PsiTreeUtil.getParentOfType(leaf, JSProperty::class.java, false) ?: return emptyList()
        if (property.nameIdentifier?.textRange?.contains(offset) != true && property.value?.textRange?.contains(offset) == true) return emptyList()
        val obj = property.parent as? JSObjectLiteralExpression ?: return emptyList()
        val kind = SxData.styleKind(obj) ?: return emptyList()
        val entry = SxData.entries[property.name ?: return emptyList()] ?: return emptyList()
        return listOf(SxDocTarget(property, entry, kind))
    }
}

class SxDocTarget(private val element: PsiElement, private val entry: SxEntry?, private val kind: StyleKind) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let { SxDocTarget(it, entry, kind) } }
    }

    override fun computePresentation() = TargetPresentation.builder(entry?.name ?: kind.name.lowercase()).presentation()

    override fun computeDocumentation() =
        DocumentationResult.documentation(if (entry != null) SxData.entryDoc(entry, kind) else SxData.attributeDoc(kind, element as? XmlAttribute))
}

/** Claves de `sx`/`style` desconocidas (`positon`) y atajos de MUI usados en `style` (no funcionan ahí). */
class SxUnknownKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val property = element as? JSProperty ?: return
            val name = property.name ?: return
            val obj = property.parent as? JSObjectLiteralExpression ?: return
            val kind = SxData.styleKind(obj) ?: return
            if (SxData.isIgnoredKey(name)) return
            // `svg: { … }`, `td: { … }`: emotion trata como selector una clave cuyo valor es un objeto
            if (property.value is JSObjectLiteralExpression) return
            // `{ theme, isSelected }` (abreviada): no es un objeto de estilos
            if (!property.text.contains(':')) return
            val anchor = property.nameIdentifier ?: property.firstChild ?: return
            val entry = SxData.entries[name]
            when {
                entry != null && kind == StyleKind.STYLE && entry.isMuiOnly -> holder.registerProblem(
                    anchor,
                    "'$name' es un atajo de MUI: en style no funciona (usa sx" +
                        (entry.muiNote?.let { Regex("atajo de (\\w+)").find(it)?.groupValues?.get(1) }?.let { " o $it" } ?: "") + ")",
                    *listOfNotNull(entry.muiNote?.let { Regex("atajo de (\\w+)").find(it)?.groupValues?.get(1) }?.let { RenameSxKeyFix(it, 0) }).toTypedArray(),
                )
                entry == null -> {
                    val similar = SxData.entries.keys
                        .filter { kind == StyleKind.SX || SxData.entries.getValue(it).isMuiOnly.not() }
                        .map { it to levenshtein(it.lowercase(), name.lowercase()) }
                        .filter { it.second <= 2 }
                        .sortedBy { it.second }
                        .take(3)
                        .mapIndexed { index, (candidate, _) -> RenameSxKeyFix(candidate, index) }
                    holder.registerProblem(anchor, "${if (kind == StyleKind.SX) "sx" else "style"} no reconoce '$name'", *similar.toTypedArray())
                }
            }
        }
    }
}

class RenameSxKeyFix(private val newName: String, private val rank: Int) : LocalQuickFix, com.intellij.codeInsight.intention.PriorityAction {
    override fun getFamilyName() = "Cambiar la clave de estilo"
    override fun getName() = "Cambiar por '$newName'"
    override fun getPriority() =
        if (rank == 0) com.intellij.codeInsight.intention.PriorityAction.Priority.HIGH else com.intellij.codeInsight.intention.PriorityAction.Priority.NORMAL

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val property = PsiTreeUtil.getParentOfType(descriptor.psiElement, JSProperty::class.java, false) ?: return
        val nameElement = property.nameIdentifier ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(property.containingFile) ?: return
        val quoted = nameElement.text.firstOrNull()?.let { it == '\'' || it == '"' } == true
        val replacement = if (quoted) "${nameElement.text.first()}$newName${nameElement.text.first()}" else newName
        document.replaceString(nameElement.textRange.startOffset, nameElement.textRange.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
