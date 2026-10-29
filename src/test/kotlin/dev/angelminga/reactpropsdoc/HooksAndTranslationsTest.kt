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
            	"loginPage": { "title": "Iniciar sesión" },
            	"fingerInfo": "Da la vuelta a tu documento"
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
            	},
            	"fingerInfo": "Turn your ID card over"
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
            	"loginPage": { "title": "Yaykuy" },
            	"fingerInfo": "Tikray"
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
        assertTrue(text, text.contains("\"button\": \"[ES] Continuar\""))
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
        assertTrue(text, text.contains("\"FORM\": \"Data entry\", \"CONFIRM\": \"[ES] Confirmar\" }"))
        assertTrue("Respeta la indentación con tabs", text.contains("\n\t\t\"button\": \"[ES] Continuar\""))
        assertTrue("Respeta la indentación con tabs", text.contains("\n\t\"loginPage\": { \"title\": \"[ES] Iniciar sesión\" }"))
        assertEmpty(problems().filter { it.startsWith("Falta") })
    }

    // ---------- `|| {}` y aviso en la página ----------

    fun testFallbackOrEmptyObjectIsRecognized() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page("const { button, nope } = getLanguagePage('internalTransferPage') || {}")
        val problems = problems()
        assertContainsElements(
            problems,
            "'button' de 'internalTransferPage' falta en EN",
            "'nope' no existe en la página 'internalTransferPage' de es.json",
        )
    }

    fun testKeyCompletionWithFallback() {
        addTranslations()
        page("const { <caret> } = getLanguagePage('internalTransferPage') ?? {}")
        assertContainsElements(myFixture.completeBasic()?.map { it.lookupString }.orEmpty(), "title", "button")
    }

    fun testPageMissingInLanguageQuickFix() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page("const { title } = getLanguagePage('login<caret>Page') || {}")
        assertContainsElements(problems(), "La página 'loginPage' falta en EN")
        myFixture.launchAction(myFixture.findSingleIntention("Agregar la página 'loginPage' a en.json"))
        val en = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("translate/en.json"))!!.text
        assertTrue(en, en.contains("\"loginPage\": { \"title\": \"[ES] Iniciar sesión\" }"))
    }

    fun testPageWithMissingKeysQuickFix() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page("const t = getLanguagePage('internal<caret>TransferPage')")
        assertContainsElements(problems(), "A la página 'internalTransferPage' le faltan claves en EN")
        myFixture.launchAction(myFixture.findSingleIntention("Agregar las claves que faltan de 'internalTransferPage' a en.json"))
        val en = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("translate/en.json"))!!.text
        assertTrue(en, en.contains("\"CONFIRM\": \"[ES] Confirmar\""))
        assertTrue(en, en.contains("\"button\": \"[ES] Continuar\""))
    }

    fun testHoverExplainsHowToAddMissing() {
        addTranslations()
        page("const { but<caret>ton } = getLanguagePage('internalTransferPage') || {}")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("Cómo agregarla:"))
        assertTrue(doc, doc.contains("Agregar … a en.json"))
    }

    fun testHookDestructuringWithFallback() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject("useOTP.js", otpHook)
        myFixture.configureByText(
            "C.jsx",
            "import { useOTPCreditPayment } from './useOTP'\nexport const C = () => { const { nope } = useOTPCreditPayment({}) || {}; return nope }",
        )
        assertTrue(problems().any { "no devuelve 'nope'" in it })
    }

    fun testTextPageIsNotReportedAsMissing() {
        addTranslations()
        myFixture.enableInspections(TranslationKeyInspection())
        page("const text = getLanguagePage('fingerInfo')")
        assertFalse(problems().any { "fingerInfo" in it })
    }
}
