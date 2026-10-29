package dev.angelminga.reactpropsdoc

import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/** Una parte del estado de Redux: `catalog` en `combineReducers({ catalog })`. */
data class ReduxSlice(
    val name: String,
    /** La propiedad en `combineReducers({ … })`. */
    val key: PsiElement,
    val reducer: JSFunction?,
    val initialState: JSExpression?,
)

object ReduxState {

    private val SELECTOR_HOOKS = setOf("useSelector", "useAppSelector")

    /** Partes del estado de todos los `combineReducers` / `configureStore({ reducer: { … } })` del proyecto. */
    fun slices(project: Project): Map<String, ReduxSlice> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(findSlices(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun findSlices(project: Project): Map<String, ReduxSlice> {
        val files = mutableSetOf<PsiFile>()
        val scope = GlobalSearchScope.projectScope(project)
        listOf("combineReducers", "configureStore").forEach { word ->
            PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, scope, { files += it; true }, true)
        }
        val result = linkedMapOf<String, ReduxSlice>()
        files.filter { "/node_modules/" !in (it.virtualFile?.path ?: "") }.forEach { file ->
            PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java).forEach { call ->
                val reducers = when ((call.methodExpression as? JSReferenceExpression)?.referenceName) {
                    "combineReducers" -> call.argumentList?.arguments?.firstOrNull() as? JSObjectLiteralExpression
                    "configureStore" -> ((call.argumentList?.arguments?.firstOrNull() as? JSObjectLiteralExpression)
                        ?.findProperty("reducer")?.value as? JSObjectLiteralExpression)
                    else -> null
                }
                reducers?.properties?.forEach { property ->
                    val name = property.name ?: return@forEach
                    // Cada micro-frontend puede tener su propio store: se queda la versión con estado inicial conocido
                    val existing = result[name]
                    if (existing == null || existing.initialState == null) result[name] = slice(name, property)
                }
            }
        }
        return result
    }

    private fun slice(name: String, property: JSProperty): ReduxSlice {
        // Con varios jsconfig.json, un alias federado puede tener varios candidatos: se toma el primero válido
        val target = (property.value as? JSReferenceExpression)?.let { ref ->
            ref.resolve() ?: ref.multiResolve(false).firstOrNull { it.isValidResult }?.element
                ?: ref.multiResolve(false).firstNotNullOfOrNull { it.element }
        }
        val declaration = ReactPropsExtractor.findDeclaration(target)
        val reducer = declaration?.function
        // `(state = initialState, action) => …`
        val fromDefault = (reducer?.parameters?.firstOrNull() as? JSParameter)?.initializer
        // `const xSlice = createSlice({ initialState })` y `export default xSlice.reducer`
        val fromSlice = (declaration?.declaration as? JSVariable)?.initializer?.let(::sliceInitialState)
            ?: (target as? JSVariable)?.initializer?.let(::sliceInitialState)
            ?: exportedExpression(target)?.let(::sliceInitialState)
        val initialState = resolveValue(fromDefault ?: fromSlice)
        if (initialState != null) return ReduxSlice(name, property, reducer, initialState)

        // El import no se resolvió (p. ej. alias federado sin mapeo en jsconfig.json): se busca el reducer por nombre
        val localName = (property.value as? JSReferenceExpression)?.referenceName ?: name
        val byName = findReducerByName(property.project, localName)
        return ReduxSlice(name, property, byName ?: reducer, resolveValue((byName?.parameters?.firstOrNull() as? JSParameter)?.initializer))
    }

    /** `const nombre = (state = initialState, action) => …` (o `function nombre(state = …)`) en el proyecto. */
    private fun findReducerByName(project: Project, name: String): JSFunction? {
        var found: JSFunction? = null
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(name, GlobalSearchScope.projectScope(project), { file ->
            if ("/node_modules/" in (file.virtualFile?.path ?: "")) return@processAllFilesWithWord true
            found = PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java).firstOrNull { function ->
                val functionName = function.name ?: (function.parent as? JSVariable)?.name
                functionName == name && (function.parameters.firstOrNull() as? JSParameter)?.initializer != null &&
                    PsiTreeUtil.getParentOfType(function, JSFunction::class.java) == null
            }
            found == null
        }, true)
        return found
    }

    /** `import x from './x'` → la expresión de `export default …` de ese archivo. */
    private fun exportedExpression(target: PsiElement?): JSExpression? {
        val exported = when (target) {
            is ES6ImportedBinding -> target.findReferencedElements().firstOrNull()
            else -> target
        }
        return PsiTreeUtil.getChildOfType(exported as? ES6ExportDefaultAssignment ?: return null, JSExpression::class.java)
    }

    private fun sliceInitialState(expr: JSExpression): JSExpression? {
        val qualifier = (expr as? JSReferenceExpression)?.takeIf { it.referenceName == "reducer" }?.qualifier as? JSReferenceExpression
        val call = (qualifier?.resolve() as? JSVariable)?.initializer as? JSCallExpression ?: expr as? JSCallExpression ?: return null
        if ((call.methodExpression as? JSReferenceExpression)?.referenceName != "createSlice") return null
        val options = call.argumentList?.arguments?.firstOrNull() as? JSObjectLiteralExpression ?: return null
        return options.findProperty("initialState")?.value
    }

    /** Sigue `initialState` (referencia) hasta su valor. */
    fun resolveValue(expr: JSExpression?): JSExpression? {
        var current = expr
        repeat(4) {
            current = when (val e = current) {
                is JSReferenceExpression -> (e.resolve() as? JSVariable)?.initializer ?: return e
                else -> return e
            }
        }
        return current
    }

    /**
     * `useSelector(state => state.catalog.data)`: ruta `["catalog", "data"]` de la expresión (sin la raíz `state`),
     * o null si no está dentro de un selector.
     */
    fun selectorPath(ref: JSReferenceExpression): List<String>? {
        val path = mutableListOf<String>()
        var current: JSReferenceExpression = ref
        while (true) {
            val qualifier = current.qualifier
            if (qualifier == null) break
            path += current.referenceName ?: return null
            current = qualifier as? JSReferenceExpression ?: return null
        }
        val rootName = current.referenceName ?: return null
        val function = PsiTreeUtil.getParentOfType(ref, JSFunction::class.java) ?: return null
        val param = function.parameters.firstOrNull() as? JSParameter ?: return null
        if (param.name != rootName || !isSelector(function)) return null
        return path.reversed()
    }

    /**
     * Función selectora: argumento de `useSelector(…)` o declarada aparte con nombre de selector
     * (`const selectCatalog = state => state.catalog`, `function catalogSelector(state)`).
     */
    fun isSelector(function: JSFunction): Boolean {
        val call = (function.parent as? JSArgumentList)?.parent as? JSCallExpression
        if ((call?.methodExpression as? JSReferenceExpression)?.referenceName in SELECTOR_HOOKS) return true
        val name = function.name ?: (function.parent as? JSVariable)?.name ?: return false
        return SELECTOR_NAME.matches(name)
    }

    private val SELECTOR_NAME = Regex("^select[A-Z0-9].*|.*Selector$")

    /** Ruta que devuelve una función selectora: `state => state.catalog.data` → `["catalog", "data"]`. */
    private fun selectorResultPath(function: JSFunction): List<String>? {
        val paramName = (function.parameters.firstOrNull() as? JSParameter)?.name ?: return null
        val body = if (function.block == null) function.children.filterIsInstance<JSExpression>().lastOrNull()
        else PsiTreeUtil.findChildrenOfType(function, JSReturnStatement::class.java)
            .firstOrNull { PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == function }?.expression
        val path = mutableListOf<String>()
        var current = Translations.unwrapFallback(body) as? JSReferenceExpression ?: return null
        while (true) {
            val qualifier = current.qualifier ?: break
            path += current.referenceName ?: return null
            current = qualifier as? JSReferenceExpression ?: return null
        }
        return if (current.referenceName == paramName) path.reversed() else null
    }

    /** `useSelector(state => state.catalog)` o `useSelector(selectCatalog)` → `["catalog"]`. */
    fun selectorCallPath(call: JSCallExpression): List<String>? {
        if ((call.methodExpression as? JSReferenceExpression)?.referenceName !in SELECTOR_HOOKS) return null
        val function = when (val arg = call.argumentList?.arguments?.firstOrNull()) {
            is JSFunction -> arg
            is JSReferenceExpression -> ReactPropsExtractor.findDeclaration(arg.resolve())?.function
            else -> null
        } ?: return null
        return selectorResultPath(function)?.takeIf { it.isNotEmpty() }
    }

    private val reduxPaths = ValuePaths(::selectorCallPath)

    /**
     * Ruta en el estado de un valor que viene de `useSelector` (fuera del selector):
     * `const catalogState = useSelector(state => state.catalog)` y `catalogState.data.entity` → `["catalog", "data", "entity"]`.
     */
    fun valuePath(expr: JSExpression?): List<String>? = reduxPaths.expressionPath(expr)

    fun pathOfElement(element: PsiElement?): List<String>? = reduxPaths.pathOfElement(element)

    /** Valor en `path` (sin el nombre de la parte) dentro del estado inicial. */
    fun valueAt(slice: ReduxSlice, path: List<String>): JSExpression? {
        var current = slice.initialState
        for (name in path) {
            val obj = resolveValue(current) as? JSObjectLiteralExpression ?: return null
            current = obj.findProperty(name)?.value ?: return null
        }
        return resolveValue(current)
    }

    fun propertyAt(slice: ReduxSlice, path: List<String>): JSProperty? {
        if (path.isEmpty()) return null
        val parent = valueAt(slice, path.dropLast(1)) as? JSObjectLiteralExpression ?: return null
        return parent.findProperty(path.last())
    }

    /** Acciones que maneja el reducer (`case fetchDataCatalog:`). */
    fun actions(slice: ReduxSlice): List<String> {
        val text = slice.reducer?.text ?: return emptyList()
        return Regex("case\\s+([\\w.$'\"]+)\\s*:").findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    fun preview(value: JSExpression?, max: Int = 60): String =
        when (val v = resolveValue(value)) {
            null -> "?"
            is JSObjectLiteralExpression -> if (v.properties.isEmpty()) "{}"
            else "{ " + v.properties.mapNotNull { it.name }.take(8).joinToString(", ") + (if (v.properties.size > 8) ", …" else "") + " }"
            else -> v.text.replace(Regex("\\s+"), " ").let { if (it.length > max) it.take(max) + "…" else it }
        }
}
