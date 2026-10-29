package dev.angelminga.reactpropsdoc

import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringParameter
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

data class ReactProp(
    val name: String,
    val type: String? = null,
    val required: Boolean = false,
    val defaultValue: String? = null,
)

data class ReactComponent(
    val name: String,
    val declaration: PsiElement,
    val function: JSFunction?,
    val file: PsiFile,
)

object ReactPropsExtractor {

    private const val MAX_DEPTH = 5
    private const val MAX_TYPE_LENGTH = 70

    /** Resuelve el elemento destino del hover a un componente React (nombre en PascalCase). */
    fun findComponent(element: PsiElement?, depth: Int = 0): ReactComponent? {
        if (element == null || depth > MAX_DEPTH) return null
        return when (element) {
            is ES6ImportedBinding ->
                element.findReferencedElements().firstNotNullOfOrNull { findComponent(it, depth + 1) }

            is JSVariable -> {
                val name = element.name ?: return null
                component(name, element, unwrapFunction(element.initializer, depth))
            }

            is JSFunction -> {
                val name = element.name ?: PsiTreeUtil.getParentOfType(element, JSVariable::class.java)?.name
                ?: return null
                component(name, element, element)
            }

            is JSClass -> component(element.name ?: return null, element, null)

            else -> null
        }
    }

    private fun component(name: String, declaration: PsiElement, function: JSFunction?): ReactComponent? {
        if (name.firstOrNull()?.isUpperCase() != true) return null
        val file = declaration.containingFile ?: return null
        return ReactComponent(name, declaration, function, file)
    }

    /** Soporta `props => ...`, `function (props)`, `memo(...)`, `forwardRef(...)` y referencias a otra variable. */
    private fun unwrapFunction(expr: JSExpression?, depth: Int): JSFunction? {
        if (expr == null || depth > MAX_DEPTH) return null
        return when (expr) {
            is JSFunction -> expr
            is JSCallExpression -> unwrapFunction(expr.argumentList?.arguments?.firstOrNull(), depth + 1)
            is JSReferenceExpression -> when (val resolved = expr.resolve()) {
                is JSFunction -> resolved
                is JSVariable -> unwrapFunction(resolved.initializer, depth + 1)
                else -> null
            }
            else -> null
        }
    }

    fun extractProps(component: ReactComponent): List<ReactProp> {
        val propTypes = objectAssignedTo(component, "propTypes")
        val defaults = objectAssignedTo(component, "defaultProps")
            ?.properties?.mapNotNull { p -> p.name?.let { it to p.value?.text?.compact() } }?.toMap()
            .orEmpty()
        val destructured = destructuredProps(component.function)

        val result = LinkedHashMap<String, ReactProp>()
        propTypes?.properties?.forEach { p ->
            val name = p.name ?: return@forEach
            val raw = p.value?.text?.compact() ?: ""
            result[name] = ReactProp(
                name = name,
                type = formatType(raw),
                required = raw.endsWith(".isRequired"),
                defaultValue = defaults[name] ?: destructured[name],
            )
        }
        destructured.forEach { (name, default) ->
            if (name !in result) result[name] = ReactProp(name = name, defaultValue = defaults[name] ?: default)
        }
        defaults.forEach { (name, default) ->
            if (name !in result) result[name] = ReactProp(name = name, defaultValue = default)
        }
        return result.values.toList()
    }

    /** Busca `Componente.<field> = { ... }` en el archivo del componente. */
    private fun objectAssignedTo(component: ReactComponent, field: String): JSObjectLiteralExpression? {
        val target = "${component.name}.$field"
        return PsiTreeUtil.findChildrenOfType(component.file, JSAssignmentExpression::class.java)
            .firstOrNull { it.lOperand?.text?.compact() == target }
            ?.rOperand as? JSObjectLiteralExpression
    }

    /** Props desestructuradas en la firma o en el cuerpo, y accesos `props.xxx`. Valor = default si lo tiene. */
    private fun destructuredProps(function: JSFunction?): Map<String, String?> {
        val param = function?.parameters?.firstOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String?>()

        fun collect(obj: JSDestructuringObject?) {
            obj?.properties?.forEach { p ->
                val name = p.name ?: return@forEach
                val default = p.destructuringElement?.initializer?.text?.compact()
                result.putIfAbsent(name, default)
            }
        }

        when (param) {
            is JSDestructuringParameter -> collect(param.target as? JSDestructuringObject)
            is JSParameter -> {
                val paramName = param.name ?: return emptyMap()
                PsiTreeUtil.findChildrenOfType(function, JSDestructuringElement::class.java)
                    .filter { it.initializer?.text == paramName }
                    .forEach { collect(it.target as? JSDestructuringObject) }
                PsiTreeUtil.findChildrenOfType(function, JSReferenceExpression::class.java)
                    .filter { (it.qualifier as? JSReferenceExpression)?.let { q -> q.qualifier == null && q.text == paramName } == true }
                    .forEach { ref -> ref.referenceName?.let { result.putIfAbsent(it, null) } }
            }
        }
        return result
    }

    /** true si el componente ya documenta sus props con JSDoc (`@param props.xxx`), para no duplicar. */
    fun hasJsDocProps(component: ReactComponent): Boolean {
        var statement: PsiElement = component.declaration
        while (statement.parent != null && statement.parent !is PsiFile) statement = statement.parent
        // El JSDoc puede quedar como primer hijo de la sentencia o justo antes de ella
        var prev = statement.prevSibling
        while (prev is PsiWhiteSpace) prev = prev.prevSibling
        return listOf(statement.firstChild, prev).any {
            it is PsiComment && it.text.contains("@param") && it.text.contains("props.")
        }
    }

    private fun formatType(raw: String): String? {
        if (raw.isEmpty()) return null
        val type = raw
            .removeSuffix(".isRequired")
            .replace("PropTypes.", "")
            .let {
                when (it) {
                    "bool" -> "boolean"
                    "func" -> "function"
                    else -> it
                }
            }
        return if (type.length > MAX_TYPE_LENGTH) type.take(MAX_TYPE_LENGTH) + "…" else type
    }

    private fun String.compact(): String = replace(Regex("\\s+"), " ").replace(Regex(",\\s*([}\\]])"), "$1").trim()
}
