package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReactPropsDocumentationProviderTest : BasePlatformTestCase() {

    private fun docAtCaret(): String? {
        val original = myFixture.file.findElementAt(myFixture.caretOffset)
        val target = DocumentationManager.getInstance(project)
            .findTargetElement(myFixture.editor, myFixture.file, original)
        return DocumentationManager.getProviderFromElement(target, original).generateDoc(target, original)
    }

    fun testPropTypesWithBodyDestructuring() {
        myFixture.addFileToProject(
            "ConfirmationDialog.jsx",
            """
            import PropTypes from 'prop-types'
            const ConfirmationDialog = props => {
                const { icon, openDialog, title = 'Hola' } = props
                return <div>{title}</div>
            }
            ConfirmationDialog.propTypes = {
                icon : PropTypes.string.isRequired,
                openDialog : PropTypes.bool.isRequired,
                title : PropTypes.string,
                typeIcon : PropTypes.oneOf(['success', 'error']),
            }
            export default ConfirmationDialog
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Page.jsx",
            """
            import ConfirmationDialog from './ConfirmationDialog'
            export const Page = () => <Confirmation<caret>Dialog icon="x" />
            """.trimIndent(),
        )
        val doc = docAtCaret()
        println(doc)
        assertNotNull(doc)
        assertTrue(doc!!.contains("Props (4)"))
        assertTrue(doc.contains("<code>icon</code>: string — <b>requerida</b>"))
        assertTrue(doc.contains("<code>openDialog</code>: boolean"))
        assertTrue(doc.contains("<code>title</code>: string — opcional (= <code>&#39;Hola&#39;</code>)"))
        assertTrue(doc.contains("oneOf([&#39;success&#39;, &#39;error&#39;])"))
        assertTrue("Props debe ir antes de la ubicación", doc.indexOf("Props (") < doc.indexOf("AllIcons.FileTypes"))
    }

    fun testSignatureDestructuringWithoutPropTypes() {
        myFixture.addFileToProject(
            "ContainerButtons.jsx",
            """
            export default function ContainerButtons({ btnLabelFirst, isMargin = false, btnActionFirst }) {
                return <div>{btnLabelFirst}</div>
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Page.jsx",
            """
            import ContainerButtons from './ContainerButtons'
            export const Page = () => <Container<caret>Buttons />
            """.trimIndent(),
        )
        val doc = docAtCaret()
        println(doc)
        assertNotNull(doc)
        assertTrue(doc!!.contains("Props (3)"))
        assertTrue(doc.contains("<code>isMargin</code> — opcional (= <code>false</code>)"))
    }

    fun testComponentWithJsDocIsLeftAlone() {
        myFixture.addFileToProject(
            "Documented.jsx",
            """
            /**
             * @param {Object} props
             * @param {string} props.title - Título
             */
            const Documented = props => <div>{props.title}</div>
            export default Documented
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Page.jsx",
            """
            import Documented from './Documented'
            export const Page = () => <Docu<caret>mented />
            """.trimIndent(),
        )
        val doc = docAtCaret()
        println(doc)
        assertFalse(doc.orEmpty().contains("Props ("))
    }
}
