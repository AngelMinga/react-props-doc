package dev.angelminga.reactpropsdoc

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReactPropsDocumentationTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    /** Mismo camino que usa el popup del IDE: targets de documentación → HTML. */
    private fun docAtCaret(): String? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }

    private fun muiPage(tag: String): String? {
        myFixture.copyDirectoryToProject("node_modules", "node_modules")
        myFixture.copyFileToProject("package.json")
        myFixture.configureByText(
            "Page.jsx",
            """
            import { Button, TextField, Grid, Dialog, Typography } from '@mui/material'
            export const Page = () => <$tag />
            """.trimIndent(),
        )
        return docAtCaret().also(::println)
    }

    fun testMuiButton() {
        val doc = muiPage("Butt<caret>on")
        assertNotNull(doc)
        assertTrue(doc!!, doc.contains("Props ("))
        assertTrue(doc, doc.contains("<code>disabled</code>: boolean — opcional (= <code>false</code>)"))
        assertTrue(doc, doc.contains("If `true`, the component is disabled."))
        assertFalse("No debe listar props HTML heredadas", doc.contains("<code>onClick</code>"))
    }

    fun testMuiTextField() {
        val doc = muiPage("TextFi<caret>eld")
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>helperText</code>"))
    }

    fun testMuiGrid() {
        val doc = muiPage("Gr<caret>id")
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>container</code>"))
    }

    fun testMuiDialog() {
        val doc = muiPage("Dia<caret>log")
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>open</code>: ModalProps[&#39;open&#39;] — <b>requerida</b>"))
    }

    fun testMuiTypography() {
        val doc = muiPage("Typogra<caret>phy")
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>variant</code>"))
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
