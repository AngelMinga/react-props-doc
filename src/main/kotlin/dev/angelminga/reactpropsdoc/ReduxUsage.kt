package dev.angelminga.reactpropsdoc

import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/** Árbol de rutas usadas en el código a partir de `useSelector`: `catalog → data → entity → colorPrimary`. */
class UsageNode {
    val children = sortedMapOf<String, UsageNode>()
    var uses = 0
        private set

    fun add(path: List<String>) {
        var node = this
        for (name in path) {
            node = node.children.getOrPut(name) { UsageNode() }
            node.uses++
        }
    }

    fun at(path: List<String>): UsageNode? = path.fold(this as UsageNode?) { node, name -> node?.children?.get(name) }
}

/**
 * Forma real del estado deducida de cómo se usa: el estado inicial suele ser `data: {}` y su contenido llega
 * del backend, así que se recorren todos los `useSelector` y lo que se lee de su resultado.
 */
object ReduxUsage {

    private const val MAX_DEPTH = 6

    fun observed(project: Project): UsageNode =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(collect(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun collect(project: Project): UsageNode {
        val root = UsageNode()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord("useSelector", GlobalSearchScope.projectScope(project), { file ->
            if ("/node_modules/" in (file.virtualFile?.path ?: "")) return@processAllFilesWithWord true
            PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java).forEach { call ->
                val path = ReduxState.selectorCallPath(call) ?: return@forEach
                root.add(path)
                followUsages(call, path, root, 0)
            }
            true
        }, true)
        return root
    }

    /** Registra lo que se lee del valor `expr` (que está en `path`): `x.a.b`, `const { a } = x`, `const y = x`. */
    private fun followUsages(expr: PsiElement, path: List<String>, root: UsageNode, depth: Int) {
        if (depth > MAX_DEPTH) return
        var current = expr
        // `useSelector(...) || {}`, `(x)`
        while (current.parent is JSParenthesizedExpression ||
            (current.parent as? JSBinaryExpression)?.let { it.lOperand == current && it.operationNode?.text in setOf("||", "??") } == true
        ) current = current.parent

        when (val parent = current.parent) {
            is JSReferenceExpression -> if (parent.qualifier == current) {
                val name = parent.referenceName ?: return
                root.add(path + name)
                followUsages(parent, path + name, root, depth + 1)
            }
            is JSVariable -> if (parent.initializer == current) followVariable(parent, path, root, depth)
            is JSDestructuringElement -> if (parent.initializer == current) {
                (parent.target as? JSDestructuringObject)?.let { followDestructuring(it, path, root, depth) }
            }
        }
    }

    private fun followDestructuring(obj: JSDestructuringObject, path: List<String>, root: UsageNode, depth: Int) {
        obj.properties.forEach { property ->
            if (property.text.startsWith("...")) return@forEach
            val name = property.name ?: return@forEach
            root.add(path + name)
            // `{ data }` → variable; `{ data: { entity } }` → objeto anidado (envuelto en un elemento sin inicializador)
            val nested = PsiTreeUtil.getChildOfType(property, JSDestructuringElement::class.java)?.target as? JSDestructuringObject
                ?: PsiTreeUtil.getChildOfType(property, JSDestructuringObject::class.java)
            if (nested != null) followDestructuring(nested, path + name, root, depth + 1)
            else (property.destructuringElement as? JSVariable)?.let { followVariable(it, path + name, root, depth + 1) }
        }
    }

    private fun followVariable(variable: JSVariable, path: List<String>, root: UsageNode, depth: Int) {
        if (depth > MAX_DEPTH) return
        ReferencesSearch.search(variable, LocalSearchScope(variable.containingFile)).findAll().forEach { ref ->
            followUsages(ref.element, path, root, depth + 1)
        }
    }

    /** Resumen de una rama: `entity (158): colorPrimary, multimedia, …`. */
    fun describe(node: UsageNode, max: Int = 15): List<String> =
        node.children.entries.sortedByDescending { it.value.uses }.take(max).map { (name, child) ->
            val sub = child.children.keys.take(6).joinToString(", ") + if (child.children.size > 6) ", …" else ""
            "$name (${child.uses} usos)" + if (sub.isNotEmpty()) ": { $sub }" else ""
        }
}
