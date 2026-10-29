package dev.angelminga.reactpropsdoc

import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Prueba de humo contra un proyecto real: carga todos sus .js/.jsx y ejecuta la extracción en cada
 * componente y hook de primer nivel. Solo corre si se define REACT_PROPS_DOC_PROJECT (ruta al proyecto).
 */
class RealProjectSmokeTest : BasePlatformTestCase() {

    fun testRealProject() {
        val root = System.getenv("REACT_PROPS_DOC_PROJECT")?.let(::File) ?: return
        val sources = root.walkTopDown()
            .onEnter { it.name !in setOf("node_modules", "dist", "build", ".git") }
            .filter { it.isFile && it.extension in setOf("js", "jsx") && "/src/" in it.path }
            .toList()
        val files = sources.map { myFixture.addFileToProject(it.relativeTo(root).path, it.readText()) }

        var components = 0
        var withProps = 0
        var warnings = 0
        var hooks = 0
        var hooksWithReturns = 0
        val failures = mutableListOf<String>()

        files.forEach { file ->
            topLevelDeclarations(file).forEach { element ->
                try {
                    ReactPropsExtractor.findComponent(element)?.let { component ->
                        components++
                        val props = ReactPropsExtractor.extractProps(component)
                        if (!props.isEmpty()) withProps++
                        props.own.filter { it.warning != null }.forEach {
                            warnings++
                            println("SMOKE aviso ${file.virtualFile.path.substringAfter("/src/")} ${component.name}.${it.name}: ${it.warning}")
                        }
                    }
                    HookExtractor.findHook(element)?.let {
                        hooks++
                        if (it.returns.isNotEmpty()) hooksWithReturns++
                    }
                } catch (e: Throwable) {
                    failures += "${file.virtualFile.path}: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }

        println("SMOKE archivos=${files.size} componentes=$components conProps=$withProps avisos=$warnings hooks=$hooks hooksConReturn=$hooksWithReturns errores=${failures.size}")
        failures.take(20).forEach { println("SMOKE error: $it") }
        assertEmpty(failures)
    }

    private fun topLevelDeclarations(file: PsiFile): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, JSVariable::class.java).filter { PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == null } +
            PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java).filter { it.name != null && PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == null }
}
