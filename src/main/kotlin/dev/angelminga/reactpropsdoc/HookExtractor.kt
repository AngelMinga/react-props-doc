package dev.angelminga.reactpropsdoc

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringArray
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringParameter
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSXmlLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.angelminga.reactpropsdoc.ReactPropsExtractor.compact

data class HookValue(val name: String, val kind: String? = null)

data class HookInfo(
    val hook: ReactComponent,
    val params: List<HookValue>,
    /** Valores devueltos: claves del objeto (`return { a, b }`) o elementos del arreglo (`return [a, b]`). */
    val returns: List<HookValue>,
    val returnsArray: Boolean,
    /** true si algún `return` no se pudo analizar (la lista puede estar incompleta). */
    val partial: Boolean,
)

object HookExtractor {

    private val HOOK_NAME = Regex("^use[A-Z0-9].*")

    fun findHook(element: PsiElement?): HookInfo? {
        val hook = ReactPropsExtractor.findDeclaration(element) ?: return null
        if (!HOOK_NAME.matches(hook.name)) return null
        val function = hook.function ?: return null

        var partial = false
        var returnsArray = false
        val returns = LinkedHashMap<String, HookValue>()
        returnExpressions(function).forEach { expr ->
            when (val value = resolveValue(expr)) {
                is JSObjectLiteralExpression -> {
                    if (!collectObject(value, returns, mutableSetOf())) partial = true
                    // `const result = {}; result.a = x; return result`: también las asignaciones por partes
                    returnedVariable(expr)?.let { variable ->
                        assignedKeys(function, variable).forEach { (name, assigned) ->
                            returns.putIfAbsent(name, HookValue(name, kindOf(assigned)))
                        }
                    }
                }
                is JSArrayLiteralExpression -> {
                    returnsArray = true
                    value.expressions.forEach { e ->
                        val name = e.text.compact()
                        returns.putIfAbsent(name, HookValue(name, kindOf(e)))
                    }
                }
                else -> partial = true
            }
        }
        if (returns.isEmpty() && partial) return null
        return HookInfo(hook, params(function), returns.values.toList(), returnsArray, partial)
    }

    /**
     * Claves de un objeto literal, incluidas las de `...otro` si `otro` es un objeto literal conocido.
     * Devuelve false si algún spread no se puede resolver (la lista queda incompleta).
     */
    private fun collectObject(obj: JSObjectLiteralExpression, into: MutableMap<String, HookValue>, visited: MutableSet<PsiElement>): Boolean {
        if (!visited.add(obj)) return true
        var complete = true
        obj.propertiesIncludingSpreads.forEach { element ->
            when (element) {
                is JSProperty -> element.name?.let { into.putIfAbsent(it, HookValue(it, kindOf(element.value))) }
                is JSSpreadExpression -> {
                    val spread = resolveValue(element.expression) as? JSObjectLiteralExpression
                    if (spread == null || !collectObject(spread, into, visited)) complete = false
                }
            }
        }
        return complete
    }

    /** `return result` → la variable local `result`. */
    private fun returnedVariable(expr: JSExpression): JSVariable? {
        val ref = (if (expr is JSParenthesizedExpression) expr.innerExpression else expr) as? JSReferenceExpression ?: return null
        return ref.resolve() as? JSVariable
    }

    /** `result.clave = valor` dentro del hook. */
    private fun assignedKeys(function: JSFunction, variable: JSVariable): List<Pair<String, JSExpression?>> {
        val name = variable.name ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(function, JSAssignmentExpression::class.java).mapNotNull { assignment ->
            val target = assignment.lOperand?.text?.replace(Regex("\\s+"), "") ?: return@mapNotNull null
            val key = Regex("^${Regex.escape(name)}\\.(\\w+)$").find(target)?.groupValues?.get(1) ?: return@mapNotNull null
            key to assignment.rOperand
        }
    }

    /** Expresiones de los `return` propios del hook (no de funciones internas). */
    private fun returnExpressions(function: JSFunction): List<JSExpression> {
        if (function.block == null) {
            // Arrow con cuerpo de expresión: `() => ({ ... })`
            return listOfNotNull(function.children.filterIsInstance<JSExpression>().lastOrNull())
        }
        return PsiTreeUtil.findChildrenOfType(function, JSReturnStatement::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == function }
            .mapNotNull { it.expression }
    }

    /** Quita paréntesis y sigue `return result` hasta `const result = { ... }`. */
    private fun resolveValue(expr: JSExpression?): JSExpression? {
        var current = expr
        repeat(4) {
            current = when (val e = current) {
                is JSParenthesizedExpression -> e.innerExpression
                is JSReferenceExpression -> (e.resolve() as? JSVariable)?.initializer ?: return e
                else -> return e
            }
        }
        return current
    }

    private fun params(function: JSFunction): List<HookValue> =
        function.parameters.flatMap { param ->
            when (param) {
                is JSDestructuringParameter ->
                    (param.target as? JSDestructuringObject)?.properties.orEmpty().mapNotNull { p ->
                        p.name?.let { HookValue(it, p.destructuringElement?.initializer?.text?.compact()?.let { d -> "= $d" }) }
                    }
                is JSParameter -> listOfNotNull(param.name?.let { HookValue(it, param.initializer?.text?.compact()?.let { d -> "= $d" }) })
                else -> emptyList()
            }
        }

    /** Tipo aproximado del valor devuelto: función, estado, JSX, objeto, arreglo o literal. */
    private fun kindOf(expr: JSExpression?): String? {
        return when (val value = expr?.let { if (it is JSParenthesizedExpression) it.innerExpression else it }) {
            is JSFunction -> "función"
            is JSXmlLiteralExpression -> "JSX"
            is JSObjectLiteralExpression -> "objeto"
            is JSArrayLiteralExpression -> "arreglo"
            is JSLiteralExpression -> value.text.compact().take(30)
            is JSCallExpression -> callKind(value)
            is JSReferenceExpression -> when (val resolved = value.resolve()) {
                is JSVariable -> variableKind(resolved)
                is JSFunction -> "función"
                else -> null
            }
            else -> null
        }
    }

    private fun variableKind(variable: JSVariable): String? {
        // `const [value, setValue] = useState()` → estado / función
        val destructuring = PsiTreeUtil.getParentOfType(variable, JSDestructuringElement::class.java)
        if (destructuring != null && destructuring.target is JSDestructuringArray) {
            val call = destructuring.initializer as? JSCallExpression
            val callee = (call?.methodExpression as? JSReferenceExpression)?.referenceName
            if (callee == "useState" || callee == "useReducer") {
                val index = (destructuring.target as JSDestructuringArray).elements.indexOfFirst { it == variable }
                return if (index == 0) "estado" else "función"
            }
        }
        return variable.initializer?.let { if (it is JSReferenceExpression) null else kindOf(it) }
    }

    private fun callKind(call: JSCallExpression): String? =
        when ((call.methodExpression as? JSReferenceExpression)?.referenceName) {
            "useCallback" -> "función"
            "useMemo" -> "valor memorizado"
            "useRef" -> "ref"
            else -> null
        }
}
