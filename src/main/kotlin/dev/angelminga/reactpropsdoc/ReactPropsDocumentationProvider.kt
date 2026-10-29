package dev.angelminga.reactpropsdoc

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement

/**
 * Agrega una sección "Props" a la documentación de un componente React,
 * conservando la documentación que genera el plugin de JavaScript (firma, JSDoc, ubicación).
 */
class ReactPropsDocumentationProvider : AbstractDocumentationProvider() {

    private val inProgress = ThreadLocal.withInitial { false }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null || inProgress.get()) return null
        val component = ReactPropsExtractor.findComponent(element) ?: return null
        if (ReactPropsExtractor.hasJsDocProps(component)) return null
        val props = ReactPropsExtractor.extractProps(component)
        if (props.isEmpty()) return null

        val row = renderProps(props)
        val section = DocumentationMarkup.SECTIONS_START + row + DocumentationMarkup.SECTIONS_END
        val base = baseDoc(element, originalElement)
            ?: return DocumentationMarkup.DEFINITION_START + "<b>" + esc(component.name) +
            "</b>" + DocumentationMarkup.DEFINITION_END + section

        // Insertar antes de la fila con la ubicación del archivo (icono + nombre del .jsx)
        val icon = base.indexOf("<icon src='AllIcons.FileTypes")
        val locationRow = if (icon >= 0) base.lastIndexOf("<tr>", icon) else -1
        return if (locationRow >= 0) base.substring(0, locationRow) + row + base.substring(locationRow) else base + section
    }

    /** Documentación que generarían los demás proveedores (la del plugin de JavaScript). */
    private fun baseDoc(element: PsiElement, originalElement: PsiElement?): String? {
        inProgress.set(true)
        try {
            return LanguageDocumentation.INSTANCE.allForLanguage(element.language)
                .asSequence()
                .filter { it !is ReactPropsDocumentationProvider }
                .firstNotNullOfOrNull { it.generateDoc(element, originalElement) }
        } finally {
            inProgress.set(false)
        }
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
            }
        }
        return DocumentationMarkup.SECTION_HEADER_START + "Props (${props.size}):" +
            DocumentationMarkup.SECTION_SEPARATOR + rows +
            DocumentationMarkup.SECTION_END
    }

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}
