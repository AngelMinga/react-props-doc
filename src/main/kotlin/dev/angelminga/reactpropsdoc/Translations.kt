package dev.angelminga.reactpropsdoc

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSParameterListElement
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * Archivos de traducción del proyecto: el de idioma base (por defecto `es.json`) y sus hermanos
 * en la misma carpeta (`en.json`, `ki.json`…). Cada archivo es `{ página: { clave: texto | {…} } }`.
 */
data class TranslationFiles(val baseLang: String, val files: Map<String, JsonFile>) {
    val base: JsonFile get() = files.getValue(baseLang)
    val others: Map<String, JsonFile> get() = files.filterKeys { it != baseLang }

    fun root(lang: String): JsonObject? = files[lang]?.topLevelValue as? JsonObject
    fun page(lang: String, page: String): JsonObject? = root(lang)?.findProperty(page)?.value as? JsonObject
    fun pages(): List<JsonProperty> = root(baseLang)?.propertyList.orEmpty()

    fun valueAt(lang: String, path: List<String>): JsonValue? {
        var current: JsonValue? = files[lang]?.topLevelValue
        for (name in path) current = (current as? JsonObject)?.findProperty(name)?.value ?: return null
        return current
    }

    /** La propiedad del JSON en `path` (`["página", "summary", "buttons", "cancel"]`), si existe. */
    fun propertyAt(lang: String, path: List<String>): JsonProperty? {
        if (path.isEmpty()) return null
        val parent = if (path.size == 1) root(lang) else valueAt(lang, path.dropLast(1)) as? JsonObject
        return parent?.findProperty(path.last())
    }

    /** Idiomas (distintos al base) en los que falta la ruta. */
    fun missingIn(path: List<String>): List<String> = others.keys.filter { valueAt(it, path) == null }
}

/** Un uso de `getLanguagePage('página')` y, si aplica, la clave desestructurada. */
data class TranslationUsage(val page: String, val key: String?, val call: JSCallExpression)

object Translations {

    private val options get() = ReactPropsDocSettings.options

    fun files(project: Project): TranslationFiles? =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(findFiles(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun findFiles(project: Project): TranslationFiles? {
        val baseLang = options.baseLanguage?.ifBlank { null }?.lowercase() ?: "es"
        val psiManager = PsiManager.getInstance(project)
        // El archivo base con más páginas (objetos de primer nivel), fuera de node_modules
        val base = FilenameIndex.getVirtualFilesByName("$baseLang.json", GlobalSearchScope.projectScope(project))
            .filter { "/node_modules/" !in it.path }
            .mapNotNull { psiManager.findFile(it) as? JsonFile }
            .maxByOrNull { file -> (file.topLevelValue as? JsonObject)?.propertyList?.count { it.value is JsonObject } ?: 0 }
            ?: return null
        val dir = base.containingDirectory ?: return null
        val files = linkedMapOf(baseLang.uppercase() to base)
        dir.files.filterIsInstance<JsonFile>()
            .filter { it != base && it.virtualFile.nameWithoutExtension.length in 2..3 && it.topLevelValue is JsonObject }
            .sortedBy { it.name }
            .forEach { files[it.virtualFile.nameWithoutExtension.uppercase()] = it }
        return TranslationFiles(baseLang.uppercase(), files)
    }

    /** `x || {}`, `x ?? {}` y `(x)` → `x`: el valor real de una desestructuración con respaldo. */
    fun unwrapFallback(expr: JSExpression?): JSExpression? {
        var current = expr
        while (true) {
            current = when (val e = current) {
                is JSParenthesizedExpression -> e.innerExpression
                is JSBinaryExpression -> if (e.operationNode?.text in setOf("||", "??")) e.lOperand else return e
                else -> return e
            }
        }
    }

    /** La llamada `getLanguagePage('…')` de un inicializador, aunque tenga `|| {}`. */
    fun translationCall(initializer: JSExpression?): JSCallExpression? =
        (unwrapFallback(initializer) as? JSCallExpression)?.takeIf { pageOf(it) != null }

    /** `getLanguagePage('página')` → "página" (solo con un literal de texto como primer argumento). */
    fun pageOf(call: JSCallExpression?): String? {
        val callee = call?.methodExpression as? JSReferenceExpression ?: return null
        if (callee.referenceName != options.translationFunction) return null
        val literal = call.argumentList?.arguments?.firstOrNull() as? JSLiteralExpression ?: return null
        return if (literal.isQuotedLiteral) literal.stringValue else null
    }

    /** El literal de página dentro de `getLanguagePage('…')`. */
    fun isPageLiteral(literal: JSLiteralExpression): Boolean {
        val args = literal.parent as? JSArgumentList ?: return false
        return args.arguments.firstOrNull() == literal && pageOf(args.parent as? JSCallExpression) != null
    }

    /** `const { title } = getLanguagePage('page')`: la desestructuración, si su inicializador es la llamada. */
    fun destructuringOf(element: PsiElement?): Pair<JSDestructuringObject, String>? {
        val destructuring = PsiTreeUtil.getParentOfType(element, JSDestructuringElement::class.java, false) ?: return null
        val page = pageOf(translationCall(destructuring.initializer)) ?: return null
        val obj = destructuring.target as? JSDestructuringObject ?: return null
        return obj to page
    }

    /** Uso de una clave: variable o propiedad dentro de `const { … } = getLanguagePage('page')`. */
    fun keyUsage(element: PsiElement?): TranslationUsage? {
        val property = element as? JSDestructuringProperty
            ?: PsiTreeUtil.getParentOfType(element, JSDestructuringProperty::class.java, false)
            ?: return null
        // Solo el primer nivel de la desestructuración
        val obj = property.parent as? JSDestructuringObject ?: return null
        val destructuring = obj.parent as? JSDestructuringElement ?: return null
        val call = translationCall(destructuring.initializer) ?: return null
        val page = pageOf(call) ?: return null
        return TranslationUsage(page, property.name ?: return null, call)
    }

    /** Rutas de traducción: el origen es la llamada `getLanguagePage('página')` → `["página"]`. */
    private val translationPaths = ValuePaths { call -> pageOf(call)?.let { listOf(it) } }

    /**
     * Ruta en las traducciones de una expresión: sigue variables y desestructuraciones hasta `getLanguagePage('p')`.
     * `buttons.cancel` con `const { buttons } = summary` y `const { summary } = getLanguagePage('investmentPage')`
     * → `["investmentPage", "summary", "buttons", "cancel"]`.
     */
    fun expressionPath(expr: JSExpression?): List<String>? = translationPaths.expressionPath(expr)

    fun variablePath(variable: JSVariable): List<String>? = translationPaths.variablePath(variable)

    /** Ruta en traducciones de una variable, una propiedad desestructurada o un acceso `a.b`. */
    fun pathOfElement(element: PsiElement?): List<String>? = translationPaths.pathOfElement(element)

    /** Ruta de una propiedad dentro de un archivo de traducción: `["page", "stepLabels", "FORM"]`. */
    fun pathOf(property: JsonProperty): Pair<String, List<String>>? {
        val files = files(property.project) ?: return null
        val lang = files.files.entries.firstOrNull { it.value == property.containingFile }?.key ?: return null
        val path = generateSequence(property) { PsiTreeUtil.getParentOfType(it, JsonProperty::class.java) }
            .map { it.name }.toList().reversed()
        return lang to path
    }

    /** Texto corto para mostrar un valor: el texto, `{ a, b, … }` o `[n elementos]`. */
    fun preview(value: JsonValue?, max: Int = 90): String? = when (value) {
        null -> null
        is JsonStringLiteral -> value.value.let { if (it.length > max) it.take(max) + "…" else it }
        is JsonObject -> value.propertyList.map { it.name }.let { names ->
            "{ " + names.take(8).joinToString(", ") + (if (names.size > 8) ", … (${names.size})" else "") + " }"
        }
        is JsonArray -> "[${value.valueList.size} elementos]"
        else -> value.text
    }

    /**
     * Copia desde el idioma base las propiedades que faltan en `target` (recursivo).
     * `only` limita a esas claves del primer nivel. Devuelve cuántas propiedades agregó.
     *
     * Se edita el documento como texto (no la PSI) para respetar la indentación del archivo: el IDE
     * reformatearía los elementos PSI nuevos con su propio estilo. El valor se copia tal cual del idioma
     * base, que está a la misma profundidad, así que su indentación interna coincide.
     */
    fun copyMissing(base: JsonObject, target: JsonObject, only: Collection<String>? = null, prefix: String = ""): Int {
        val pending = LinkedHashMap<JsonObject, MutableList<String>>()
        collectMissing(base, target, only, prefix, pending)
        if (pending.isEmpty()) return 0

        val file = target.containingFile
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return 0
        val unit = if ("\n\t" in file.text) "\t" else "  "
        // Se calculan todas las inserciones antes de editar y se aplican de atrás hacia adelante
        pending.map { (obj, entries) -> insertion(obj, entries, unit) }
            .sortedByDescending { it.first }
            .forEach { (offset, text) -> document.insertString(offset, text) }
        documents.commitDocument(document)
        return pending.values.sumOf { it.size }
    }

    /** Agrega `entries` (`"clave": valor`) al final de `obj`, respetando la indentación del archivo. */
    fun appendEntries(obj: JsonObject, entries: List<String>) {
        if (entries.isEmpty()) return
        val file = obj.containingFile
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return
        val unit = if ("\n\t" in file.text) "\t" else "  "
        val (offset, text) = insertion(obj, entries, unit)
        document.insertString(offset, text)
        documents.commitDocument(document)
    }

    private fun collectMissing(
        base: JsonObject,
        target: JsonObject,
        only: Collection<String>?,
        prefix: String,
        pending: MutableMap<JsonObject, MutableList<String>>,
    ) {
        base.propertyList.filter { only == null || it.name in only }.forEach { baseProp ->
            val existing = target.findProperty(baseProp.name)
            val baseValue = baseProp.value ?: return@forEach
            when {
                existing == null ->
                    pending.getOrPut(target) { mutableListOf() } += "${baseProp.nameElement.text}: ${withPrefix(baseValue, prefix)}"
                baseValue is JsonObject && existing.value is JsonObject ->
                    collectMissing(baseValue, existing.value as JsonObject, null, prefix, pending)
            }
        }
    }

    /** Texto del valor con `prefix` delante de cada texto (no de las claves): `"[ES] Continuar"`. */
    private fun withPrefix(value: JsonValue, prefix: String): String {
        if (prefix.isEmpty()) return value.text
        val start = value.textRange.startOffset
        val literals = (PsiTreeUtil.findChildrenOfType(value, JsonStringLiteral::class.java) + listOfNotNull(value as? JsonStringLiteral))
            .filter { !JsonPsiUtil.isPropertyKey(it) && !it.value.startsWith(prefix) }
            .sortedByDescending { it.textRange.startOffset }
        val text = StringBuilder(value.text)
        literals.forEach { text.insert(it.textRange.startOffset - start + 1, prefix) }
        return text.toString()
    }

    /** Prefijo para marcar textos copiados (`"[ES] "`), o "" si está desactivado. */
    fun copyPrefix(files: TranslationFiles): String =
        if (options.markCopiedTranslations) "[${files.baseLang}] " else ""

    /** (offset, texto) para agregar `entries` al final del objeto, con su separador e indentación. */
    private fun insertion(obj: JsonObject, entries: List<String>, unit: String): Pair<Int, String> {
        val last = obj.propertyList.lastOrNull()
        if (last == null) {
            val indent = lineIndent(obj) + unit
            return obj.firstChild.textRange.endOffset to
                entries.joinToString(",") { "\n$indent$it" } + "\n" + lineIndent(obj)
        }
        val before = (last.prevSibling as? PsiWhiteSpace)?.text.orEmpty()
        // Objeto en una sola línea (`{ "a": 1 }`): se mantiene en la misma línea
        val separator = if ('\n' in before) "\n" + before.substringAfterLast('\n') else " "
        return last.textRange.endOffset to entries.joinToString("") { ",$separator$it" }
    }

    private fun lineIndent(element: PsiElement): String {
        val text = element.containingFile.text
        val lineStart = text.lastIndexOf('\n', element.textRange.startOffset - 1) + 1
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }

    /** Claves en `base` que faltan en `target`, como rutas relativas (`a`, `b.c`). */
    fun missingPaths(base: JsonObject, target: JsonObject, prefix: String = ""): List<String> =
        base.propertyList.flatMap { baseProp ->
            val existing = target.findProperty(baseProp.name)
            val baseValue = baseProp.value
            when {
                existing == null -> listOf(prefix + baseProp.name)
                baseValue is JsonObject && existing.value is JsonObject ->
                    missingPaths(baseValue, existing.value as JsonObject, prefix + baseProp.name + ".")
                else -> emptyList()
            }
        }
}
