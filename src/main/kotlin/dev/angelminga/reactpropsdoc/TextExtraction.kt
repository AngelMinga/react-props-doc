package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlText
import java.text.Normalizer

/** Texto fijo en JSX que se puede llevar a las traducciones: contenido de un tag o valor de una prop de texto. */
data class HardcodedText(val range: TextRange, val text: String, val isAttribute: Boolean, val anchor: PsiElement)

/** De dónde sacar los textos dentro del componente: `const { … } = getLanguagePage('p')` o `const t = getLanguagePage('p')`. */
data class TranslationTarget(val page: String, val destructuring: JSDestructuringObject?, val variable: String?)

object TextExtraction {

    private val TEXT_ATTRIBUTE = Regex(
        "^(title|subtitle|label|text|placeholder|description|helperText|alt|message|aria-label|tooltip|" +
            "\\w*(Text|Label|Title|Description|Message|Placeholder))$",
    )
    private val HAS_WORD = Regex("\\p{L}{2,}")
    private val ENTITY = Regex("&#?\\w+;")

    /** Tiene al menos una palabra (sin contar entidades como `&nbsp;`). */
    private fun hasWords(text: String) = HAS_WORD.containsMatchIn(text.replace(ENTITY, " "))

    /** Texto fijo bajo el cursor (o null si no aplica). */
    fun find(element: PsiElement): HardcodedText? = attributeText(element) ?: contentText(element)

    /** `title="Monto"` en una prop de texto. */
    private fun attributeText(element: PsiElement): HardcodedText? {
        val attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false) ?: return null
        if (!TEXT_ATTRIBUTE.matches(attribute.name)) return null
        val valueElement = attribute.valueElement ?: return null
        if (!valueElement.text.startsWith("\"") && !valueElement.text.startsWith("'")) return null
        val value = attribute.value ?: return null
        // Valores técnicos (`body1`, `primary`, `outlined`) no son textos para el usuario
        if (!hasWords(value) || Regex("^[a-z][A-Za-z0-9_-]*$").matches(value.trim())) return null
        return HardcodedText(valueElement.textRange, value.trim(), isAttribute = true, anchor = attribute)
    }

    /** Texto entre etiquetas: JSX lo divide en palabras (`Monto`, ` `, `total`), así que se une el tramo contiguo. */
    private fun contentText(element: PsiElement): HardcodedText? {
        val start = PsiTreeUtil.getParentOfType(element, XmlText::class.java, false)
            ?: (element as? PsiWhiteSpace)?.let { it.prevSibling as? XmlText ?: it.nextSibling as? XmlText }
            ?: return null
        fun isPart(e: PsiElement?) = e is XmlText || e is PsiWhiteSpace
        var first: PsiElement = start
        while (isPart(first.prevSibling)) first = first.prevSibling
        var last: PsiElement = start
        while (isPart(last.nextSibling)) last = last.nextSibling
        while (first is PsiWhiteSpace && first != last) first = first.nextSibling
        while (last is PsiWhiteSpace && last != first) last = last.prevSibling
        if (first !is XmlText || last !is XmlText) return null
        val range = TextRange(first.textRange.startOffset, last.textRange.endOffset)
        val text = range.substring(element.containingFile.text).replace(Regex("\\s+"), " ").trim()
        if (!hasWords(text)) return null
        return HardcodedText(range, text, isAttribute = false, anchor = first)
    }

    /** Componente (función de primer nivel) que contiene el elemento. */
    private fun componentFunction(element: PsiElement): JSFunction? =
        generateSequence(PsiTreeUtil.getParentOfType(element, JSFunction::class.java)) {
            PsiTreeUtil.getParentOfType(it, JSFunction::class.java)
        }.lastOrNull()

    /** Uso de `getLanguagePage` en el componente; se prefiere una desestructuración que no sea de `modalCancel`. */
    fun target(element: PsiElement): TranslationTarget? {
        val function = componentFunction(element) ?: return null
        val destructurings = PsiTreeUtil.findChildrenOfType(function, JSDestructuringElement::class.java)
            .mapNotNull { d ->
                val page = Translations.pageOf(Translations.translationCall(d.initializer)) ?: return@mapNotNull null
                val obj = d.target as? JSDestructuringObject ?: return@mapNotNull null
                TranslationTarget(page, obj, null)
            }
        destructurings.firstOrNull { it.page != "modalCancel" }?.let { return it }
        PsiTreeUtil.findChildrenOfType(function, JSVariable::class.java).forEach { v ->
            val page = Translations.pageOf(Translations.translationCall(v.initializer)) ?: return@forEach
            val name = v.name ?: return@forEach
            if (page != "modalCancel") return TranslationTarget(page, null, name)
        }
        return destructurings.firstOrNull()
    }

    /**
     * Disponible si el componente ya usa `getLanguagePage`, o si se le puede agregar (función con cuerpo `{ … }`).
     */
    fun isAvailable(element: PsiElement): Boolean {
        if (find(element) == null) return false
        Translations.files(element.project) ?: return false
        return target(element) != null || componentFunction(element)?.block != null
    }

    /** `¿Quieres enviar esa fotografía?` → `quieresEnviarEsaFotografia` (máx. 4 palabras). */
    fun suggestKey(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val words = Regex("[A-Za-z0-9]+").findAll(plain).map { it.value.lowercase() }.take(4).toList()
        if (words.isEmpty()) return "texto"
        val key = words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        return if (key.first().isDigit()) "t$key" else key
    }

    /**
     * Página sugerida para un componente sin `getLanguagePage`: la más usada en los archivos de la misma carpeta,
     * o la que coincide con una carpeta (`pages/Investment/…` → `investmentPage`).
     */
    fun suggestPage(file: PsiFile, files: TranslationFiles): String {
        val pages = files.pages().map { it.name }.toSet()
        val dir = file.containingDirectory
        val nearby = generateSequence(dir) { it.parentDirectory }.take(3)
            .flatMap { d -> d.files.asSequence().filter { it != file } }
            .flatMap { f -> Regex("getLanguagePage\\(\\s*['\"](\\w+)['\"]").findAll(f.text).map { it.groupValues[1] } }
            .filter { it in pages && it != "modalCancel" }
            .groupingBy { it }.eachCount()
        nearby.maxByOrNull { it.value }?.let { return it.key }
        generateSequence(dir) { it.parentDirectory }.take(6).forEach { d ->
            val candidate = d.name.replaceFirstChar(Char::lowercase) + "Page"
            if (candidate in pages) return candidate
        }
        return ""
    }

    /** Import de `useTranslateContext` que ya usa la misma app (p. ej. `MFComponents/ComponentUseTranslateContext`). */
    private fun translateContextImport(file: PsiFile): String {
        val path = file.virtualFile?.path ?: return DEFAULT_TRANSLATE_IMPORT
        val app = path.substringBefore("/src/", "")
        val sources = mutableListOf<String>()
        PsiSearchHelper.getInstance(file.project).processAllFilesWithWord("useTranslateContext", GlobalSearchScope.projectScope(file.project), { f ->
            if (app.isNotEmpty() && f.virtualFile?.path?.startsWith("$app/src/") == true) {
                Regex("import\\s*\\{[^}]*\\buseTranslateContext\\b[^}]*\\}\\s*from\\s*['\"]([^'\"]+)['\"]").find(f.text)
                    ?.let { sources += it.groupValues[1] }
            }
            true
        }, true)
        return sources.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DEFAULT_TRANSLATE_IMPORT
    }

    private const val DEFAULT_TRANSLATE_IMPORT = "MFComponents/ComponentUseTranslateContext"

    /**
     * Pide la clave (sugerida a partir del texto) y extrae: agrega la clave al idioma base (y marcada a los demás),
     * reemplaza el texto por la variable y la agrega a la desestructuración de `getLanguagePage`.
     * Si el componente no usa `getLanguagePage`, primero pide la página y agrega `useTranslateContext` y la llamada.
     */
    fun perform(project: Project, element: PsiElement) {
        val hardcoded = find(element) ?: return
        val files = Translations.files(project) ?: return
        val function = componentFunction(element) ?: return
        val existingTarget = target(element)
        val jsFile = element.containingFile

        val page = existingTarget?.page ?: Messages.showEditableChooseDialog(
            "El componente aún no usa traducciones.\nPágina de ${files.base.name} (elige una o escribe una nueva):",
            "Extraer a traducciones",
            null,
            files.pages().map { it.name }.toTypedArray(),
            suggestPage(jsFile, files),
            object : InputValidator {
                override fun checkInput(input: String?) = input != null && IDENTIFIER.matches(input.trim())
                override fun canClose(input: String?) = checkInput(input)
            },
        )?.trim() ?: return
        val target = existingTarget ?: TranslationTarget(page, null, null)
        val basePage = files.page(files.baseLang, page)
        val usedNames = PsiTreeUtil.findChildrenOfType(function, JSVariable::class.java).mapNotNull { it.name }.toSet()
        val destructured = target.destructuring?.properties?.mapNotNull { it.name }.orEmpty().toSet()

        // Válida si es un identificador y, si ya existe en la página, tiene el mismo texto
        fun conflict(key: String): String? {
            if (!IDENTIFIER.matches(key)) return "No es un nombre válido"
            val existing = basePage?.findProperty(key)?.value
            if (existing != null && (existing as? JsonStringLiteral)?.value != hardcoded.text) {
                return "'$key' ya existe en '$page' con otro texto"
            }
            if (target.variable == null && key in usedNames && key !in destructured) {
                return "Ya hay una variable '$key' en el componente"
            }
            return null
        }

        var suggestion = suggestKey(hardcoded.text)
        var n = 2
        while (conflict(suggestion) != null && n < 50) suggestion = suggestKey(hardcoded.text) + n++

        val key = Messages.showInputDialog(
            project,
            "Clave en '$page' (${files.base.name}) para:\n\"${hardcoded.text}\"",
            "Extraer a traducciones",
            null,
            suggestion,
            object : InputValidator {
                override fun checkInput(input: String?) = input != null && conflict(input.trim()) == null
                override fun canClose(input: String?) = checkInput(input)
            },
        )?.trim() ?: return

        val importSource = if (existingTarget == null) translateContextImport(jsFile) else null
        val jsonFiles = files.files.values.toList()
        WriteCommandAction.runWriteCommandAction(project, "Extraer a traducciones", null, {
            apply(jsFile, function, hardcoded, target, key, files, importSource)
        }, *(listOf<PsiFile>(jsFile) + jsonFiles).toTypedArray())
    }

    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private fun apply(
        jsFile: PsiFile,
        function: JSFunction,
        hardcoded: HardcodedText,
        target: TranslationTarget,
        key: String,
        files: TranslationFiles,
        importSource: String?,
    ) {
        // 1) JSON: idioma base con el texto; los demás con el texto marcado. Si la página no existe, se crea.
        val escaped = StringUtil.escapeStringCharacters(hardcoded.text)
        val prefix = Translations.copyPrefix(files)
        files.files.keys.forEach { lang ->
            val value = if (lang == files.baseLang) escaped else prefix + escaped
            val page: JsonObject? = files.page(lang, target.page)
            if (page == null) {
                files.root(lang)?.let { Translations.appendEntries(it, listOf("\"${target.page}\": { \"$key\": \"$value\" }")) }
                return@forEach
            }
            if (page.findProperty(key) != null) return@forEach
            Translations.appendEntries(page, listOf("\"$key\": \"$value\""))
        }

        // 2) JS: reemplazar el texto; agregar la clave a la desestructuración (o crear las llamadas si no existen)
        val documents = PsiDocumentManager.getInstance(jsFile.project)
        val document = documents.getDocument(jsFile) ?: return
        val expression = target.variable?.let { "$it.$key" } ?: key
        val edits = mutableListOf(hardcoded.range to "{$expression}")
        val obj = target.destructuring
        if (obj != null) {
            if (obj.properties.none { it.name == key }) {
                val last = obj.properties.lastOrNull()
                edits += if (last != null) TextRange.from(last.textRange.endOffset, 0) to ", $key"
                else TextRange.from(obj.textRange.startOffset + 1, 0) to " $key "
            }
        } else if (target.variable == null) {
            edits += setupEdits(jsFile, function, target.page, key, importSource)
        }
        edits.sortedByDescending { it.first.startOffset }.forEach { (range, text) ->
            document.replaceString(range.startOffset, range.endOffset, text)
        }
        documents.commitDocument(document)
    }

    /**
     * Componente sin traducciones: `const { getLanguagePage } = useTranslateContext()` (si no existe),
     * `const { clave } = getLanguagePage('página')` y el import de `useTranslateContext`.
     */
    private fun setupEdits(jsFile: PsiFile, function: JSFunction, page: String, key: String, importSource: String?): List<Pair<TextRange, String>> {
        val block = function.block ?: return emptyList()
        val text = jsFile.text
        val firstStatement = PsiTreeUtil.getChildOfType(block, JSStatement::class.java)
        val indent = firstStatement?.let { lineIndent(text, it.textRange.startOffset) }
            ?: (lineIndent(text, function.textRange.startOffset) + if ("\n\t" in text) "\t" else "    ")
        val pageCall = "const { $key } = getLanguagePage('$page')"
        val edits = mutableListOf<Pair<TextRange, String>>()

        // ¿Ya hay `const { … } = useTranslateContext()` en el componente?
        val context = PsiTreeUtil.findChildrenOfType(function, JSDestructuringElement::class.java).firstOrNull {
            ((it.initializer as? JSCallExpression)?.methodExpression as? JSReferenceExpression)?.referenceName == "useTranslateContext"
        }
        if (context != null) {
            val contextObj = context.target as? JSDestructuringObject
            if (contextObj != null && contextObj.properties.none { it.name == "getLanguagePage" }) {
                val last = contextObj.properties.lastOrNull()
                edits += if (last != null) TextRange.from(last.textRange.endOffset, 0) to ", getLanguagePage"
                else TextRange.from(contextObj.textRange.startOffset + 1, 0) to " getLanguagePage "
            }
            val statement = PsiTreeUtil.getParentOfType(context, JSStatement::class.java) ?: return edits
            edits += TextRange.from(statement.textRange.endOffset, 0) to "\n$indent$pageCall"
        } else {
            val brace = block.firstChild.textRange.endOffset
            edits += TextRange.from(brace, 0) to "\n${indent}const { getLanguagePage } = useTranslateContext()\n$indent$pageCall\n"
        }

        if (importSource != null && !Regex("import\\s*\\{[^}]*\\buseTranslateContext\\b").containsMatchIn(text)) {
            val lastImport = PsiTreeUtil.findChildrenOfType(jsFile, ES6ImportDeclaration::class.java).lastOrNull()
            val offset = lastImport?.textRange?.endOffset ?: 0
            val line = "import { useTranslateContext } from '$importSource'"
            edits += TextRange.from(offset, 0) to if (lastImport != null) "\n$line" else "$line\n"
        }
        return edits
    }

    private fun lineIndent(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }
}

/** Alt+Enter sobre un texto fijo del JSX: "Extraer a traducciones". */
class ExtractTextToTranslationIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName() = "Extraer a traducciones"

    override fun getText() = familyName

    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) = TextExtraction.isAvailable(element)

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) = TextExtraction.perform(project, element)

    // Pide la clave y edita los JSON de traducción: sin vista previa
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile) = IntentionPreviewInfo.EMPTY
}

/**
 * Marca los textos fijos del JSX en componentes que ya usan `getLanguagePage` (desactivada por defecto:
 * actívala en Settings → Editor → Inspections → React Props Doc para revisar un archivo o el proyecto).
 */
class HardcodedTextInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            // Solo una vez por tramo de texto (su primer XmlText) o por atributo
            val hardcoded = when (element) {
                is XmlText -> TextExtraction.find(element)?.takeIf { it.anchor == element }
                is XmlAttribute -> TextExtraction.find(element)?.takeIf { it.isAttribute }
                else -> null
            } ?: return
            if (!TextExtraction.isAvailable(element)) return
            val range = hardcoded.range.shiftLeft(element.textRange.startOffset)
                .intersection(TextRange(0, element.textLength)) ?: return
            holder.registerProblem(
                element,
                "Texto fijo sin traducir: \"${hardcoded.text}\"",
                ProblemHighlightType.WEAK_WARNING,
                if (hardcoded.isAttribute) range else TextRange(0, element.textLength),
                ExtractTextFix(),
            )
        }
    }
}

class ExtractTextFix : LocalQuickFix {
    override fun getFamilyName() = "Extraer a traducciones"
    override fun startInWriteAction() = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) = TextExtraction.perform(project, descriptor.psiElement)
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY
}
