package dev.angelminga.reactpropsdoc

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager

/**
 * Se registra como `psiTargetProvider` (API nueva de documentación): el IDE la consulta antes que los
 * `lang.documentationProvider` clásicos, así la sección "Props" aparece también cuando otros proveedores
 * (como el del plugin de React) responden primero.
 */
class ReactPropsDocumentationTargetProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val component = ReactPropsExtractor.findComponent(element) ?: return null
        if (ReactPropsExtractor.hasJsDocProps(component)) return null
        if (ReactPropsExtractor.extractProps(component).isEmpty()) return null
        return ReactPropsDocumentationTarget(element, originalElement, component.name)
    }
}

class ReactPropsDocumentationTarget(
    private val element: PsiElement,
    private val originalElement: PsiElement?,
    private val componentName: String,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val elementPointer = SmartPointerManager.createPointer(element)
        val originalPointer = originalElement?.let { SmartPointerManager.createPointer(it) }
        return Pointer {
            elementPointer.element?.let { ReactPropsDocumentationTarget(it, originalPointer?.element, componentName) }
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(componentName).icon(element.getIcon(0)).presentation()

    override val navigatable: Navigatable?
        get() = element as? Navigatable

    override fun computeDocumentationHint(): String? =
        ReactPropsDocumentation.legacyProviders(element)
            .firstNotNullOfOrNull { it.getQuickNavigateInfo(element, originalElement) }

    override fun computeDocumentation(): DocumentationResult? =
        ReactPropsDocumentation.generate(element, originalElement)?.let { DocumentationResult.documentation(it) }
}

/** Arma el HTML: documentación de los proveedores del IDE + sección "Props". */
object ReactPropsDocumentation {

    fun legacyProviders(element: PsiElement) = LanguageDocumentation.INSTANCE.allForLanguage(element.language)

    fun generate(element: PsiElement, originalElement: PsiElement?): String? {
        val component = ReactPropsExtractor.findComponent(element) ?: return null
        val props = ReactPropsExtractor.extractProps(component)
        if (props.isEmpty()) return null

        val row = renderProps(props)
        val section = DocumentationMarkup.SECTIONS_START + row + DocumentationMarkup.SECTIONS_END
        val base = legacyProviders(element).firstNotNullOfOrNull { it.generateDoc(element, originalElement) }
            ?: return DocumentationMarkup.DEFINITION_START + "<b>" + esc(component.name) +
            "</b>" + DocumentationMarkup.DEFINITION_END + section

        // Insertar antes de la fila con la ubicación del archivo (icono + nombre del .jsx / paquete)
        val icon = base.indexOf("<icon src='AllIcons.FileTypes")
        val locationRow = if (icon >= 0) base.lastIndexOf("<tr>", icon) else -1
        return if (locationRow >= 0) base.substring(0, locationRow) + row + base.substring(locationRow) else base + section
    }

    private fun renderProps(props: List<ReactProp>): String {
        val rows = props.joinToString("<br/>") { p ->
            buildString {
                append("<code>").append(esc(p.name)).append("</code>")
                p.type?.let { append(": ").append(esc(it)) }
                when {
                    p.required -> append(" — <b>requerida</b>")
                    p.defaultValue != null -> append(" — opcional (= <code>").append(esc(p.defaultValue)).append("</code>)")
                    p.type != null -> append(" — opcional")
                }
                p.description?.let { append("<br/>&nbsp;&nbsp;&nbsp;&nbsp;<i>").append(esc(it)).append("</i>") }
            }
        }
        return DocumentationMarkup.SECTION_HEADER_START + "Props (${props.size}):" +
            DocumentationMarkup.SECTION_SEPARATOR + rows +
            DocumentationMarkup.SECTION_END
    }

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}
