package dev.angelminga.reactpropsdoc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonObject
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * `getLanguagePage('página')`: el literal referencia la página en el JSON base
 * (Ctrl+clic navega al JSON y el autocompletado sugiere las páginas).
 */
class TranslationReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? JSLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    if (!literal.isQuotedLiteral || !Translations.isPageLiteral(literal)) return PsiReference.EMPTY_ARRAY
                    return arrayOf(TranslationPageReference(literal))
                }
            },
        )
    }
}

class TranslationPageReference(literal: JSLiteralExpression) :
    PsiReferenceBase<JSLiteralExpression>(literal, TextRange(1, maxOf(1, literal.textLength - 1)), true) {

    override fun resolve(): PsiElement? {
        val page = element.stringValue ?: return null
        return Translations.files(element.project)?.root(baseLang())?.findProperty(page)
    }

    override fun getVariants(): Array<Any> =
        Translations.files(element.project)?.pages().orEmpty().map { page ->
            val keys = (page.value as? JsonObject)?.propertyList?.size ?: 0
            LookupElementBuilder.create(page.name).withTypeText("$keys claves")
        }.toTypedArray()

    private fun baseLang() = Translations.files(element.project)?.baseLang ?: "ES"
}

/** `const { <caret> } = getLanguagePage('página')`: sugiere las claves de la página con su texto. */
class TranslationKeyCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val (obj, page) = Translations.destructuringOf(parameters.position) ?: return
                // Solo dentro de `{ … }`, no en el literal de la página
                if (!PsiTreeUtil.isAncestor(obj, parameters.position, false)) return
                val files = Translations.files(parameters.position.project) ?: return
                val pageObject = files.page(files.baseLang, page) ?: return
                val existing = obj.properties.mapNotNull { it.name }.toSet()
                pageObject.propertyList.filter { it.name !in existing }.forEach { property ->
                    result.addElement(
                        LookupElementBuilder.create(property.name)
                            .withTypeText(Translations.preview(property.value, 40))
                            .withTailText("  ($page)", true),
                    )
                }
            }
        })
    }
}
