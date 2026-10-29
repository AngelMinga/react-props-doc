package dev.angelminga.reactpropsdoc

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtractionAndUnknownPropsTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    override fun tearDown() {
        try {
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun problems() = myFixture.doHighlighting().filter { it.description != null }.map { it.description!! }
        .also { println("PROBLEMAS: $it") }

    // ---------- #2 Prop que el componente no acepta ----------

    private fun mui(code: String) {
        myFixture.copyDirectoryToProject("node_modules", "node_modules")
        myFixture.copyFileToProject("package.json")
        myFixture.enableInspections(UnknownPropInspection())
        myFixture.configureByText(
            "Page.jsx",
            "import { Button, TextField, Dialog, Box, Typography, IconButton } from '@mui/material'\nexport const P = () => $code",
        )
    }

    fun testMuiButtonUnknownProp() {
        mui(
            """<Button tex<caret>t="Buscar" variant="outlined" type="submit" onClick={() => {}} aria-label="x" data-id="1"
               sx={{}} disableRipple startIcon={null} className="c" key="k" href="/x" />""",
        )
        val problems = problems().filter { "no tiene la prop" in it }
        assertEquals(listOf("Button no tiene la prop 'text'"), problems)
    }

    fun testMoveTextToChildren() {
        mui("""<Button tex<caret>t="Buscar" variant="outlined" />""")
        myFixture.launchAction(myFixture.findSingleIntention("Usar el texto como contenido (children)"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("""<Button variant="outlined">Buscar</Button>"""))
    }

    fun testTypoSuggestsSimilarProp() {
        mui("""<Button varie<caret>nt="outlined">x</Button>""")
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'variant'"))
        assertTrue(myFixture.editor.document.text.contains("""<Button variant="outlined">x</Button>"""))
    }

    fun testInheritedAndVariantPropsAreAccepted() {
        mui(
            """<>
                <Dialog open={true} onClose={() => {}} maxWidth="sm" fullWidth keepMounted foo={1} />
                <TextField variant="outlined" helperText="h" InputProps={{}} inputProps={{}} label="l" />
                <IconButton edge="end" disableRipple type="button" />
            </>""",
        )
        assertEquals(listOf("Dialog no tiene la prop 'foo'"), problems().filter { "no tiene la prop" in it })
    }

    fun testSystemComponentsAreNotChecked() {
        mui("""<><Box text="x" mt={2} /><Typography text="y" /></>""")
        assertEmpty(problems().filter { "no tiene la prop" in it })
    }

    fun testOwnComponentUnknownProp() {
        myFixture.enableInspections(UnknownPropInspection())
        myFixture.addFileToProject(
            "Dlg.jsx",
            "import PropTypes from 'prop-types'\nconst Dlg = ({ title, isOpen = false }) => <div>{title}</div>\nDlg.propTypes = { title: PropTypes.string }\nexport default Dlg",
        )
        myFixture.addFileToProject("Pass.jsx", "const Pass = ({ title, ...rest }) => <div {...rest}>{title}</div>\nexport default Pass")
        myFixture.configureByText(
            "Page.jsx",
            "import Dlg from './Dlg'\nimport Pass from './Pass'\nexport const P = () => <><Dlg title=\"x\" isOpen subtitle=\"y\" /><Pass subtitle=\"z\" /></>",
        )
        assertEquals(listOf("Dlg no tiene la prop 'subtitle'"), problems().filter { "no tiene la prop" in it })
    }

    // ---------- #1 Extraer texto a traducciones ----------

    private fun addTranslations() {
        myFixture.addFileToProject(
            "translate/es.json",
            "{\n\t\"transferPage\": {\n\t\t\"title\": \"Transferencia\",\n\t\t\"continuar\": \"Seguir\"\n\t},\n\t\"modalCancel\": {\n\t\t\"title\": \"Cancelar\"\n\t}\n}",
        )
        myFixture.addFileToProject("translate/en.json", "{\n\t\"transferPage\": {\n\t\t\"title\": \"Transfer\"\n\t},\n\t\"modalCancel\": {\n\t\t\"title\": \"Cancel\"\n\t}\n}")
    }

    private fun answer(key: String?) = TestDialogManager.setTestInputDialog(object : TestInputDialog {
        override fun show(message: String) = key
    })

    private fun json(name: String) =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("translate/$name"))!!.text

    fun testSuggestKey() {
        assertEquals("quieresEnviarEsaFotografia", TextExtraction.suggestKey("¿Quieres enviar esa fotografía?"))
        assertEquals("monto", TextExtraction.suggestKey("Monto"))
        assertEquals("identificacion", TextExtraction.suggestKey("Identificación"))
    }

    fun testExtractContentText() {
        addTranslations()
        answer("montoTotal")
        myFixture.configureByText(
            "Form.jsx",
            """
            export const Form = () => {
            	const { getLanguagePage } = useTranslateContext()
            	const { buttons } = getLanguagePage('modalCancel')
            	const { title } = getLanguagePage('transferPage')
            	return <div><span>Monto <caret>total</span> &nbsp;<b>{title}</b></div>
            }
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        val js = myFixture.editor.document.text
        println(js)
        assertTrue(js, js.contains("const { title, montoTotal } = getLanguagePage('transferPage')"))
        assertTrue(js, js.contains("<span>{montoTotal}</span>"))
        assertTrue("No toca modalCancel", js.contains("const { buttons } = getLanguagePage('modalCancel')"))
        assertTrue(json("es.json"), json("es.json").contains("\t\t\"continuar\": \"Seguir\",\n\t\t\"montoTotal\": \"Monto total\"\n\t}"))
        assertTrue(json("en.json"), json("en.json").contains("\t\t\"title\": \"Transfer\",\n\t\t\"montoTotal\": \"[ES] Monto total\"\n\t}"))
    }

    fun testExtractAttributeWithVariable() {
        addTranslations()
        answer("fecha")
        myFixture.configureByText(
            "Form.jsx",
            """
            export const Form = () => {
            	const texts = getLanguagePage('transferPage')
            	return <Field title="Fe<caret>cha" />
            }
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("<Field title={texts.fecha} />"))
        assertTrue(json("es.json").contains("\"fecha\": \"Fecha\""))
    }

    fun testEntityStaysOutsideAndCancelDoesNothing() {
        addTranslations()
        answer(null)
        val code = """
            export const Form = () => {
            	const { title } = getLanguagePage('transferPage')
            	return <button>Acep<caret>tar &nbsp;</button>
            }
        """.trimIndent()
        myFixture.configureByText("Form.jsx", code)
        assertEquals("Aceptar", TextExtraction.find(myFixture.file.findElementAt(myFixture.caretOffset)!!)?.text)
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        assertEquals("Cancelar el diálogo no cambia nada", code.replace("<caret>", ""), myFixture.editor.document.text)
    }

    fun testNotAvailableWithoutTranslations() {
        addTranslations()
        myFixture.configureByText("Plain.jsx", "export const Plain = () => <span>Mon<caret>to</span>")
        assertEmpty(myFixture.filterAvailableIntentions("Extraer a traducciones"))
    }

    fun testHardcodedTextInspection() {
        addTranslations()
        myFixture.enableInspections(HardcodedTextInspection())
        myFixture.configureByText(
            "Form.jsx",
            """
            export const Form = () => {
            	const { title } = getLanguagePage('transferPage')
            	return <div title="Detalle"><span>Monto total</span><b>{title}</b><i className="icon-x" /></div>
            }
            """.trimIndent(),
        )
        val found = problems().filter { it.startsWith("Texto fijo") }
        assertSameElements(found, "Texto fijo sin traducir: \"Monto total\"", "Texto fijo sin traducir: \"Detalle\"")
    }

    // ---------- Falsos avisos encontrados en el repo real ----------

    fun testStyledComponentsAreNotChecked() {
        myFixture.enableInspections(UnknownPropInspection())
        myFixture.configureByText(
            "S.jsx",
            "const MenuItemStyled = styled(MenuItem)(({ theme }) => ({ color: theme.x }))\nexport const P = () => <MenuItemStyled className=\"c\" />",
        )
        assertEmpty(problems().filter { "no tiene la prop" in it })
    }

    fun testComponentPropDisablesCheck() {
        mui("""<><Button component={Link} to="/home">x</Button><Button tex<caret>t="y" /></>""")
        assertEquals(listOf("Button no tiene la prop 'text'"), problems().filter { "no tiene la prop" in it })
    }

    fun testEntitiesAndTechnicalValuesAreNotText() {
        addTranslations()
        myFixture.enableInspections(HardcodedTextInspection())
        myFixture.configureByText(
            "Form.jsx",
            """
            export const Form = () => {
            	const { title } = getLanguagePage('transferPage')
            	return <div><b>{title}</b>&nbsp;<Typo variantTitle="body1" title="Pagar" /></div>
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Texto fijo sin traducir: \"Pagar\""), problems().filter { it.startsWith("Texto fijo") })
    }
}
