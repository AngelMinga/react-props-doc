package dev.angelminga.reactpropsdoc

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReduxAndPolishTest : BasePlatformTestCase() {

    private fun docAtCaret(): String? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }
            .also(::println)

    private fun gotoTargets() = ReactPropsGotoDeclarationHandler()
        .getGotoDeclarationTargets(myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset, myFixture.editor)
        ?.toList().orEmpty()

    private fun problems() = myFixture.doHighlighting().filter { it.description != null }.map { it.description!! }

    // ---------- Redux ----------

    private fun addStore() {
        myFixture.addFileToProject(
            "redux/reducers/catalog.js",
            """
            import { fetchDataCatalog } from '../actions/catalog'
            const initialState = {
            	success: null,
            	loading: true,
            	data: {},
            	message: ''
            }
            const catalog = (state = initialState, action) => {
            	switch (action.type) {
            		case fetchDataCatalog:
            			return { ...state, data: action.payload.data }
            		case 'LOGOUT':
            			return initialState
            		default:
            			return state
            	}
            }
            export default catalog
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "redux/reducers/bankList.js",
            """
            import { createSlice } from '@reduxjs/toolkit'
            const bankListSlice = createSlice({ name: 'bankList', initialState: { items: [], selected: null }, reducers: {} })
            export default bankListSlice.reducer
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "redux/reducers/rootReducers.js",
            """
            import { combineReducers } from 'redux'
            import catalog from './catalog'
            import bankList from './bankList'
            export const rootReducers = combineReducers({ catalog, bankList })
            """.trimIndent(),
        )
    }

    private fun page(selector: String) = myFixture.configureByText(
        "Page.jsx",
        "import { useSelector } from 'react-redux'\nexport const P = () => { const x = useSelector($selector); return null }",
    )

    fun testCompletesSlices() {
        addStore()
        page("state => state.<caret>")
        assertContainsElements(myFixture.completeBasic()?.map { it.lookupString }.orEmpty(), "catalog", "bankList")
    }

    fun testCompletesInitialStateKeys() {
        addStore()
        page("(state) => state.catalog.<caret>")
        assertContainsElements(myFixture.completeBasic()?.map { it.lookupString }.orEmpty(), "success", "loading", "data", "message")
    }

    fun testCompletesCreateSliceState() {
        addStore()
        page("state => state.bankList.<caret>")
        assertContainsElements(myFixture.completeBasic()?.map { it.lookupString }.orEmpty(), "items", "selected")
    }

    fun testHoverShowsInitialStateAndActions() {
        addStore()
        page("state => state.cata<caret>log")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("<b>state.catalog</b>"))
        assertTrue(doc, doc.contains("Estado inicial:"))
        assertTrue(doc, doc.contains("<code>loading</code>: true"))
        assertTrue(doc, doc.contains("<code>fetchDataCatalog</code>"))
        assertTrue(doc, doc.contains("catalog.js"))
    }

    fun testHoverOnEmptyDataExplainsIt() {
        addStore()
        page("state => state.catalog.da<caret>ta")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("Valor inicial:"))
        assertTrue(doc, doc.contains("Empieza vacío"))
    }

    fun testGotoSliceAndKey() {
        addStore()
        page("state => state.cata<caret>log")
        val reducer = gotoTargets().single()
        assertInstanceOf(reducer, JSFunction::class.java)
        assertEquals("catalog.js", reducer.containingFile.name)

        page("state => state.catalog.load<caret>ing")
        val key = gotoTargets().single()
        assertInstanceOf(key, JSProperty::class.java)
        assertEquals("loading", (key as JSProperty).name)
    }

    fun testOutsideSelectorIsIgnored() {
        addStore()
        myFixture.configureByText("Page.jsx", "export const P = (state) => state.cata<caret>log")
        assertEmpty(gotoTargets())
        assertFalse(docAtCaret().orEmpty().contains("Estado inicial"))
    }

    // ---------- Ctrl+clic en una clave de traducción ----------

    fun testGotoTranslationKey() {
        myFixture.addFileToProject("translate/es.json", "{\n\t\"page\": {\n\t\t\"title\": \"Hola\"\n\t}\n}")
        myFixture.addFileToProject("translate/en.json", "{\n\t\"page\": {\n\t\t\"title\": \"Hello\"\n\t}\n}")
        myFixture.configureByText(
            "Form.jsx",
            "export const F = () => { const { ti<caret>tle } = getLanguagePage('page') || {}; return title }",
        )
        val target = gotoTargets().single()
        assertInstanceOf(target, JsonProperty::class.java)
        assertEquals("es.json", target.containingFile.name)
        assertEquals("title", (target as JsonProperty).name)
    }

    // ---------- Hooks que arman el resultado por partes ----------

    fun testHookResultBuiltByParts() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject(
            "useX.js",
            """
            export const useX = () => {
                const result = {}
                result.amount = 1
                result.save = () => {}
                return result
            }
            """.trimIndent(),
        )
        myFixture.configureByText("C.jsx", "import { useX } from './useX'\nexport const C = () => { const { amount, save, nope } = useX(); return amount }")
        assertEquals(listOf("useX() no devuelve 'nope'. Devuelve: amount, save"), problems().filter { "no devuelve" in it })
        assertEquals(listOf("amount", "save"), HookExtractor.findHook(myFixture.file.findElementAt(myFixture.file.text.indexOf("useX()"))?.parent?.let {
            (it as? com.intellij.lang.javascript.psi.JSReferenceExpression)?.resolve()
        })?.returns?.map { it.name })
    }

    fun testHookSpreadOfKnownObject() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject(
            "useY.js",
            "export const useY = () => {\n    const base = { x: 1 }\n    return { ...base, y: 2 }\n}",
        )
        myFixture.configureByText("C.jsx", "import { useY } from './useY'\nexport const C = () => { const { x, y, z } = useY(); return x }")
        assertEquals(listOf("useY() no devuelve 'z'. Devuelve: x, y"), problems().filter { "no devuelve" in it })
    }

    fun testHookSpreadOfUnknownObjectIsNotReported() {
        myFixture.enableInspections(HookDestructuringInspection())
        myFixture.addFileToProject("useZ.js", "export const useZ = (props) => {\n    return { ...props, y: 2 }\n}")
        myFixture.configureByText("C.jsx", "import { useZ } from './useZ'\nexport const C = () => { const { anything } = useZ({}); return anything }")
        assertEmpty(problems().filter { "no devuelve" in it })
    }

    // ---------- Omit en props heredadas ----------

    fun testOmittedKeys() {
        assertEquals(
            mapOf("PaperOwnProps" to setOf("classes"), "ModalProps" to setOf("children", "open")),
            ReactPropsExtractor.omittedKeys("extends DistributiveOmit<PaperOwnProps, 'classes'>, StandardProps<ModalProps, 'children' | \"open\">"),
        )
    }

    fun testOmitIsAppliedToInheritedProps() {
        myFixture.addFileToProject(
            "lib/Bar.d.ts",
            "export interface BarOwnProps {\n  /** @default false */\n  hidden?: boolean;\n  visible?: boolean;\n}\ndeclare const Bar: (props: BarOwnProps) => any;\nexport default Bar;",
        )
        myFixture.addFileToProject(
            "lib/Foo.d.ts",
            "import { BarOwnProps } from './Bar';\nexport interface FooOwnProps extends Omit<BarOwnProps, 'hidden'> {\n  label?: string;\n}\ndeclare const Foo: (props: FooOwnProps) => any;\nexport default Foo;",
        )
        myFixture.configureByText("Page.jsx", "import Foo from './lib/Foo'\nexport const P = () => <F<caret>oo />")
        val doc = docAtCaret().orEmpty()
        assertTrue(doc, doc.contains("Heredadas de Bar:"))
        assertTrue(doc, doc.contains("<code>visible</code>"))
        assertFalse("hidden está excluida por Omit", doc.contains("<code>hidden</code>"))
    }
}
