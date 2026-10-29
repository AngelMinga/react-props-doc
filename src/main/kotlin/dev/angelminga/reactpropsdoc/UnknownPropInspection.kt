package dev.angelminga.reactpropsdoc

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/** Props aceptadas por un componente, o null si no se puede saber con seguridad (entonces no se revisa). */
object AcceptedProps {

    /** Atributos válidos en elementos DOM de React (de @types/react). */
    val DOM_ATTRIBUTES: Set<String> by lazy {
        AcceptedProps::class.java.getResourceAsStream("/react-dom-attributes.txt")!!.bufferedReader()
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }

    private val ALWAYS = setOf("key", "ref")

    /** Props comunes que MUI acepta en todos sus componentes. */
    private val MUI_COMMON = setOf("component", "sx", "classes", "className", "style", "slots", "slotProps", "children")

    fun of(component: ReactComponent): Set<String>? =
        CachedValuesManager.getCachedValue(component.declaration) {
            CachedValueProvider.Result.create(compute(component), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun compute(component: ReactComponent): Set<String>? =
        if (component.file.name.endsWith(".d.ts")) library(component) else own(component)

    /** Componente propio: solo si se conocen todas las props que usa (sin `...rest` ni `{...props}`). */
    private fun own(component: ReactComponent): Set<String>? {
        val function = component.function ?: return null
        val usage = ReactPropsExtractor.propsUsage(function)
        if (!usage.complete) return null
        val declared = ReactPropsExtractor.propTypesObject(component)?.properties?.mapNotNull { it.name }.orEmpty()
        val defaults = ReactPropsExtractor.extractProps(component).own.map { it.name }
        return usage.props.keys + declared + defaults + ALWAYS
    }

    /** Componente de librería (.d.ts): todas sus interfaces de props, las heredadas y los atributos HTML. */
    private fun library(component: ReactComponent): Set<String>? {
        val names = ReactPropsExtractor.allLibraryPropNames(component) ?: return null
        return names + MUI_COMMON + ALWAYS + DOM_ATTRIBUTES
    }

    fun isAccepted(name: String, accepted: Set<String>) =
        name in accepted || name.startsWith("data-") || name.startsWith("aria-")
}

/** `<Button text="Buscar">`: prop que el componente no acepta (se ignora o termina en el DOM sin efecto). */
class UnknownPropInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val tag = element as? XmlTag ?: return
            if (tag.name.firstOrNull()?.isUpperCase() != true) return
            val target = tag.references.firstNotNullOfOrNull { it.resolve() } ?: return
            val component = ReactPropsExtractor.findComponent(target) ?: return
            // `component={Link}`: MUI acepta también las props de ese componente (no se puede revisar)
            if (tag.getAttribute("component") != null || tag.getAttribute("as") != null) return
            val accepted = AcceptedProps.of(component) ?: return
            val suggestions = ReactPropsExtractor.extractProps(component).own.map { it.name }

            tag.attributes.forEach { attribute ->
                val name = attribute.name
                if (AcceptedProps.isAccepted(name, accepted)) return@forEach
                val similar = suggestions
                    .map { it to levenshtein(it.lowercase(), name.lowercase()) }
                    .filter { (_, distance) -> distance <= maxOf(2, name.length / 3) }
                    .sortedBy { it.second }
                    .take(3)
                    .mapIndexed { index, (candidate, _) -> RenamePropFix(candidate, index) }
                val fixes = buildList<LocalQuickFix> {
                    addAll(similar)
                    if (MoveToChildrenFix.isApplicable(tag, attribute, accepted)) add(MoveToChildrenFix())
                    add(RemovePropFix())
                }
                holder.registerProblem(
                    attribute.nameElement ?: attribute,
                    "${component.name} no tiene la prop '$name'",
                    *fixes.toTypedArray(),
                )
            }
        }
    }
}

class RenamePropFix(private val newName: String, private val rank: Int) : LocalQuickFix, PriorityAction {
    override fun getFamilyName() = "Cambiar por una prop del componente"
    override fun getName() = "Cambiar por '$newName'"
    override fun getPriority() = if (rank == 0) PriorityAction.Priority.HIGH else PriorityAction.Priority.NORMAL

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val attribute = descriptor.psiElement.parent as? XmlAttribute ?: descriptor.psiElement as? XmlAttribute ?: return
        attribute.name = newName
    }
}

class RemovePropFix : LocalQuickFix {
    override fun getFamilyName() = "Quitar la prop"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val attribute = descriptor.psiElement.parent as? XmlAttribute ?: descriptor.psiElement as? XmlAttribute ?: return
        attribute.delete()
    }
}

/** `<Button text="Buscar" />` → `<Button>Buscar</Button>` (si el componente acepta children y no tiene contenido). */
class MoveToChildrenFix : LocalQuickFix {
    override fun getFamilyName() = "Usar el texto como contenido (children)"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val attribute = descriptor.psiElement.parent as? XmlAttribute ?: descriptor.psiElement as? XmlAttribute ?: return
        val tag = attribute.parent ?: return
        val value = attribute.value ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(tag.containingFile) ?: return
        val tagRange = tag.textRange
        // Reconstruye el tag sin el atributo y con el texto como contenido
        val attributeRange = attribute.textRange
        val before = document.getText(com.intellij.openapi.util.TextRange(tagRange.startOffset, attributeRange.startOffset)).trimEnd()
        val after = document.getText(com.intellij.openapi.util.TextRange(attributeRange.endOffset, tagRange.endOffset))
        val opening = (before + after).trimEnd().removeSuffix("/>").trimEnd()
        document.replaceString(tagRange.startOffset, tagRange.endOffset, "$opening>$value</${tag.name}>")
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {
        private val TEXT_PROPS = setOf("text", "label", "title", "value", "content")

        fun isApplicable(tag: XmlTag, attribute: XmlAttribute, accepted: Set<String>): Boolean =
            attribute.name in TEXT_PROPS && "children" in accepted && tag.getAttribute("children") == null &&
                tag.value.text.isBlank() && tag.text.trimEnd().endsWith("/>") &&
                attribute.valueElement?.text?.startsWith("\"") == true
    }
}
