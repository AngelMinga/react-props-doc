package dev.angelminga.reactpropsdoc

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** Lectura mínima de un comentario JSDoc: primera oración de la descripción y `@default`. */
data class JsDoc(val summary: String?, val defaultValue: String?) {

    companion object {
        private const val MAX_SUMMARY_LENGTH = 110

        fun of(element: PsiElement): JsDoc? {
            val comment = docComment(element) ?: return null
            val lines = comment.text
                .removePrefix("/**").removeSuffix("*/")
                .lines()
                .map { it.trim().removePrefix("*").trim() }

            val description = lines.takeWhile { !it.startsWith("@") }.filter { it.isNotEmpty() }.joinToString(" ")
            val defaultValue = lines.firstOrNull { it.startsWith("@default") }?.removePrefix("@default")?.trim()
                ?.takeIf { it.isNotEmpty() }
            return JsDoc(summarize(description), defaultValue)
        }

        private fun docComment(element: PsiElement): PsiComment? {
            (element.firstChild as? PsiComment)?.let { if (it.text.startsWith("/**")) return it }
            var prev = element.prevSibling
            while (prev is PsiWhiteSpace) prev = prev.prevSibling
            return (prev as? PsiComment)?.takeIf { it.text.startsWith("/**") }
        }

        /** Primera oración, sin enlaces markdown, recortada. */
        private fun summarize(text: String): String? {
            if (text.isBlank()) return null
            val plain = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            val sentence = Regex("^(.+?\\.)(\\s|$)").find(plain)?.groupValues?.get(1) ?: plain
            return if (sentence.length > MAX_SUMMARY_LENGTH) sentence.take(MAX_SUMMARY_LENGTH) + "…" else sentence
        }
    }
}
