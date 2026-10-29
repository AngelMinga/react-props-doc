package dev.angelminga.reactpropsdoc

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager

/**
 * Se registra como `psiTargetProvider` (API nueva de documentación): el IDE la consulta antes que los
 * `lang.documentationProvider` clásicos, así nuestras secciones aparecen también cuando otros proveedores
 * (como el del plugin de React) responden primero.
 */
class ReactPropsDocumentationTargetProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        if (ReactPropsDocumentation.generate(element, originalElement, baseOnly = true) == null) return null
        val name = (element as? PsiNamedElement)?.name ?: element.text?.take(40) ?: return null
        return ReactPropsDocumentationTarget(element, originalElement, name)
    }
}

class ReactPropsDocumentationTarget(
    private val element: PsiElement,
    private val originalElement: PsiElement?,
    private val name: String,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val elementPointer = SmartPointerManager.createPointer(element)
        val originalPointer = originalElement?.let { SmartPointerManager.createPointer(it) }
        return Pointer {
            elementPointer.element?.let { ReactPropsDocumentationTarget(it, originalPointer?.element, name) }
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(name).icon(element.getIcon(0)).presentation()

    override val navigatable: Navigatable?
        get() = element as? Navigatable

    override fun computeDocumentationHint(): String? =
        ReactPropsDocumentation.legacyProviders(element)
            .firstNotNullOfOrNull { it.getQuickNavigateInfo(element, originalElement) }

    override fun computeDocumentation(): DocumentationResult? =
        ReactPropsDocumentation.generate(element, originalElement)?.let { DocumentationResult.documentation(it) }
}

/** Arma el HTML: documentación de los proveedores del IDE + nuestras secciones. */
object ReactPropsDocumentation {

    private val options get() = ReactPropsDocSettings.options

    fun legacyProviders(element: PsiElement) = LanguageDocumentation.INSTANCE.allForLanguage(element.language)

    /**
     * Componente → sección "Props"; hook → "Parámetros"/"Devuelve"; prop de `propTypes` → su detalle.
     * Con `baseOnly` solo comprueba si aplica (devuelve "" sin llamar a los demás proveedores).
     */
    fun generate(element: PsiElement, originalElement: PsiElement?, baseOnly: Boolean = false): String? {
        if (options.showTranslations) {
            translationPath(element)?.let { path ->
                return if (baseOnly) "" else translationDoc(element, originalElement, path)
            }
        }

        ReactPropsExtractor.findComponent(element)
            ?.takeUnless { ReactPropsExtractor.hasJsDocProps(it) }
            ?.let { component ->
                val props = ReactPropsExtractor.extractProps(component)
                if (!props.isEmpty()) {
                    return if (baseOnly) "" else componentDoc(element, originalElement, component, props)
                }
            }

        if (options.showHooks) {
            HookExtractor.findHook(element)?.let { hook ->
                return if (baseOnly) "" else hookDoc(element, originalElement, hook)
            }
        }

        if (options.showPropHover) {
            ReactPropsExtractor.findPropTypesEntry(element)?.let { (component, prop) ->
                return if (baseOnly) "" else propDoc(element, originalElement, component, prop)
            }
        }
        return null
    }

    /** Ruta `[página, clave…]` si el elemento es una clave de `getLanguagePage(…)` o una propiedad del JSON de traducciones. */
    private fun translationPath(element: PsiElement): List<String>? {
        if (element is JsonProperty) {
            val (_, path) = Translations.pathOf(element) ?: return null
            return path
        }
        if (element !is JSVariable && element !is JSDestructuringProperty) return null
        val usage = Translations.keyUsage(element) ?: return null
        val files = Translations.files(element.project) ?: return null
        if (files.page(files.baseLang, usage.page)?.findProperty(usage.key!!) == null) return null
        return listOf(usage.page, usage.key)
    }

    private fun translationDoc(element: PsiElement, original: PsiElement?, path: List<String>): String {
        val files = Translations.files(element.project) ?: return ""
        val rows = buildString {
            append(section("Página:", "<code>${esc(path.first())}</code>"))
            files.files.keys.forEach { lang ->
                val value = Translations.preview(files.valueAt(lang, path))
                append(section("$lang:", value?.let(::esc) ?: "⚠ <b>falta</b>"))
            }
            val missing = files.missingIn(path)
            if (missing.isNotEmpty()) {
                val shortcut = KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_SHOW_INTENTION_ACTIONS)
                    .ifEmpty { "Alt+Enter" }
                val targets = missing.joinToString(", ") { it.lowercase() + ".json" }
                append(
                    section(
                        "Cómo agregarla:",
                        "<b>${esc(shortcut)}</b> sobre <code>getLanguagePage(&#39;${esc(path.first())}&#39;)</code> " +
                            "o sobre la clave → <i>Agregar … a ${esc(targets)}</i>.<br/>" +
                            "Copia el texto de ${esc(files.baseLang)} para traducirlo después. " +
                            "También desde ${esc(targets)}: ${esc(shortcut)} sobre la página marcada.",
                    ),
                )
            }
        }
        val title = if (path.size == 1) path.first() else path.drop(1).joinToString(".")
        val definition = DocumentationMarkup.DEFINITION_START + "<b>" + esc(title) + "</b>" +
            DocumentationMarkup.DEFINITION_END
        return definition + DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
    }

    private fun componentDoc(element: PsiElement, original: PsiElement?, component: ReactComponent, props: ComponentProps): String {
        val rows = buildList {
            if (props.own.isNotEmpty()) add(section("Props (${props.own.size}):", props.own.joinToString("<br/>", transform = ::propRow)))
            props.inherited.forEach { group ->
                val content = buildString {
                    if (group.props.isNotEmpty()) append(group.props.joinToString("<br/>", transform = ::inheritedRow))
                    group.note?.let { if (group.props.isEmpty()) append(esc(it)) else append("<br/><i>").append(esc(it)).append("</i>") }
                }
                add(section(esc(group.header), content))
            }
        }.joinToString("")

        val definition = DocumentationMarkup.DEFINITION_START + "<b>" + esc(component.name) + "</b>" +
            DocumentationMarkup.DEFINITION_END
        return insert(baseDoc(element, original), rows, if (options.hideSignature) definition else null, definition)
    }

    private fun hookDoc(element: PsiElement, original: PsiElement?, hook: HookInfo): String {
        val rows = buildString {
            if (hook.params.isNotEmpty()) {
                append(section("Parámetros:", hook.params.joinToString("<br/>", transform = ::hookValueRow)))
            }
            val title = if (hook.returnsArray) "Devuelve [${hook.returns.size}]:" else "Devuelve (${hook.returns.size}):"
            val content = hook.returns.joinToString("<br/>", transform = ::hookValueRow) +
                if (hook.partial) "<br/><i>Algún return no se pudo analizar: la lista puede estar incompleta.</i>" else ""
            append(section(title, content))
        }
        val definition = DocumentationMarkup.DEFINITION_START + "<b>" + esc(hook.hook.name) + "</b>" +
            DocumentationMarkup.DEFINITION_END
        return insert(baseDoc(element, original), rows, null, definition)
    }

    private fun propDoc(element: PsiElement, original: PsiElement?, component: ReactComponent, prop: ReactProp): String {
        val rows = buildString {
            append(section("Componente:", "<b>${esc(component.name)}</b>"))
            append(section("Requerida:", if (prop.required) "<b>sí</b>" else "no"))
            prop.defaultValue?.let { append(section("Por defecto:", "<code>${esc(it)}</code>")) }
            prop.type?.let { type ->
                Regex("^oneOf\\(\\[(.*)]\\)$").find(type)?.groupValues?.get(1)?.let { values ->
                    append(section("Valores:", values.split(",").joinToString(" | ") { "<code>${esc(it.trim())}</code>" }))
                }
            }
            prop.description?.let { append(section("Descripción:", formatDescription(it))) }
            prop.warning?.let { append(section("Aviso:", "⚠ ${esc(it)}")) }
        }
        val definition = DocumentationMarkup.DEFINITION_START + "<b>" + esc(prop.name) + "</b>" +
            (prop.type?.let { ": " + esc(it) } ?: "") + DocumentationMarkup.DEFINITION_END
        return insert(baseDoc(element, original), rows, null, definition)
    }

    private fun baseDoc(element: PsiElement, original: PsiElement?): String? =
        legacyProviders(element).firstNotNullOfOrNull { it.generateDoc(element, original) }

    /**
     * Inserta las filas antes de la ubicación del archivo (icono + nombre del .jsx o del paquete npm).
     * `replaceDefinition` sustituye la firma del IDE; `fallbackDefinition` se usa si no hay documentación base.
     */
    private fun insert(base: String?, rows: String, replaceDefinition: String?, fallbackDefinition: String): String {
        if (base == null) return fallbackDefinition + DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
        val doc = if (replaceDefinition != null) replaceDefinition(base, replaceDefinition) else base
        val icon = doc.lastIndexOf("<icon src=")
        val locationRow = if (icon >= 0) doc.lastIndexOf("<tr>", icon) else -1
        return if (locationRow >= 0) doc.substring(0, locationRow) + rows + doc.substring(locationRow)
        else doc + DocumentationMarkup.SECTIONS_START + rows + DocumentationMarkup.SECTIONS_END
    }

    /** La firma que genera el IDE (larga en librerías como MUI) se reemplaza por el nombre: las props ya se listan. */
    private fun replaceDefinition(base: String, definition: String): String {
        val start = base.indexOf(DocumentationMarkup.DEFINITION_START)
        if (start < 0) return base
        val end = base.indexOf(DocumentationMarkup.DEFINITION_END, start)
        if (end < 0) return base
        return base.substring(0, start) + definition + base.substring(end + DocumentationMarkup.DEFINITION_END.length)
    }

    private fun section(header: String, content: String) =
        DocumentationMarkup.SECTION_HEADER_START + header + DocumentationMarkup.SECTION_SEPARATOR + content +
            DocumentationMarkup.SECTION_END

    private fun propRow(p: ReactProp) = buildString {
        append("<code>").append(esc(p.name)).append("</code>")
        p.type?.let { append(": ").append(esc(it)) }
        when {
            p.required -> append(" — <b>requerida</b>")
            p.defaultValue != null -> append(" — opcional (= <code>").append(esc(p.defaultValue)).append("</code>)")
            p.type != null -> append(" — opcional")
        }
        p.warning?.let { append(" ⚠ <i>").append(esc(it)).append("</i>") }
        if (options.showDescriptions) {
            p.description?.let { append("<br/>&nbsp;&nbsp;&nbsp;&nbsp;<i>").append(formatDescription(it)).append("</i>") }
        }
    }

    /** Fila compacta (sin descripción) para props heredadas: `nombre: tipo (= defecto)`. */
    private fun inheritedRow(p: ReactProp) = buildString {
        append("<code>").append(esc(p.name)).append("</code>")
        p.type?.let { append(": ").append(esc(it)) }
        if (p.required) append(" — <b>requerida</b>")
        p.defaultValue?.let { append(" (= <code>").append(esc(it)).append("</code>)") }
    }

    private fun hookValueRow(v: HookValue) =
        "<code>${esc(v.name)}</code>" + (v.kind?.let { if (it.startsWith("=")) " ${esc(it)}" else " — ${esc(it)}" } ?: "")

    private fun formatDescription(text: String) = esc(text).replace(Regex("`([^`]+)`"), "<code>$1</code>")

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}
