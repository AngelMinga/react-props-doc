package dev.angelminga.reactpropsdoc

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class QuickFixesTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            ReactPropsDocSettings.options.markCopiedTranslations = true
        } finally {
            super.tearDown()
        }
    }

    private fun problems() = myFixture.doHighlighting().filter { it.description != null }.map { it.description!! }

    // ---------- Generar propTypes ----------

    fun testTypeInference() {
        val cases = mapOf(
            ("isReturn" to null) to "bool",
            ("maskData" to null) to "bool",
            ("handleCancel" to null) to "func",
            ("onClick" to null) to "func",
            ("setValue" to null) to "func",
            ("children" to null) to "node",
            ("dataAccount" to null) to "object",
            ("messageTimeTransfer" to null) to "string",
            ("title" to null) to "string",
            ("optionsList" to null) to "array",
            ("maxWidth" to null) to "number",
            ("rare" to null) to "any",
            ("rare" to "0") to "number",
            ("rare" to "'x'") to "string",
            ("rare" to "false") to "bool",
            ("rare" to "[]") to "array",
            ("rare" to "() => {}") to "func",
        )
        cases.forEach { (input, expected) ->
            assertEquals("$input", expected, PropTypeInference.infer(input.first, input.second))
        }
    }

    private val creditResume = """
        import React from 'react'
        import PropTypes from 'prop-types'

        const CreditResume = ({ dataAccount, maskData, isReturn = false }) => {
        	return <div>{maskData}{isReturn}</div>
        }

        CreditResume.propTypes = {
        	dataAccount: PropTypes.object.isRequired
        }

        export default CreditResume
    """.trimIndent()

    fun testAddSingleUndeclaredProp() {
        myFixture.enableInspections(UndeclaredPropInspection())
        myFixture.configureByText("CreditResume.jsx", creditResume.replace("{ dataAccount, maskData,", "{ dataAccount, mask<caret>Data,"))
        assertContainsElements(problems(), "'maskData' no está declarada en CreditResume.propTypes")
        myFixture.launchAction(myFixture.findSingleIntention("Agregar 'maskData' a propTypes"))
        val text = myFixture.editor.document.text
        println(text)
        assertTrue(text, text.contains("CreditResume.propTypes = {\n\tdataAccount: PropTypes.object.isRequired,\n\tmaskData: PropTypes.bool\n}"))
    }

    fun testAddAllUndeclaredProps() {
        myFixture.enableInspections(UndeclaredPropInspection())
        myFixture.configureByText("CreditResume.jsx", creditResume.replace("{ dataAccount, maskData,", "{ dataAccount, mask<caret>Data,"))
        myFixture.launchAction(myFixture.findSingleIntention("Agregar las 2 props que faltan a propTypes"))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\tdataAccount: PropTypes.object.isRequired,\n\tmaskData: PropTypes.bool,\n\tisReturn: PropTypes.bool\n}"))
        assertEmpty(problems().filter { "no está declarada" in it })
    }

    fun testAddPropAfterTrailingComma() {
        myFixture.enableInspections(UndeclaredPropInspection())
        myFixture.configureByText(
            "Otp.jsx",
            """
            import PropTypes from 'prop-types'
            const Otp = props => {
                const { openOtp, processOtp } = props
                return <div>{openOtp}{proc<caret>essOtp}</div>
            }
            Otp.propTypes = {
                openOtp: PropTypes.bool.isRequired,
            }
            export default Otp
            """.trimIndent().replace("{ openOtp, processOtp }", "{ openOtp, process<caret>Otp }").replace("{proc<caret>essOtp}", "{processOtp}"),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Agregar 'processOtp' a propTypes"))
        val text = myFixture.editor.document.text
        println(text)
        assertTrue(text, text.contains("    openOtp: PropTypes.bool.isRequired,\n    processOtp: PropTypes.any,\n}"))
    }

    fun testPropsAccessIsAnchoredOnUsage() {
        myFixture.enableInspections(UndeclaredPropInspection())
        myFixture.configureByText(
            "Card2.jsx",
            """
            import PropTypes from 'prop-types'
            const Card2 = props => <div>{props.title}{props.foo<caret>terText}</div>
            Card2.propTypes = { title: PropTypes.string }
            export default Card2
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Agregar 'footerText' a propTypes"))
        assertTrue(myFixture.editor.document.text.contains("Card2.propTypes = { title: PropTypes.string, footerText: PropTypes.string }"))
    }

    fun testGeneratePropTypesIntention() {
        myFixture.configureByText(
            "ChipStatus.jsx",
            """
            import React from 'react'
            import { Chip } from '@mui/material'

            const Chip<caret>Status = ({ text, color, onClick, disabled = false, ...rest }) => {
            	return <Chip label={text} {...rest} />
            }

            export default ChipStatus
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Generar propTypes para ChipStatus (4 props)"))
        val text = myFixture.editor.document.text
        println(text)
        assertTrue(text, text.contains("import { Chip } from '@mui/material'\nimport PropTypes from 'prop-types'\n"))
        assertTrue(
            text,
            text.contains(
                "ChipStatus.propTypes = {\n\ttext: PropTypes.string,\n\tcolor: PropTypes.string,\n" +
                    "\tonClick: PropTypes.func,\n\tdisabled: PropTypes.bool\n}\n\nexport default ChipStatus",
            ),
        )
    }

    fun testGenerateIntentionNotOfferedWhenPropTypesExist() {
        myFixture.configureByText("CreditResume.jsx", creditResume.replace("const CreditResume", "const Credit<caret>Resume"))
        assertEmpty(myFixture.filterAvailableIntentions("Generar propTypes"))
    }

    // ---------- Alt+Enter en el aviso de hooks ----------

    private val otpHook = """
        export const useOTPCreditPayment = ({ args }) => {
            const dataOTP = null
            const addPayment = () => {}
            const registerBeneficiaryOTP = () => {}
            return { addPayment, dataOTP, registerBeneficiaryOTP }
        }
    """.trimIndent()

    fun testReplaceHookValueRenamesUsages() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject("useOTP.js", otpHook)
        myFixture.configureByText(
            "Confirm.jsx",
            """
            import { useOTPCreditPayment } from './useOTP'
            export const C = ({ isOTP }) => {
                const { register<caret>Beneficiary, dataOTP } = useOTPCreditPayment({ args: 1 })
                return <button onClick={isOTP ? null : () => registerBeneficiary({ otp: '' })}>{dataOTP}</button>
            }
            """.trimIndent(),
        )
        val fixes = myFixture.availableIntentions.filter { it.text.startsWith("Cambiar por") }
        println(fixes.map { it.text })
        assertEquals("El nombre más parecido va primero", "Cambiar por 'registerBeneficiaryOTP'", fixes.first().text)
        assertDoesntContain(fixes.map { it.text }, "Cambiar por 'dataOTP'")
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'registerBeneficiaryOTP'"))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("const { registerBeneficiaryOTP, dataOTP } = useOTPCreditPayment"))
        assertTrue(text, text.contains("() => registerBeneficiaryOTP({ otp: '' })"))
        assertEmpty(problems().filter { "no devuelve" in it })
    }

    fun testReplaceHookValueKeepsAlias() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject("useOTP.js", otpHook)
        myFixture.configureByText(
            "Confirm.jsx",
            """
            import { useOTPCreditPayment } from './useOTP'
            export const C = () => {
                const { regis<caret>ter: save } = useOTPCreditPayment({ args: 1 })
                return save()
            }
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'addPayment'"))
        assertTrue(myFixture.editor.document.text.contains("const { addPayment: save } = useOTPCreditPayment"))
    }

    // ---------- Prefijo [ES] ----------

    private fun addTranslations() {
        myFixture.addFileToProject("translate/es.json", "{\n\t\"page\": {\n\t\t\"title\": \"Hola\",\n\t\t\"form\": { \"name\": \"Nombre\" }\n\t}\n}")
        myFixture.addFileToProject("translate/en.json", "{\n\t\"page\": {\n\t\t\"title\": \"Hello\"\n\t}\n}")
    }

    fun testCopiedTextIsMarkedAndReportedAsUntranslated() {
        addTranslations()
        myFixture.enableInspections(TranslationFileInspection())
        myFixture.configureFromTempProjectFile("translate/en.json")
        myFixture.getAllQuickFixes().filter { it.text.startsWith("Agregar") }.forEach { myFixture.launchAction(it) }
        val text = myFixture.editor.document.text
        println(text)
        assertTrue("Solo los textos llevan el prefijo, no las claves", text.contains("\"form\": { \"name\": \"[ES] Nombre\" }"))
        assertContainsElements(problems(), "Texto sin traducir (copiado de es.json)")
    }

    fun testMarkerCanBeDisabled() {
        ReactPropsDocSettings.options.markCopiedTranslations = false
        addTranslations()
        myFixture.enableInspections(TranslationFileInspection())
        myFixture.configureFromTempProjectFile("translate/en.json")
        myFixture.getAllQuickFixes().filter { it.text.startsWith("Agregar") }.forEach { myFixture.launchAction(it) }
        val text = FileDocumentManager.getInstance().getDocument(myFixture.file.virtualFile)!!.text
        assertTrue(text, text.contains("\"form\": { \"name\": \"Nombre\" }"))
    }
}
