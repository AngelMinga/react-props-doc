package dev.angelminga.reactpropsdoc

import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
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
            .filter { it.isFile && ((it.extension in setOf("js", "jsx", "json") && "/src/" in it.path) || it.name == "jsconfig.json") }
            .toList()
        val allFiles = sources.map { myFixture.addFileToProject(it.relativeTo(root).path, it.readText()) }
        val files = allFiles.filter { it.name.endsWith(".js") || it.name.endsWith(".jsx") }

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
                        warnings += props.own.count { it.warning != null }
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

        // Inspecciones nuevas sobre todo el proyecto
        val context = InspectionManager.getInstance(project).createNewGlobalContext() as GlobalInspectionContext
        val tools = listOf(HookDestructuringInspection(), TranslationKeyInspection(), TranslationFileInspection())
        tools.forEach { tool ->
            val wrapper = LocalInspectionToolWrapper(tool)
            val found = allFiles.flatMap { file ->
                InspectionEngine.runInspectionOnFile(file, wrapper, context).map {
                    "${file.virtualFile.path.substringAfter("/src/")}: ${it.descriptionTemplate}"
                }
            }
            println("SMOKE ${tool.javaClass.simpleName}: ${found.size} problemas")
            found.take(40).forEach { println("SMOKE   $it") }
        }
    }

    private fun topLevelDeclarations(file: PsiFile): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, JSVariable::class.java).filter { PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == null } +
            PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java).filter { it.name != null && PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == null }
}
