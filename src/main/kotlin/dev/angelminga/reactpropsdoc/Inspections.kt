package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSDestructuringShorthandedProperty
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/** `const { x } = useAlgo()` donde el hook no devuelve `x` (llega como `undefined`). */
class HookDestructuringInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val destructuring = element as? JSDestructuringElement ?: return
            val obj = destructuring.target as? JSDestructuringObject ?: return
            val call = Translations.unwrapFallback(destructuring.initializer) as? JSCallExpression ?: return
            val callee = call.methodExpression as? JSReferenceExpression ?: return
            if (callee.referenceName?.startsWith("use") != true) return

            val hook = HookExtractor.findHook(callee.resolve()) ?: return
            // Solo si se conoce todo lo que devuelve como objeto
            if (hook.partial || hook.returnsArray || hook.returns.isEmpty()) return
            val returned = hook.returns.map { it.name }.toSet()

            val destructured = obj.properties.mapNotNull { it.name }.toSet()
            obj.properties.forEach { property ->
                if (property.text.startsWith("...")) return@forEach
                val name = property.name ?: return@forEach
                if (name !in returned) {
                    // Opciones: lo que devuelve el hook y aún no se desestructura, de más a menos parecido
                    val fixes = (returned - destructured)
                        .sortedBy { levenshtein(it.lowercase(), name.lowercase()) }
                        .take(MAX_SUGGESTIONS)
                        .mapIndexed { index, candidate -> ReplaceHookValueFix(candidate, index) }
                    holder.registerProblem(
                        property,
                        "${hook.hook.name}() no devuelve '$name'. Devuelve: ${returned.joinToString(", ")}",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        *fixes.toTypedArray(),
                    )
                }
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 4

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        previous = current
    }
    return previous[b.length]
}

/**
 * Cambia el valor desestructurado por uno que el hook sí devuelve.
 * `{ viejo }` → `{ nuevo }` y renombra los usos de la variable; `{ viejo: local }` → `{ nuevo: local }`.
 */
class ReplaceHookValueFix(private val newName: String, private val rank: Int) : LocalQuickFix, PriorityAction {

    override fun getFamilyName() = "Cambiar por un valor que devuelve el hook"

    // Alt+Enter ordena por prioridad y luego alfabéticamente: la opción más parecida va primero
    override fun getPriority() = if (rank == 0) PriorityAction.Priority.HIGH else PriorityAction.Priority.NORMAL

    override fun getName() = "Cambiar por '$newName'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val property = descriptor.psiElement as? JSDestructuringProperty ?: return
        val file = property.containingFile
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return

        val ranges = if (property is JSDestructuringShorthandedProperty) {
            val variable = property.destructuringElement ?: return
            val declaration = variable.nameIdentifier?.textRange ?: return
            val usages = ReferencesSearch.search(variable, LocalSearchScope(file)).findAll()
                .map { it.element.textRange.cutOut(it.rangeInElement) }
            listOf(declaration) + usages
        } else {
            // `{ clave: local }`: la clave va al inicio de la propiedad
            val name = property.name ?: return
            if (!property.text.startsWith(name)) return
            listOf(TextRange.from(property.textRange.startOffset, name.length))
        }
        ranges.distinct().sortedByDescending { it.startOffset }
            .forEach { document.replaceString(it.startOffset, it.endOffset, newName) }
        documents.commitDocument(document)
    }
}

/** Páginas y claves de `getLanguagePage('página')` que no existen o faltan en algún idioma. */
class TranslationKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            when (element) {
                is JSLiteralExpression -> checkPage(element)
                is JSDestructuringElement -> checkKeys(element)
            }
        }

        private fun checkPage(literal: JSLiteralExpression) {
            if (!literal.isQuotedLiteral || !Translations.isPageLiteral(literal)) return
            val files = Translations.files(literal.project) ?: return
            val page = literal.stringValue ?: return
            if (files.root(files.baseLang)?.findProperty(page) == null) {
                holder.registerProblem(literal, "La página '$page' no existe en ${files.base.name}")
                return
            }
            // Idiomas donde falta la página entera, o algunas de sus claves (la "página" puede ser un texto suelto)
            val basePage = files.page(files.baseLang, page)
            val missingPage = files.missingIn(listOf(page))
            val incomplete = if (basePage == null) emptyList() else files.others.keys.filter { lang ->
                lang !in missingPage && Translations.missingPaths(basePage, files.page(lang, page) ?: return@filter false).isNotEmpty()
            }
            if (missingPage.isNotEmpty()) {
                holder.registerProblem(
                    literal,
                    "La página '$page' falta en ${missingPage.joinToString(", ")}",
                    ProblemHighlightType.WEAK_WARNING,
                    CopyFromBaseLanguageFix(missingPage, null, listOf(page)),
                )
            }
            if (incomplete.isNotEmpty()) {
                holder.registerProblem(
                    literal,
                    "A la página '$page' le faltan claves en ${incomplete.joinToString(", ")}",
                    ProblemHighlightType.WEAK_WARNING,
                    CopyFromBaseLanguageFix(incomplete, page, null),
                )
            }
        }

        private fun checkKeys(destructuring: JSDestructuringElement) {
            val page = Translations.pageOf(Translations.translationCall(destructuring.initializer)) ?: return
            val obj = destructuring.target as? JSDestructuringObject ?: return
            val files = Translations.files(destructuring.project) ?: return
            val basePage = files.page(files.baseLang, page) ?: return

            obj.properties.forEach { property ->
                if (property.text.startsWith("...")) return@forEach
                val key = property.name ?: return@forEach
                if (basePage.findProperty(key) == null) {
                    holder.registerProblem(property, "'$key' no existe en la página '$page' de ${files.base.name}")
                    return@forEach
                }
                val missing = files.missingIn(listOf(page, key))
                if (missing.isNotEmpty()) {
                    holder.registerProblem(
                        property,
                        "'$key' de '$page' falta en ${missing.joinToString(", ")}",
                        ProblemHighlightType.WEAK_WARNING,
                        CopyFromBaseLanguageFix(missing, page, listOf(key)),
                    )
                }
            }
        }
    }
}

/** En `en.json`, `ki.json`…: páginas y claves que existen en el idioma base y aquí faltan. */
class TranslationFileInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? JsonFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val files = Translations.files(file.project) ?: return PsiElementVisitor.EMPTY_VISITOR
        val lang = files.others.entries.firstOrNull { it.value == file }?.key ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitFile(psiFile: com.intellij.psi.PsiFile) {
                val root = files.root(lang) ?: return
                val baseRoot = files.root(files.baseLang) ?: return
                val missingPages = baseRoot.propertyList.map { it.name }.filter { root.findProperty(it) == null }
                if (missingPages.isNotEmpty()) {
                    holder.registerProblem(
                        root.firstChild,
                        "Faltan páginas de ${files.base.name}: ${missingPages.joinToString(", ")}",
                        CopyFromBaseLanguageFix(listOf(lang), null, missingPages),
                    )
                }
                // Textos copiados con la corrección rápida y aún sin traducir: "[ES] …"
                val marker = "[${files.baseLang}] "
                PsiTreeUtil.findChildrenOfType(root, JsonStringLiteral::class.java)
                    .filter { !JsonPsiUtil.isPropertyKey(it) && it.value.startsWith(marker) }
                    .forEach {
                        holder.registerProblem(it, "Texto sin traducir (copiado de ${files.base.name})", ProblemHighlightType.WEAK_WARNING)
                    }
                root.propertyList.forEach { property ->
                    val basePage = baseRoot.findProperty(property.name)?.value as? JsonObject ?: return@forEach
                    val page = property.value as? JsonObject ?: return@forEach
                    val missing = Translations.missingPaths(basePage, page)
                    if (missing.isNotEmpty()) {
                        holder.registerProblem(
                            property.nameElement,
                            "Faltan ${missing.size} claves de ${files.base.name}: " +
                                missing.take(6).joinToString(", ") + if (missing.size > 6) ", …" else "",
                            CopyFromBaseLanguageFix(listOf(lang), property.name, null),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copia desde el idioma base (con su texto, para traducir después) lo que falta en los idiomas indicados.
 * `page` = null → nivel de páginas; `keys` = null → todo lo que falte dentro de la página.
 */
class CopyFromBaseLanguageFix(
    private val langs: List<String>,
    private val page: String?,
    private val keys: List<String>?,
) : LocalQuickFix {

    override fun getFamilyName() = "Copiar desde el idioma base"

    override fun getName(): String {
        val what = when {
            page == null && keys?.size == 1 -> "la página '${keys.single()}'"
            page == null -> "${keys.orEmpty().size} páginas"
            keys == null -> "las claves que faltan de '$page'"
            else -> "'${keys.joinToString(", ")}'"
        }
        return "Agregar $what a ${langs.joinToString(", ") { it.lowercase() + ".json" }} (copiando el texto base)"
    }

    override fun startInWriteAction() = true

    // Modifica otros archivos (en.json, ki.json…): la vista previa no aplica
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val files = Translations.files(project) ?: return
        val prefix = Translations.copyPrefix(files)
        langs.forEach { lang ->
            val baseRoot = files.root(files.baseLang) ?: return@forEach
            val root = files.root(lang) ?: return@forEach
            if (page == null) {
                Translations.copyMissing(baseRoot, root, keys, prefix)
                return@forEach
            }
            val basePage = files.page(files.baseLang, page) ?: return@forEach
            val targetPage = files.page(lang, page)
            if (targetPage == null) Translations.copyMissing(baseRoot, root, listOf(page), prefix)
            else Translations.copyMissing(basePage, targetPage, keys, prefix)
        }
    }
}
