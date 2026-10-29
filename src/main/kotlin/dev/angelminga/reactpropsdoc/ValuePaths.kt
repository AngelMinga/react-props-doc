package dev.angelminga.reactpropsdoc

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSParameterListElement
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Sigue variables, desestructuraciones y accesos `a.b` hasta una llamada de origen y devuelve la ruta del valor.
 * `root` decide qué llamadas son origen y su ruta inicial: `getLanguagePage('p')` → `["p"]`,
 * `useSelector(state => state.catalog)` → `["catalog"]`.
 *
 * `const { summary } = getLanguagePage('p')`, `const { buttons } = summary`, `buttons.cancel`
 * → `["p", "summary", "buttons", "cancel"]`.
 */
class ValuePaths(private val root: (JSCallExpression) -> List<String>?) {

    fun expressionPath(expr: JSExpression?, depth: Int = 0): List<String>? {
        if (depth > MAX_CHAIN) return null
        return when (val e = Translations.unwrapFallback(expr)) {
            is JSCallExpression -> root(e)
            is JSReferenceExpression -> {
                val qualifier = e.qualifier
                if (qualifier != null) {
                    val name = e.referenceName ?: return null
                    expressionPath(qualifier, depth + 1)?.plus(name)
                } else {
                    (e.resolve() as? JSVariable)?.let { variablePath(it, depth + 1) }
                }
            }
            else -> null
        }
    }

    /**
     * Ruta de una variable: por su inicializador (`const t = origen()`, `const x = t.a`) o por la
     * desestructuración que la declara, incluidas las anidadas (`const { conditions: { optionLabels } } = …`).
     */
    fun variablePath(variable: JSVariable, depth: Int = 0): List<String>? {
        if (depth > MAX_CHAIN) return null
        if (variable.parent !is JSDestructuringProperty) {
            return variable.initializer?.let { expressionPath(it, depth + 1) }
        }
        val keys = ArrayDeque<String>()
        var current: PsiElement = variable
        while (true) {
            val property = current.parent as? JSDestructuringProperty ?: return null
            keys.addFirst(property.name ?: return null)
            val obj = property.parent as? JSDestructuringObject ?: return null
            when (val owner = obj.parent) {
                is JSDestructuringProperty -> current = obj
                is JSDestructuringElement -> {
                    if (owner is JSParameterListElement) return null
                    // Anidado (`conditions: { optionLabels }`): el objeto va envuelto en un elemento sin inicializador
                    if (owner.parent is JSDestructuringProperty) {
                        current = owner
                        continue
                    }
                    return expressionPath(owner.initializer, depth + 1)?.plus(keys)
                }
                else -> return null
            }
        }
    }

    /** Ruta de una variable, una propiedad desestructurada o un acceso `a.b`. */
    fun pathOfElement(element: PsiElement?): List<String>? = when (element) {
        is JSVariable -> variablePath(element)
        is JSDestructuringProperty -> (element.destructuringElement as? JSVariable)?.let { variablePath(it) }
            ?: PsiTreeUtil.findChildOfType(element, JSVariable::class.java)?.let { variablePath(it) }
        is JSReferenceExpression -> expressionPath(element)
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_CHAIN = 10
    }
}
