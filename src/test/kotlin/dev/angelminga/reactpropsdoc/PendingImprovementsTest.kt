package dev.angelminga.reactpropsdoc

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PendingImprovementsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun docAtCaret(): String =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }.orEmpty()
            .also(::println)

    private fun completions() = myFixture.completeBasic()?.map { it.lookupString }.orEmpty().also { println("VARIANTES: $it") }

    // ---------- Redux: forma real de `data` deducida de los usos ----------

    private fun addStoreAndUsages() {
        myFixture.addFileToProject(
            "redux/catalog.js",
            "const initialState = { success: null, loading: true, data: {}, message: '' }\n" +
                "const catalog = (state = initialState, action) => state\nexport default catalog",
        )
        myFixture.addFileToProject("redux/root.js", "import { combineReducers } from 'redux'\nimport catalog from './catalog'\nexport const root = combineReducers({ catalog })")
        myFixture.addFileToProject(
            "Header.jsx",
            """
            import { useSelector } from 'react-redux'
            export const Header = () => {
            	const catalogState = useSelector(state => state.catalog)
            	return <h1 style={{ color: catalogState.data.entity.colorPrimary }}>{catalogState.data.entity.name}</h1>
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Footer.jsx",
            """
            import { useSelector } from 'react-redux'
            export const Footer = () => {
            	const { data } = useSelector((state) => state.catalog) || {}
            	const { entity: { multimedia } } = data
            	return <img src={multimedia[0].url} />
            }
            """.trimIndent(),
        )
    }

    fun testHoverShowsObservedShapeOfData() {
        addStoreAndUsages()
        myFixture.configureByText("P.jsx", "import { useSelector } from 'react-redux'\nexport const P = () => useSelector(state => state.catalog.da<caret>ta)")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("Usado en el código:"))
        assertTrue(doc, doc.contains("<code>entity</code>"))
        assertTrue(doc, doc.contains("colorPrimary"))
        assertTrue(doc, doc.contains("multimedia"))
    }

    fun testCompletionOnVariableFromUseSelector() {
        addStoreAndUsages()
        myFixture.configureByText(
            "P.jsx",
            "import { useSelector } from 'react-redux'\nexport const P = () => { const c = useSelector(state => state.catalog); return c.data.entity.<caret> }",
        )
        assertSameElements(completions(), "colorPrimary", "name", "multimedia")
    }

    fun testCompletionMixesInitialStateAndObserved() {
        addStoreAndUsages()
        myFixture.configureByText(
            "P.jsx",
            "import { useSelector } from 'react-redux'\nexport const P = () => { const c = useSelector(state => state.catalog); return c.<caret> }",
        )
        assertSameElements(completions(), "success", "loading", "data", "message")
    }

    fun testHoverAndGotoOnVariableFromUseSelector() {
        addStoreAndUsages()
        myFixture.configureByText(
            "P.jsx",
            "import { useSelector } from 'react-redux'\nexport const P = () => { const c = useSelector(state => state.catalog); return c.load<caret>ing }",
        )
        assertTrue(docAtCaret().contains("<b>state.catalog.loading</b>"))
        val target = ReactPropsGotoDeclarationHandler()
            .getGotoDeclarationTargets(myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset, myFixture.editor)!!.single()
        assertEquals("loading", (target as JSProperty).name)
    }

    // ---------- Redux: selectores definidos aparte ----------

    fun testSelectorDefinedApart() {
        addStoreAndUsages()
        myFixture.addFileToProject("selectors.js", "export const selectCatalog = (state) => state.catalog\nexport const selectData = state => state.catalog.data")
        myFixture.configureByText(
            "P.jsx",
            "import { useSelector } from 'react-redux'\nimport { selectCatalog } from './selectors'\nexport const P = () => { const c = useSelector(selectCatalog); return c.<caret> }",
        )
        assertContainsElements(completions(), "loading", "data")
    }

    fun testCompletionInsideSelectorDefinedApart() {
        addStoreAndUsages()
        myFixture.configureByText("selectors.js", "export const selectCatalog = (state) => state.<caret>")
        assertContainsElements(completions(), "catalog")
    }

    // ---------- Extraer texto en componentes sin getLanguagePage ----------

    private fun addTranslations() {
        myFixture.addFileToProject("translate/es.json", "{\n\t\"investmentPage\": {\n\t\t\"title\": \"Inversión\"\n\t},\n\t\"modalCancel\": {\n\t\t\"title\": \"Cancelar\"\n\t}\n}")
        myFixture.addFileToProject("translate/en.json", "{\n\t\"investmentPage\": {\n\t\t\"title\": \"Investment\"\n\t},\n\t\"modalCancel\": {\n\t\t\"title\": \"Cancel\"\n\t}\n}")
    }

    /** Respuestas en orden para los diálogos (página, clave). */
    private fun answers(vararg values: String) {
        val queue = ArrayDeque(values.toList())
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String) = queue.removeFirstOrNull()
            override fun show(message: String, validator: InputValidator?) = show(message)
        })
    }

    private fun json(name: String) =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("translate/$name"))!!.text

    fun testExtractInComponentWithoutTranslations() {
        addTranslations()
        myFixture.addFileToProject("pages/Investment/components/Other.jsx", "import { useTranslateContext } from 'MFComponents/ComponentUseTranslateContext'\nconst x = getLanguagePage('investmentPage')")
        answers("investmentPage", "resumen")
        myFixture.configureByText(
            "Summary.jsx",
            """
            import React from 'react'

            export const Summary = () => {
            	const total = 1
            	return <span>Res<caret>umen</span>
            }
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        val js = myFixture.editor.document.text
        println(js)
        assertTrue(js, js.contains("import React from 'react'\nimport { useTranslateContext } from 'MFComponents/ComponentUseTranslateContext'"))
        assertTrue(js, js.contains("export const Summary = () => {\n\tconst { getLanguagePage } = useTranslateContext()\n\tconst { resumen } = getLanguagePage('investmentPage')\n\n\tconst total = 1"))
        assertTrue(js, js.contains("<span>{resumen}</span>"))
        assertTrue(json("es.json"), json("es.json").contains("\"resumen\": \"Resumen\""))
        assertTrue(json("en.json"), json("en.json").contains("\"resumen\": \"[ES] Resumen\""))
    }

    fun testExtractReusesExistingTranslateContext() {
        addTranslations()
        answers("investmentPage", "resumen")
        myFixture.configureByText(
            "Summary.jsx",
            """
            export const Summary = () => {
            	const { languageState } = useTranslateContext()
            	return <span>Res<caret>umen</span>
            }
            """.trimIndent(),
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        val js = myFixture.editor.document.text
        assertTrue(js, js.contains("const { languageState, getLanguagePage } = useTranslateContext()\n\tconst { resumen } = getLanguagePage('investmentPage')"))
    }

    fun testExtractCreatesNewPage() {
        addTranslations()
        answers("summaryPage", "resumen")
        myFixture.configureByText("Summary.jsx", "export const Summary = () => {\n\treturn <span>Res<caret>umen</span>\n}")
        myFixture.launchAction(myFixture.findSingleIntention("Extraer a traducciones"))
        assertTrue(json("es.json"), json("es.json").contains("\"summaryPage\": { \"resumen\": \"Resumen\" }"))
        assertTrue(json("en.json"), json("en.json").contains("\"summaryPage\": { \"resumen\": \"[ES] Resumen\" }"))
    }

    fun testSuggestPageFromFolder() {
        addTranslations()
        val file = myFixture.addFileToProject("pages/Investment/components/Summary.jsx", "export const S = () => { return <b>x</b> }")
        assertEquals("investmentPage", TextExtraction.suggestPage(file, Translations.files(project)!!))
    }
}
