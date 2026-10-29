package dev.angelminga.reactpropsdoc

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SxTest : BasePlatformTestCase() {

    private fun page(jsx: String) = myFixture.configureByText("P.jsx", "import { Box } from '@mui/material'\nexport const P = () => $jsx")

    private fun completions() = myFixture.completeBasic()?.map { it.lookupString }.orEmpty().also { println("VARIANTES(${it.size}): ${it.take(15)}") }

    private fun docAtCaret(): String =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }.orEmpty()
            .also(::println)

    private fun problems() = myFixture.doHighlighting().filter { it.description != null }.map { it.description!! }

    fun testCompletesKeysExceptExisting() {
        page("<Box sx={{ position: 'relative', <caret> }} />")
        val variants = completions()
        assertContainsElements(variants, "cursor")
        assertDoesntContain(variants, "position")
    }

    fun testSingleKeyIsInserted() {
        page("<Box sx={{ position: 'relative', cur<caret> }} />")
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("position: 'relative', cursor }}"))
    }

    fun testCompletesCssAndMuiShortcutsInSx() {
        page("<Box sx={{ <caret> }} />")
        assertContainsElements(completions(), "position", "cursor", "p", "mt", "bgcolor", "borderRadius")
    }

    fun testStyleExcludesMuiShortcuts() {
        page("<div style={{ <caret> }} />")
        val variants = completions()
        assertContainsElements(variants, "position", "backgroundColor")
        assertDoesntContain(variants, "bgcolor", "p", "mt")
    }

    fun testCompletesValues() {
        page("<Box sx={{ position: '<caret>' }} />")
        assertSameElements(completions(), "absolute", "fixed", "relative", "static", "sticky")
    }

    fun testCompletesValuesWithPrefix() {
        page("<Box sx={{ cursor: 'poi<caret>' }} />")
        myFixture.completeBasic()
        // Una sola coincidencia: se inserta directamente
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("sx={{ cursor: 'pointer' }}"))
    }

    fun testHoverOnKeyShowsValues() {
        page("<Box sx={{ posi<caret>tion: 'relative', cursor: 'pointer' }} />")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("<b>position</b> (CSS)"))
        assertTrue(doc, doc.contains("<code>relative</code>"))
        assertTrue(doc, doc.contains("<code>sticky</code>"))
    }

    fun testHoverOnMuiShortcut() {
        page("<Box sx={{ bg<caret>color: 'primary.main' }} />")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("atajo de MUI"))
        assertTrue(doc, doc.contains("color del tema"))
    }

    fun testHoverOnSxAttribute() {
        page("<Box s<caret>x={{ position: 'relative' }} />")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("Atajos de MUI:"))
        assertTrue(doc, doc.contains("Responsive:"))
    }

    fun testHoverOnAttributeShowsTypesOfUsedKeys() {
        page("<Box s<caret>x={{ position: 'relative', marginTop: '5px', '&:hover': { color: 'red' }, bgColor: 'red' }} />")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("SxProps&lt;Theme&gt;"))
        assertTrue(doc, doc.contains("<code>position</code>: &#39;absolute&#39; | &#39;fixed&#39; | &#39;relative&#39;"))
        assertTrue(doc, doc.contains("= <code>&#39;relative&#39;</code>"))
        assertTrue(doc, doc.contains("<code>marginTop</code>: &#39;auto&#39; | número (px) o string &nbsp;"))
        assertTrue(doc, doc.contains("estilos anidados"))
        assertTrue(doc, doc.contains("<code>bgColor</code>: ⚠ clave desconocida"))
    }

    fun testHoverOnStyleShowsTypesAndVariable() {
        myFixture.configureByText(
            "S.jsx",
            "const estilos = { marginTop: '5px', mt: 2 }\nexport const S = () => <div st<caret>yle={estilos} />",
        )
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("React.CSSProperties"))
        assertTrue(doc, doc.contains("<code>marginTop</code>:"))
        assertTrue(doc, doc.contains("no funciona en style"))
    }

    fun testUnknownKeysAndFixes() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page(
            """<Box sx={{ posi<caret>ton: 'relative', '&:hover': { colr: 'red' }, p: { xs: 1, md: 2 }, WebkitBoxOrient: 'vertical', '@media (max-width:600px)': { mt: 1 } }} />""",
        )
        assertSameElements(problems().filter { "no reconoce" in it }, "sx no reconoce 'positon'", "sx no reconoce 'colr'")
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'position'"))
        assertTrue(myFixture.editor.document.text.contains("sx={{ position: 'relative'"))
    }

    fun testMuiShortcutInStyleIsReported() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page("""<div style={{ bgco<caret>lor: 'red', position: 'relative' }} />""")
        assertContainsElements(problems(), "'bgcolor' es un atajo de MUI: en style no funciona (usa sx o backgroundColor)")
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'backgroundColor'"))
        assertTrue(myFixture.editor.document.text.contains("style={{ backgroundColor: 'red'"))
    }

    fun testOtherAttributesAreIgnored() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page("""<Box data={{ positon: 1 }} slotProps={{ input: { foo: 1 } }} />""")
        assertEmpty(problems().filter { "no reconoce" in it })
    }

    // ---------- Falsos avisos encontrados en el repo real ----------

    fun testRealCssInStyleAndSelectorsAreAccepted() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page(
            """<>
                <div style={{ marginTop: 8, paddingRight: 4 }} />
                <Box sx={{ svg: { width: 20 }, td: { p: 1 }, 'th:first-of-type': { pl: 2 }, gridGap: 2, pageBreakInside: 'avoid', bgCo<caret>lor: 'red' }} />
                <Custom sx={{ theme, isSelected }} />
            </>""",
        )
        assertEquals(listOf("sx no reconoce 'bgColor'"), problems().filter { "no reconoce" in it || "atajo de MUI" in it })
        myFixture.launchAction(myFixture.findSingleIntention("Cambiar por 'bgcolor'"))
        assertTrue(myFixture.editor.document.text.contains("bgcolor: 'red'"))
    }

    fun testKeyframesPercentagesAreAccepted() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page("""<Box sx={{ '@keyframes pulse': { '0%': { opacity: 1 }, '100%': { opacity: 0 } }, animation: 'pulse 1s' }} />""")
        assertEmpty(problems().filter { "no reconoce" in it })
    }

    fun testCallArgumentsInsideSxAreNotStyles() {
        myFixture.enableInspections(SxUnknownKeyInspection())
        page(
            """<>
                <Box sx={getDayButtonSx({ theme, isSelected: true })} />
                <Box sx={{ transition: transitions.create(['color'], { duration: '0.5s' }), position: 'relative' }} />
            </>""",
        )
        assertEmpty(problems().filter { "no reconoce" in it })
    }
}
