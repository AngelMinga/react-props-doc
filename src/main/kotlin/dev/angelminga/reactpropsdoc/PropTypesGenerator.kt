package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/** Deduce el `PropTypes.x` de una prop por su valor por defecto o, si no tiene, por su nombre. */
object PropTypeInference {

    fun infer(name: String, defaultValue: String?): String {
        defaultValue?.trim()?.let { value ->
            when {
                value == "true" || value == "false" -> return "bool"
                value.matches(Regex("-?\\d+(\\.\\d+)?")) -> return "number"
                value.startsWith("'") || value.startsWith("\"") || value.startsWith("`") -> return "string"
                value.startsWith("[") -> return "array"
                value.startsWith("{") -> return "object"
                "=>" in value || value.startsWith("function") -> return "func"
            }
        }
        return when {
            name == "children" -> "node"
            Regex("^(on|handle)[A-Z].*").matches(name) || name.startsWith("set") && name.length > 3 && name[3].isUpperCase() -> "func"
            Regex("^(is|has|show|can|should|enable|disable|mask|with|hide|open)([A-Z].*)?$").matches(name) ||
                name in setOf("open", "disabled", "loading", "checked", "required", "fullWidth", "visible") -> "bool"
            Regex(".*(List|Items|Options|Rows|Columns)$").matches(name) -> "array"
            Regex("^(count|size|index|width|height|max|min|page|total|limit|step|amount)([A-Z].*)?$").matches(name) ||
                Regex(".*(Count|Index|Size|Width|Height)$").matches(name) -> "number"
            Regex("^(title|text|label|description|name|icon|color|variant|placeholder|url|id|type|alt|src|message)$").matches(name) ||
                Regex("^(message|text|label|title|description|placeholder)[A-Z].*").matches(name) ||
                Regex(".*(Text|Label|Title|Color|Url|Id|Name|Message|Icon|Description|Placeholder)$").matches(name) -> "string"
            Regex("^(data|args|style|sx|props|params|config)$").matches(name) ||
                Regex("^(data|info|config|params)[A-Z].*").matches(name) ||
                Regex(".*(Data|Info|Props|Config|Params|Object|State|Style)$").matches(name) -> "object"
            else -> "any"
        }
    }
}

/** Edición de `Componente.propTypes` como texto, respetando la indentación del archivo. */
object PropTypesWriter {

    /** Agrega las props a `propTypes` (creando el bloque y el import si no existen). */
    fun addProps(component: ReactComponent, names: List<String>) {
        if (names.isEmpty()) return
        val file = component.file
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return
        val defaults = ReactPropsExtractor.propsUsage(component.function).props
        val propTypes = propTypesName(file)
        val unit = if ("\n\t" in file.text) "\t" else "    "
        val entries = names.map { "$it: $propTypes.${PropTypeInference.infer(it, defaults[it])}" }

        val existing = ReactPropsExtractor.propTypesObject(component)
        val edits = mutableListOf<Pair<Int, String>>()
        if (existing != null) {
            val last = existing.properties.lastOrNull()
            if (last == null) {
                edits += existing.firstChild.textRange.endOffset to entries.joinToString(",") { "\n$unit$it" } + "\n"
            } else {
                val before = (last.prevSibling as? PsiWhiteSpace)?.text.orEmpty()
                val separator = if ('\n' in before) "\n" + before.substringAfterLast('\n') else " "
                // Si ya hay coma final (`a: x,`), se escribe después de ella
                val trailingComma = PsiTreeUtil.skipWhitespacesForward(last)?.takeIf { it.text == "," }
                val anchor = trailingComma ?: last
                val text = if (trailingComma != null) entries.joinToString("") { "$separator$it," }
                else entries.joinToString("") { ",$separator$it" }
                edits += anchor.textRange.endOffset to text
            }
        } else {
            val block = "${component.name}.propTypes = {" + entries.joinToString(",") { "\n$unit$it" } + "\n}"
            edits += blockInsertion(component, block)
        }
        importOffset(file)?.let { edits += it to "import PropTypes from 'prop-types'\n" }

        edits.sortedByDescending { it.first }.forEach { (offset, text) -> document.insertString(offset, text) }
        documents.commitDocument(document)
    }

    /** Nombre con que el archivo importa prop-types (`PropTypes` por defecto). */
    private fun propTypesName(file: PsiFile): String =
        Regex("import\\s+(\\w+)\\s+from\\s+['\"]prop-types['\"]").find(file.text)?.groupValues?.get(1) ?: "PropTypes"

    /** Dónde agregar `import PropTypes…` (null si ya existe): después del último import, o al inicio. */
    private fun importOffset(file: PsiFile): Int? {
        if (Regex("from\\s+['\"]prop-types['\"]").containsMatchIn(file.text)) return null
        val lastImport = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).lastOrNull()
        return lastImport?.let { it.textRange.endOffset + 1 } ?: 0
    }

    /**
     * Antes de `export default Componente` (el bloque termina en línea en blanco), o después de la
     * sentencia que declara el componente (el bloque empieza con línea en blanco).
     */
    private fun blockInsertion(component: ReactComponent, block: String): Pair<Int, String> {
        PsiTreeUtil.findChildrenOfType(component.file, ES6ExportDefaultAssignment::class.java)
            .firstOrNull { it.text.replace(Regex("\\s+"), " ").trim().removeSuffix(";") == "export default ${component.name}" }
            ?.let { return it.textRange.startOffset to "$block\n\n" }
        var statement: PsiElement = component.declaration
        while (statement.parent != null && statement.parent !is PsiFile) statement = statement.parent
        return statement.textRange.endOffset to "\n\n$block"
    }
}

/** Props que el componente usa y no están en `propTypes` (solo si el componente ya tiene `propTypes`). */
class UndeclaredPropInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val function = element as? JSFunction ?: return
            // Solo componentes de primer nivel (no funciones internas)
            if (PsiTreeUtil.getParentOfType(function, JSFunction::class.java) != null) return
            val component = ReactPropsExtractor.findComponent(function)?.takeIf { it.function == function } ?: return
            val propTypes = ReactPropsExtractor.propTypesObject(component) ?: return
            val declared = propTypes.properties.mapNotNull { it.name }.toSet()
            val usage = ReactPropsExtractor.propsUsage(function)
            val undeclared = usage.props.keys.filter { it !in declared }
            undeclared.forEach { name ->
                val anchor = usage.anchors[name] ?: return@forEach
                val fixes = buildList<LocalQuickFix> {
                    add(AddPropTypesFix(listOf(name)))
                    if (undeclared.size > 1) add(AddPropTypesFix(undeclared))
                }
                holder.registerProblem(
                    anchor,
                    "'$name' no está declarada en ${component.name}.propTypes",
                    ProblemHighlightType.WEAK_WARNING,
                    *fixes.toTypedArray(),
                )
            }
        }
    }
}

class AddPropTypesFix(private val names: List<String>) : LocalQuickFix {

    override fun getFamilyName() = "Agregar a propTypes"

    override fun getName() =
        if (names.size == 1) "Agregar '${names.single()}' a propTypes" else "Agregar las ${names.size} props que faltan a propTypes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val function = PsiTreeUtil.getParentOfType(descriptor.psiElement, JSFunction::class.java, false)
            ?.let { generateSequence(it) { f -> PsiTreeUtil.getParentOfType(f, JSFunction::class.java) }.last() }
            ?: return
        val component = ReactPropsExtractor.findComponent(function) ?: return
        PropTypesWriter.addProps(component, names)
    }
}

/** Sobre el nombre de un componente sin `propTypes`: genera el bloque con las props que usa. */
class GeneratePropTypesIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName() = "Generar propTypes"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val component = componentAt(element) ?: return false
        if (ReactPropsExtractor.propTypesObject(component) != null) return false
        val props = ReactPropsExtractor.propsUsage(component.function).props
        if (props.isEmpty()) return false
        text = "Generar propTypes para ${component.name} (${props.size} props)"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val component = componentAt(element) ?: return
        PropTypesWriter.addProps(component, ReactPropsExtractor.propsUsage(component.function).props.keys.toList())
    }

    /** El cursor debe estar sobre el nombre del componente en su declaración. */
    private fun componentAt(element: PsiElement): ReactComponent? {
        val declaration = element.parent as? JSVariable ?: element.parent as? JSFunction ?: return null
        if ((declaration as? JSVariable)?.nameIdentifier != element && (declaration as? JSFunction)?.nameIdentifier != element) return null
        if (element.containingFile.name.endsWith(".d.ts")) return null
        return ReactPropsExtractor.findComponent(declaration)?.takeIf { it.function != null }
    }
}
