package dev.angelminga.reactpropsdoc

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReactPropsDocumentationTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    override fun tearDown() {
        try {
            ReactPropsDocSettings.options.apply {
                showDescriptions = true
                showHooks = true
                hideSignature = true
            }
        } finally {
            super.tearDown()
        }
    }

    private fun docFor(fileName: String, source: String, page: String): String? {
        myFixture.addFileToProject(fileName, source.trimIndent())
        myFixture.configureByText("Page.jsx", page.trimIndent())
        return docAtCaret().also(::println)
    }

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
            import { Button, TextField, Grid, Dialog, Typography, Box, Card } from '@mui/material'
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
        assertTrue(doc, doc.contains("If <code>true</code>, the component is disabled."))
        assertTrue("Props debe ir antes de la ubicación", doc.indexOf("Props (") < doc.lastIndexOf("<icon src="))
        assertFalse("No debe listar props HTML heredadas", doc.contains("<code>onClick</code>"))
        assertFalse("La firma larga del IDE debe ocultarse", doc.contains("ExtendButtonBase"))
        assertTrue(doc, doc.contains("<b>Button</b>"))
        assertTrue("Se conserva el resto de la documentación (Demos/API)", doc.contains("Button API"))
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

    // ---------- #3 Hover sobre una prop ----------

    fun testPropAttributeHover() {
        val doc = docFor(
            "Dlg.jsx",
            """
            import PropTypes from 'prop-types'
            const Dlg = ({ openDialog, typeIcon = 'info' }) => <div />
            Dlg.propTypes = {
                openDialog : PropTypes.bool.isRequired,
                typeIcon : PropTypes.oneOf(['success', 'error', 'info']),
            }
            export default Dlg
            """,
            """
            import Dlg from './Dlg'
            export const P = () => <Dlg openDialog={true} type<caret>Icon="error" />
            """,
        )
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<b>Dlg</b>"))
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("Requerida:</td><td valign='top'>no"))
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>&#39;info&#39;</code>"))
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>&#39;success&#39;</code> | <code>&#39;error&#39;</code> | <code>&#39;info&#39;</code>"))
    }

    // ---------- #4 Box ----------

    fun testMuiBox() {
        val doc = muiPage("B<caret>ox")
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>component</code>"))
        assertTrue(doc.orEmpty(), doc.orEmpty().contains("<code>sx</code>"))
    }

    // ---------- #5 Heredadas ----------

    fun testMuiCardInheritsPaper() {
        val doc = muiPage("Ca<caret>rd").orEmpty()
        assertTrue(doc, doc.contains("Heredadas de Paper:"))
        assertTrue(doc, doc.contains("<code>elevation</code>"))
    }

    fun testMuiDialogInheritsModal() {
        val doc = muiPage("Dia<caret>log").orEmpty()
        assertTrue(doc, doc.contains("Heredadas de Modal:"))
        assertTrue(doc, doc.contains("<code>keepMounted</code>"))
    }

    fun testMuiGridBreakpointsAndSystem() {
        val doc = muiPage("Gr<caret>id").orEmpty()
        assertTrue(doc, doc.contains("Heredadas de Breakpoints:"))
        assertTrue(doc, doc.contains("<code>xs</code>"))
        assertTrue(doc, doc.contains("Heredadas de MUI System:"))
    }

    // ---------- #6 Inconsistencias ----------

    fun testRestElementIsNotAProp() {
        val doc = docFor(
            "Chip2.jsx",
            """
            import PropTypes from 'prop-types'
            const Chip2 = ({ text, legacy, ...rest }) => <span {...rest}>{text}</span>
            Chip2.propTypes = { text : PropTypes.string, legacy : PropTypes.bool, unused : PropTypes.bool }
            export default Chip2
            """,
            """
            import Chip2 from './Chip2'
            export const P = () => <Ch<caret>ip2 text="x" />
            """,
        ).orEmpty()
        assertTrue(doc, doc.contains("Props (3)"))
        assertFalse(doc, doc.contains("<code>rest</code>"))
        assertFalse("Con ...rest no se puede afirmar que una prop no se usa", doc.contains("no se usa"))
    }

    fun testWarnings() {
        val doc = docFor(
            "Card2.jsx",
            """
            import PropTypes from 'prop-types'
            const Card2 = props => {
                const { title, subtitle } = props
                return <div>{title}{subtitle}{props.footer}</div>
            }
            Card2.propTypes = {
                title : PropTypes.string.isRequired,
                subtitle : PropTypes.string,
                legacy : PropTypes.bool,
            }
            export default Card2
            """,
            """
            import Card2 from './Card2'
            export const P = () => <Ca<caret>rd2 title="x" />
            """,
        ).orEmpty()
        assertTrue(doc, doc.contains("<code>legacy</code>: boolean — opcional ⚠ <i>no se usa en el componente</i>"))
        assertTrue(doc, doc.contains("<code>footer</code> ⚠ <i>no está declarada en propTypes</i>"))
        assertFalse(doc, doc.contains("<code>title</code>: string — <b>requerida</b> ⚠"))
    }

    fun testNoUnusedWarningWhenPropsArePassedThrough() {
        val doc = docFor(
            "Wrapper.jsx",
            """
            import PropTypes from 'prop-types'
            const Wrapper = props => {
                const { title } = props
                return <Inner {...props}>{title}</Inner>
            }
            Wrapper.propTypes = { title : PropTypes.string, legacy : PropTypes.bool }
            export default Wrapper
            """,
            """
            import Wrapper from './Wrapper'
            export const P = () => <Wrap<caret>per />
            """,
        ).orEmpty()
        assertTrue(doc, doc.contains("Props (2)"))
        assertFalse(doc, doc.contains("no se usa"))
    }

    // ---------- #7 Hooks ----------

    fun testHookObjectReturn() {
        val doc = docFor(
            "useStep.js",
            """
            import { useState, useCallback } from 'react'
            export const useStepTransfer = ({ args, initial = 0 }) => {
                const [step, setStep] = useState(initial)
                const handleNext = useCallback(() => setStep(s => s + 1), [])
                const labels = ['a', 'b']
                const helper = () => { return { notThis: 1 } }
                return { step, setStep, handleNext, labels, body: <div />, optional: 2 }
            }
            """,
            """
            import { useStepTransfer } from './useStep'
            export const P = () => { const r = useStep<caret>Transfer({ args: 1 }); return null }
            """,
        ).orEmpty()
        assertTrue(doc, doc.contains("Parámetros:"))
        assertTrue(doc, doc.contains("<code>initial</code> = 0"))
        assertTrue(doc, doc.contains("Devuelve (6):"))
        assertTrue(doc, doc.contains("<code>step</code> — estado"))
        assertTrue(doc, doc.contains("<code>setStep</code> — función"))
        assertTrue(doc, doc.contains("<code>handleNext</code> — función"))
        assertTrue(doc, doc.contains("<code>labels</code> — arreglo"))
        assertTrue(doc, doc.contains("<code>body</code> — JSX"))
        assertFalse("No debe incluir returns de funciones internas", doc.contains("notThis"))
    }

    fun testHookArrayReturn() {
        val doc = docFor(
            "useToggle.js",
            """
            import { useState } from 'react'
            export function useToggle() {
                const [on, setOn] = useState(false)
                const toggle = () => setOn(v => !v)
                return [on, toggle]
            }
            """,
            """
            import { useToggle } from './useToggle'
            export const P = () => { const [a, b] = useTog<caret>gle(); return null }
            """,
        ).orEmpty()
        assertTrue(doc, doc.contains("Devuelve [2]:"))
        assertTrue(doc, doc.contains("<code>on</code> — estado"))
        assertTrue(doc, doc.contains("<code>toggle</code> — función"))
    }

    // ---------- #8 Ajustes ----------

    fun testSettingsHideDescriptionsAndKeepSignature() {
        ReactPropsDocSettings.options.showDescriptions = false
        ReactPropsDocSettings.options.hideSignature = false
        val doc = muiPage("Butt<caret>on").orEmpty()
        assertTrue(doc, doc.contains("<code>disabled</code>"))
        assertFalse(doc, doc.contains("the component is disabled"))
        assertTrue(doc, doc.contains("ExtendButtonBase"))
    }

    fun testSettingsDisableHooks() {
        ReactPropsDocSettings.options.showHooks = false
        val doc = docFor(
            "useX.js",
            "export const useX = () => { return { a: 1 } }",
            "import { useX } from './useX'\nexport const P = () => { use<caret>X(); return null }",
        ).orEmpty()
        assertFalse(doc, doc.contains("Devuelve"))
    }
}
