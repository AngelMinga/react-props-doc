package dev.angelminga.reactpropsdoc

import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifierAlias
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
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptPropertySignature
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
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
    val description: String? = null,
    val warning: String? = null,
)

/** Props que un componente de librería hereda de otro tipo (p. ej. Card ← Paper). */
data class InheritedProps(
    val source: String,
    val props: List<ReactProp>,
    val note: String? = null,
    val header: String = "Heredadas de $source:",
)

data class ComponentProps(val own: List<ReactProp>, val inherited: List<InheritedProps> = emptyList()) {
    fun isEmpty() = own.isEmpty() && inherited.isEmpty()
}

/** Declaración resuelta (componente o hook) con su función, si se pudo determinar. */
data class ReactComponent(
    val name: String,
    val declaration: PsiElement,
    val function: JSFunction?,
    val file: PsiFile,
)

object ReactPropsExtractor {

    private const val MAX_DEPTH = 8
    private const val MAX_INHERITED = 25

    /** Tipos genéricos de utilidad que aparecen en `extends` y no aportan props propias. */
    private val IGNORED_BASES = setOf("StandardProps", "DistributiveOmit", "Omit", "Partial", "Pick", "OverridableStringUnion")

    /** Resuelve el elemento destino del hover a un componente React (nombre en PascalCase). */
    fun findComponent(element: PsiElement?): ReactComponent? =
        findDeclaration(element)?.takeIf { it.name.firstOrNull()?.isUpperCase() == true }

    /**
     * Sigue imports, re-exportaciones y `export default X` hasta la declaración real
     * (variable, función o clase) y devuelve su nombre y su función, si la tiene.
     */
    fun findDeclaration(element: PsiElement?, depth: Int = 0): ReactComponent? {
        if (element == null || depth > MAX_DEPTH) return null
        return when (element) {
            is ES6ImportedBinding ->
                element.findReferencedElements().firstNotNullOfOrNull { findDeclaration(it, depth + 1) }

            // Re-exportaciones de librerías: `export { default as Button } from './Button'`
            is ES6ImportExportSpecifierAlias ->
                findDeclaration(element.findAliasedElement(), depth + 1)
                    ?: findDeclaration(element.findSpecifierElement(), depth + 1)

            is ES6ImportExportSpecifier ->
                element.resolveOverAliases(HashSet()).firstNotNullOfOrNull { findDeclaration(it.element, depth + 1) }

            // `export default Button;`
            is ES6ExportDefaultAssignment ->
                findDeclaration(
                    element.namedElement
                        ?: PsiTreeUtil.findChildOfType(element, JSReferenceExpression::class.java)?.resolve(),
                    depth + 1,
                )

            is JSVariable -> {
                val name = element.name ?: return null
                declaration(name, element, unwrapFunction(element.initializer, depth))
            }

            is JSFunction -> {
                val name = element.name ?: PsiTreeUtil.getParentOfType(element, JSVariable::class.java)?.name
                ?: return null
                declaration(name, element, element)
            }

            is JSClass -> declaration(element.name ?: return null, element, null)

            else -> null
        }
    }

    private fun declaration(name: String, declaration: PsiElement, function: JSFunction?): ReactComponent? {
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

    /** Sigue alias de import/export hasta el elemento declarado (interfaz, tipo, variable…). */
    private fun followAliases(element: PsiElement?): PsiElement? {
        var current = element
        repeat(MAX_DEPTH) {
            current = when (val e = current) {
                is ES6ImportedBinding -> e.findReferencedElements().firstOrNull()
                is ES6ImportExportSpecifierAlias -> e.findAliasedElement() ?: e.findSpecifierElement()
                is ES6ImportExportSpecifier -> e.resolveOverAliases(HashSet()).firstOrNull()?.element
                else -> return e
            }
        }
        return current
    }

    fun extractProps(component: ReactComponent): ComponentProps {
        if (component.file.name.endsWith(".d.ts")) return declarationFileProps(component)

        val propTypes = objectAssignedTo(component, "propTypes")
        val defaults = objectAssignedTo(component, "defaultProps")
            ?.properties?.mapNotNull { p -> p.name?.let { it to p.value?.text?.compact() } }?.toMap()
            .orEmpty()
        val usage = propsUsage(component.function)
        val checkWarnings = propTypes != null && ReactPropsDocSettings.options.showWarnings

        val result = LinkedHashMap<String, ReactProp>()
        propTypes?.properties?.forEach { p ->
            val name = p.name ?: return@forEach
            val raw = p.value?.text?.compact() ?: ""
            result[name] = ReactProp(
                name = name,
                type = formatType(raw),
                required = raw.endsWith(".isRequired"),
                defaultValue = defaults[name] ?: usage.props[name],
                description = JsDoc.of(p)?.summary,
                warning = if (checkWarnings && usage.complete && name !in usage.props) "no se usa en el componente" else null,
            )
        }
        usage.props.forEach { (name, default) ->
            if (name !in result) {
                result[name] = ReactProp(
                    name = name,
                    defaultValue = defaults[name] ?: default,
                    warning = if (checkWarnings) "no está declarada en propTypes" else null,
                )
            }
        }
        defaults.forEach { (name, default) ->
            if (name !in result) result[name] = ReactProp(name = name, defaultValue = default)
        }
        return ComponentProps(result.values.toList())
    }

    // ---------- .d.ts de librerías (MUI) ----------

    /**
     * Props declaradas en un `.d.ts` de librería (p. ej. @mui/material): toma solo las props propias del
     * componente (`XxxOwnProps`, `BaseXxxProps` o `XxxProps`), sin las heredadas de HTML/React.
     * Si no están en el mismo archivo (Box), las busca siguiendo el `XxxTypeMap` de su tipo.
     */
    private fun declarationFileProps(component: ReactComponent): ComponentProps {
        val propsInterface = propsInterfaceIn(component.file, component.name)
            ?: PsiTreeUtil.findChildrenOfType(component.declaration, JSReferenceExpression::class.java)
                .filter { it.referenceName?.endsWith("TypeMap") == true }
                .firstNotNullOfOrNull { ref ->
                    followAliases(ref.resolve())?.containingFile?.let { propsInterfaceIn(it, component.name) }
                }
            ?: return ComponentProps(emptyList())

        val own = members(propsInterface)
        val inherited = if (ReactPropsDocSettings.options.showInherited) {
            val ownNames = own.map { it.name }.toSet()
            val extended = extendedComponentProps(component, ownNames)
            extended + inheritedProps(propsInterface, ownNames) +
                listOfNotNull(htmlAttributes(component, isButtonBase = extended.any { it.source == "ButtonBase" }))
        } else {
            emptyList()
        }
        return ComponentProps(own, inherited)
    }

    /**
     * Componentes declarados como `ExtendButtonBase<ButtonTypeMap>` (Button, IconButton, Fab…):
     * la herencia no está en `extends`, sino en el tipo de la declaración.
     */
    private fun extendedComponentProps(component: ReactComponent, ownNames: Set<String>): List<InheritedProps> =
        PsiTreeUtil.findChildrenOfType(component.declaration, JSReferenceExpression::class.java)
            .filter { it.referenceName?.matches(Regex("Extend[A-Z]\\w*")) == true && it.referenceName?.endsWith("TypeMap") == false }
            .mapNotNull { ref ->
                val base = ref.referenceName!!.removePrefix("Extend")
                val file = followAliases(ref.resolve())?.containingFile ?: return@mapNotNull null
                val props = propsInterfaceIn(file, base)?.let(::members).orEmpty().filter { it.name !in ownNames }
                if (props.isEmpty()) null else InheritedProps(base, props.take(MAX_INHERITED))
            }

    /** Props de una interfaz de `.d.ts` con su tipo, si es requerida, `@default` y descripción. */
    private fun members(propsInterface: TypeScriptInterface): List<ReactProp> =
        propsInterface.body?.typeMembers.orEmpty().filterIsInstance<TypeScriptPropertySignature>()
            .mapNotNull { member ->
                val name = member.name ?: return@mapNotNull null
                val doc = JsDoc.of(member)
                ReactProp(
                    name = name,
                    type = member.typeDeclaration?.text?.compact()?.let(::truncate),
                    required = !member.isOptional,
                    defaultValue = doc?.defaultValue,
                    description = doc?.summary,
                )
            }

    /** Elemento HTML raíz (`RootComponent extends React.ElementType = 'button'`) y sus atributos más útiles. */
    private fun htmlAttributes(component: ReactComponent, isButtonBase: Boolean): InheritedProps? {
        val root = Regex("RootComponent extends React\\.ElementType = '([a-z][a-z0-9]*)'").find(component.file.text)
            ?.groupValues?.get(1)
            ?: Regex("TypeMap<\\{},\\s*'([a-z][a-z0-9]*)'").find(component.declaration.text)?.groupValues?.get(1)
            ?: return null
        var props = HTML_ATTRIBUTES[root] ?: COMMON_HTML_ATTRIBUTES
        // ButtonBase pone type="button" si no se indica (en HTML el valor por defecto es "submit")
        if (isButtonBase) props = props.map { if (it.name == "type") it.copy(defaultValue = "'button'") else it }
        return InheritedProps(
            source = "<$root>",
            props = props,
            note = "y demás atributos HTML / aria-*",
            header = "Atributos HTML de <$root>:",
        )
    }

    // Tipos según @types/react (los manejadores de eventos se muestran como `(event) => void`)
    private fun attr(name: String, type: String) = ReactProp(name, type)
    private const val HANDLER = "(event) => void"

    private val COMMON_HTML_ATTRIBUTES = listOf(
        attr("id", "string"), attr("className", "string"), attr("style", "CSSProperties"),
        attr("title", "string"), attr("role", "AriaRole"), attr("onClick", HANDLER), attr("aria-label", "string"),
    )

    private val HTML_ATTRIBUTES = mapOf(
        "button" to listOf(
            attr("type", "\"submit\" | \"reset\" | \"button\""), attr("name", "string"),
            attr("value", "string | number | readonly string[]"), attr("form", "string"),
            attr("onClick", HANDLER), attr("onFocus", HANDLER), attr("onBlur", HANDLER),
        ) + COMMON_HTML_ATTRIBUTES.filter { it.name != "onClick" },
        "a" to listOf(
            attr("href", "string"), attr("target", "\"_blank\" | \"_self\" | string"), attr("rel", "string"), attr("download", "any"),
        ) + COMMON_HTML_ATTRIBUTES,
        "input" to listOf(
            attr("type", "string"), attr("name", "string"), attr("value", "string | number | readonly string[]"),
            attr("placeholder", "string"), attr("onChange", HANDLER), attr("maxLength", "number"), attr("autoComplete", "string"),
        ) + COMMON_HTML_ATTRIBUTES,
        "form" to listOf(
            attr("onSubmit", HANDLER), attr("action", "string"), attr("method", "string"), attr("noValidate", "boolean"),
        ) + COMMON_HTML_ATTRIBUTES,
        "img" to listOf(
            attr("src", "string"), attr("alt", "string"), attr("width", "number | string"), attr("height", "number | string"),
            attr("loading", "\"eager\" | \"lazy\""),
        ) + COMMON_HTML_ATTRIBUTES,
    )

    private fun propsInterfaceIn(file: PsiFile, base: String): TypeScriptInterface? {
        val interfaces = PsiTreeUtil.findChildrenOfType(file, TypeScriptInterface::class.java).associateBy { it.name }
        return listOf("${base}OwnProps", "Base${base}Props", "${base}Props").firstNotNullOfOrNull { interfaces[it] }
    }

    /** Agrupa las props de los tipos en `extends` (Card ← Paper, Dialog ← Modal, Grid ← Breakpoints). */
    private fun inheritedProps(propsInterface: TypeScriptInterface, ownNames: Set<String>): List<InheritedProps> {
        val extendsList = propsInterface.extendsList ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(extendsList, JSReferenceExpression::class.java)
            .distinctBy { it.referenceName }
            .mapNotNull { ref ->
                val name = ref.referenceName ?: return@mapNotNull null
                when {
                    name in IGNORED_BASES || name == "Theme" -> null
                    name == "SystemProps" -> InheritedProps(
                        "MUI System", emptyList(), "props de estilo: m, p, display, color, bgcolor, width…",
                    )
                    else -> {
                        val props = typeMembers(followAliases(ref.resolve()), name).filter { it.name !in ownNames }
                        if (props.isEmpty()) null
                        else InheritedProps(name.removeSuffix("OwnProps").removeSuffix("Props"), props.take(MAX_INHERITED))
                    }
                }
            }
    }

    /** Props de un tipo resuelto: interfaz propia del componente, la interfaz misma o la del alias. */
    private fun typeMembers(target: PsiElement?, name: String): List<ReactProp> {
        val file = target?.containingFile ?: return emptyList()
        val base = name.removeSuffix("OwnProps").removeSuffix("Props")
        if (name.endsWith("Props")) propsInterfaceIn(file, base)?.let { return members(it) }
        return when (target) {
            is TypeScriptInterface -> members(target)
            // `type Breakpoints = IfEquals<..., RegularBreakpoints, ...>`: primera interfaz con props
            is TypeScriptTypeAlias -> PsiTreeUtil.findChildrenOfType(target, JSReferenceExpression::class.java)
                .mapNotNull { followAliases(it.resolve()) as? TypeScriptInterface }
                .map(::members)
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            else -> emptyList()
        }
    }

    // ---------- Hover sobre una prop (`openDialog=`) ----------

    /** Si el elemento es una propiedad de `Componente.propTypes`, devuelve el componente y la prop. */
    fun findPropTypesEntry(element: PsiElement?): Pair<ReactComponent, ReactProp>? {
        val property = element as? JSProperty ?: element?.parent as? JSProperty ?: return null
        val assignment = (property.parent as? JSObjectLiteralExpression)?.parent as? JSAssignmentExpression ?: return null
        val target = assignment.lOperand?.text?.compact() ?: return null
        if (!target.endsWith(".propTypes")) return null
        val componentRef = PsiTreeUtil.findChildrenOfType(assignment.lOperand, JSReferenceExpression::class.java)
            .firstOrNull { it.qualifier == null } ?: return null
        val component = findComponent(componentRef.resolve()) ?: return null
        val prop = extractProps(component).own.firstOrNull { it.name == property.name } ?: return null
        return component to prop
    }

    // ---------- Componentes propios (propTypes / desestructuración) ----------

    /** `Componente.propTypes = { ... }`, si existe. */
    fun propTypesObject(component: ReactComponent) = objectAssignedTo(component, "propTypes")

    /** Busca `Componente.<field> = { ... }` en el archivo del componente. */
    private fun objectAssignedTo(component: ReactComponent, field: String): JSObjectLiteralExpression? {
        val target = "${component.name}.$field"
        return PsiTreeUtil.findChildrenOfType(component.file, JSAssignmentExpression::class.java)
            .firstOrNull { it.lOperand?.text?.compact() == target }
            ?.rOperand as? JSObjectLiteralExpression
    }

    /**
     * Props usadas por el componente (desestructuradas o `props.xxx`), con su valor por defecto.
     * `complete` = se puede afirmar que no usa otras (sin `...rest`, `{...props}` ni `props` pasado entero).
     * `anchors` = primer lugar donde aparece cada prop (para marcarla en el editor).
     */
    data class PropsUsage(val props: Map<String, String?>, val complete: Boolean, val anchors: Map<String, PsiElement>)

    fun propsUsage(function: JSFunction?): PropsUsage {
        val param = function?.parameters?.firstOrNull() ?: return PropsUsage(emptyMap(), false, emptyMap())
        val result = LinkedHashMap<String, String?>()
        val anchors = LinkedHashMap<String, PsiElement>()
        var complete = true

        fun collect(obj: JSDestructuringObject?) {
            if (obj == null || obj.text.contains("...")) complete = false
            obj?.properties?.forEach { p ->
                // `...rest` no es una prop
                if (p.text.startsWith("...")) return@forEach
                val name = p.name ?: return@forEach
                val default = p.destructuringElement?.initializer?.text?.compact()
                result.putIfAbsent(name, default)
                anchors.putIfAbsent(name, p)
            }
        }

        when (param) {
            is JSDestructuringParameter -> collect(param.target as? JSDestructuringObject)
            is JSParameter -> {
                val paramName = param.name ?: return PropsUsage(emptyMap(), false, emptyMap())
                PsiTreeUtil.findChildrenOfType(function, JSReferenceExpression::class.java)
                    .filter { it.qualifier == null && it.referenceName == paramName }
                    .forEach { ref ->
                        val parent = ref.parent
                        when {
                            parent is JSReferenceExpression && parent.qualifier == ref ->
                                parent.referenceName?.let {
                                    result.putIfAbsent(it, null)
                                    anchors.putIfAbsent(it, parent.referenceNameElement ?: parent)
                                }
                            parent is JSDestructuringElement && parent.initializer == ref ->
                                collect(parent.target as? JSDestructuringObject)
                            else -> complete = false
                        }
                    }
            }
            else -> complete = false
        }
        return PropsUsage(result, complete, anchors)
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
        return truncate(type)
    }

    fun truncate(type: String): String {
        val max = ReactPropsDocSettings.options.maxTypeLength
        return if (type.length > max) type.take(max) + "…" else type
    }

    fun String.compact(): String = replace(Regex("\\s+"), " ").replace(Regex(",\\s*([}\\]])"), "$1").trim()
}
