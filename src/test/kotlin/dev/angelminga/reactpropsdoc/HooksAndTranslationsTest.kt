package dev.angelminga.reactpropsdoc

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HooksAndTranslationsTest : BasePlatformTestCase() {

    private fun docAtCaret(): String? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }
            .also(::println)

    private fun problems() = myFixture.doHighlighting().filter { it.description != null }.map { it.description!! }
        .also { println("PROBLEMAS: $it") }

    // ---------- #1 Hooks ----------

    private val otpHook = """
        import { useState } from 'react'
        export const useOTPCreditPayment = ({ args }) => {
            const [dataOTP, setDataOTP] = useState(null)
            const addPayment = () => {}
            return { addPayment, dataOTP }
        }
    """.trimIndent()

    fun testHookDestructuringWarning() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject("useOTP.js", otpHook)
        myFixture.configureByText(
            "Confirm.jsx",
            """
            import { useOTPCreditPayment } from './useOTP'
            export const C = () => {
                const { registerBeneficiary, dataOTP, ...rest } = useOTPCreditPayment({ args: 1 })
                return dataOTP
            }
            """.trimIndent(),
        )
        val problems = problems()
        assertContainsElements(problems, "useOTPCreditPayment() no devuelve 'registerBeneficiary'. Devuelve: addPayment, dataOTP")
        assertFalse(problems.any { "'dataOTP'" in it || "'rest'" in it })
    }

    fun testNoHookWarningWhenReturnIsNotAnalyzable() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject(
            "useX.js",
            """
            export const useX = (flag) => {
                if (flag) return buildResult()
                return { a: 1 }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "C.jsx",
            "import { useX } from './useX'\nexport const C = () => { const { b } = useX(true); return b }",
        )
        assertFalse(problems().any { "no devuelve" in it })
    }

    // ---------- #2 Traducciones ----------

    private fun addTranslations() {
        myFixture.addFileToProject(
            "translate/es.json",
            """
            {
            	"internalTransferPage": {
            		"title": "Transferencia interna",
            		"stepLabels": { "FORM": "Ingreso de datos", "CONFIRM": "Confirmar" },
            		"button": "Continuar"
            	},
            	"loginPage": { "title": "Iniciar sesión" }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "translate/en.json",
            """
            {
            	"internalTransferPage": {
            		"title": "Internal transfer",
            		"stepLabels": { "FORM": "Data entry" }
            	}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "translate/ki.json",
            """
            {
            	"internalTransferPage": {
            		"title": "Ukulla kachay",
            		"stepLabels": { "FORM": "Willaykuna", "CONFIRM": "Kamachiy" },
            		"button": "Katiy"
            	},
            	"loginPage": { "title": "Yaykuy" }
            }
            """.trimIndent(),
        )
    }

    private fun page(body: String) = myFixture.configureByText(
        "Page.jsx",
        """
        import { useTranslateContext } from './ctx'
        export const P = () => {
            const { getLanguagePage } = useTranslateContext()
            $body
            return null
        }
        """.trimIndent(),
    )

    fun testPageCompletion() {
        addTranslations()
        page("const t = getLanguagePage('<caret>')")
        val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        println("VARIANTES: $variants")
        assertContainsElements(variants, "internalTransferPage", "loginPage")
    }

    fun testPageNavigatesToJson() {
        addTranslations()
        page("const t = getLanguagePage('internal<caret>TransferPage')")
        val target = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertInstanceOf(target, JsonProperty::class.java)
        assertEquals("es.json", target!!.containingFile.name)
    }

    fun testKeyCompletion() {
        addTranslations()
        page("const { title, <caret> } = getLanguagePage('internalTransferPage')")
        val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        println("VARIANTES: $variants")
        assertContainsElements(variants, "stepLabels", "button")
        assertDoesntContain(variants, "title")
    }

    fun testKeyHoverShowsAllLanguages() {
        addTranslations()
        page("const { title } = getLanguagePage('internalTransferPage')\n    console.log(ti<caret>tle)")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("ES:</td><td valign='top'>Transferencia interna"))
        assertTrue(doc, doc.contains("EN:</td><td valign='top'>Internal transfer"))
        assertTrue(doc, doc.contains("KI:</td><td valign='top'>Ukulla kachay"))
    }

    fun testKeyHoverShowsMissingLanguage() {
        addTranslations()
        page("const { but<caret>ton } = getLanguagePage('internalTransferPage')")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("EN:</td><td valign='top'>⚠ <b>falta</b>"))
    }

    fun testUnknownPageAndKeyWarnings() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page(
            """
            const a = getLanguagePage('locationPage')
                const { title, subtitle } = getLanguagePage('internalTransferPage')
            """.trimIndent(),
        )
        val problems = problems()
        assertContainsElements(
            problems,
            "La página 'locationPage' no existe en es.json",
            "'subtitle' no existe en la página 'internalTransferPage' de es.json",
        )
        assertFalse("title existe en todos los idiomas", problems.any { "'title'" in it })
    }

    fun testMissingKeyQuickFixCopiesToOtherLanguage() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page("const { but<caret>ton } = getLanguagePage('internalTransferPage')")
        assertContainsElements(problems(), "'button' de 'internalTransferPage' falta en EN")
        myFixture.launchAction(myFixture.findSingleIntention("Agregar 'button' a en.json"))
        val en = myFixture.findFileInTempDir("translate/en.json")
        // El cambio queda en el documento (en memoria) hasta que se guarda
        val text = FileDocumentManager.getInstance().getDocument(en)!!.text
        println(text)
        assertTrue(text, text.contains("\"button\": \"Continuar\""))
    }

    fun testTranslationFileInspectionAndFix() {
        addTranslations()
        myFixture.enableInspections(TranslationFileInspection())
        myFixture.configureFromTempProjectFile("translate/en.json")
        val problems = problems()
        assertContainsElements(
            problems,
            "Faltan páginas de es.json: loginPage",
            "Faltan 2 claves de es.json: stepLabels.CONFIRM, button",
        )
        myFixture.getAllQuickFixes().filter { it.text.startsWith("Agregar") }.forEach { myFixture.launchAction(it) }
        val text = myFixture.editor.document.text
        println(text)
        assertTrue(text, text.contains("\"FORM\": \"Data entry\", \"CONFIRM\": \"Confirmar\" }"))
        assertTrue("Respeta la indentación con tabs", text.contains("\n\t\t\"button\": \"Continuar\""))
        assertTrue("Respeta la indentación con tabs", text.contains("\n\t\"loginPage\": { \"title\": \"Iniciar sesión\" }"))
        assertEmpty(problems().filter { it.startsWith("Falta") })
    }
}
