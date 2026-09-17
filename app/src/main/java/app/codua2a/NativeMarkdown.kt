package app.codua2a

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Small native renderer: headings, lists, emphasis, links and fenced code. */
object NativeMarkdown {
    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        var fenced = false
        val inline = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|\\[[^\\]]+\\]\\(https?://[^\\s)]+\\))")
        source.split('\n').forEachIndexed { index, original ->
            if (index > 0) out.append('\n')
            if (original.trimStart().startsWith("```")) { fenced = !fenced; return@forEachIndexed }
            val start = out.length
            if (fenced) {
                out.append(original)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val heading = Regex("^(#{1,6}) +").find(original)
                val line = (if (heading != null) original.substring(heading.value.length) else original)
                    .replace(Regex("^\\s*[-*] +"), "• ")
                var cursor = 0
                inline.findAll(line).forEach { match ->
                    out.append(line.substring(cursor, match.range.first))
                    val begin = out.length
                    val token = match.value
                    val span = when {
                        token.startsWith("`") -> { out.append(token.substring(1, token.length - 1)); TypefaceSpan("monospace") }
                        token.startsWith("**") -> { out.append(token.substring(2, token.length - 2)); StyleSpan(Typeface.BOLD) }
                        token.startsWith("*") -> { out.append(token.substring(1, token.length - 1)); StyleSpan(Typeface.ITALIC) }
                        else -> {
                            val split = token.indexOf("](")
                            out.append(token.substring(1, split)); URLSpan(token.substring(split + 2, token.length - 1))
                        }
                    }
                    out.setSpan(span, begin, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cursor = match.range.last + 1
                }
                out.append(line.substring(cursor))
                if (heading != null && out.length > start) {
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(if (heading.groupValues[1].length < 3) 1.2f else 1.05f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }
}
