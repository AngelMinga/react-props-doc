package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInsight.navigation.impl.fromGTDProviders
import com.intellij.psi.PsiElement
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Caso real: `btnLabelSecond={buttons.cancel}` con `buttons` desestructurado de `summary`. */
class TranslationChainTest : BasePlatformTestCase() {

    private fun addTranslations() {
        myFixture.addFileToProject(
            "translate/es.json",
            """
            {
            	"investmentPage": {
            		"summary": {
            			"rows": { "amount": "Monto" },
            			"debitAccount": "Cuenta",
            			"buttons": { "cancel": "Cancelar", "continue": "Cargar Documentos" }
            		},
            		"documents": { "title": "Documentos" },
            		"conditions": { "optionLabels": { "yes": "Sí" } },
            		"messages": { "defaultError": "Error" }
            	}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "translate/en.json",
            "{\n\t\"investmentPage\": {\n\t\t\"summary\": {\n\t\t\t\"buttons\": { \"cancel\": \"Cancel\" }\n\t\t}\n\t}\n}",
        )
    }

    /**
     * Como Ctrl+clic (`fromGTDProviders`): los GotoDeclarationHandler en orden, el primero que responde;
     * si ninguno responde, la resolución normal del IDE.
     */
    private fun gotoTargets(): List<PsiElement> {
        val source = myFixture.file.findElementAt(myFixture.caretOffset)
        val fromHandlers = GotoDeclarationHandler.EP_NAME.extensionList.firstNotNullOfOrNull { handler ->
            handler.getGotoDeclarationTargets(source, myFixture.caretOffset, myFixture.editor)?.takeIf { it.isNotEmpty() }
        }
        if (fromHandlers != null) {
            assertNotNull("Ctrl+clic usa los handlers", fromGTDProviders(project, myFixture.editor, myFixture.caretOffset))
            return fromHandlers.toList()
        }
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).toList()
    }

    private fun docAtCaret(): String =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }.orEmpty()

    private fun configure(code: String) {
        addTranslations()
        myFixture.configureByText(
            "SummaryStep.jsx",
            """
            export const SummaryStep = () => {
            	const { getLanguagePage } = useTranslateContext()
            	const { summary, documents, conditions: { optionLabels }, messages: { defaultError } } = getLanguagePage('investmentPage')
            	const { rows, debitAccount, buttons } = summary
            	$code
            }
            """.trimIndent(),
        )
    }

    fun testCtrlClickOnMemberAccessGoesToJson() {
        configure("return <ContainerButtons btnLabelSecond={buttons.can<caret>cel} />")
        val targets = gotoTargets()
        println("TARGETS: " + targets.map { "${it.javaClass.simpleName}@${it.containingFile?.name}" })
        val target = targets.single()
        assertInstanceOf(target, JsonProperty::class.java)
        assertEquals("cancel", (target as JsonProperty).name)
        assertEquals("es.json", target.containingFile.name)
    }

    fun testHoverOnMemberAccessShowsTranslations() {
        configure("return <ContainerButtons btnLabelSecond={buttons.can<caret>cel} />")
        val doc = docAtCaret()
        println(doc)
        assertTrue(doc, doc.contains("ES:</td><td valign='top'>Cancelar"))
        assertTrue(doc, doc.contains("EN:</td><td valign='top'>Cancel"))
    }

    fun testSecondLevelDestructuringVariable() {
        configure("return <C label={butt<caret>ons.cancel} />")
        // Sobre la variable suelta: Ctrl+clic sigue yendo a su declaración (no al JSON)
        assertFalse(gotoTargets().any { it is JsonProperty })
        // pero el hover muestra el objeto de traducciones
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("{ cancel, continue }"))
    }

    fun testCtrlClickOnSecondLevelDeclaration() {
        configure("const { rows: r, deb<caret>itAccount } = summary\n\treturn null")
        val target = gotoTargets().single()
        assertEquals("debitAccount", (target as JsonProperty).name)
    }

    fun testNestedDestructuringPath() {
        configure("return <C title={optionLabels.y<caret>es} />")
        val target = gotoTargets().single() as JsonProperty
        assertEquals("yes", target.name)
        assertEquals("optionLabels", (target.parent.parent as JsonProperty).name)
    }

    fun testUnknownKeyKeepsNormalBehaviour() {
        configure("return <C label={buttons.no<caret>pe} />")
        assertFalse(gotoTargets().any { it is JsonProperty })
        assertFalse(docAtCaret().contains("ES:"))
    }

    fun testExpressionPath() {
        configure("const x = buttons.cancel\n\treturn x")
        val path = Translations.expressionPath(
            myFixture.file.findElementAt(myFixture.file.text.indexOf("buttons.cancel") + 8)!!.parent as com.intellij.lang.javascript.psi.JSReferenceExpression,
        )
        assertEquals(listOf("investmentPage", "summary", "buttons", "cancel"), path)
    }
}
