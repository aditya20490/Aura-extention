package com.universalfiles.app.data

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Auto-indent and auto-close while typing. */
object Smart {
    private val VOID = setOf("br", "img", "input", "meta", "link", "hr", "area", "base", "col", "embed", "source", "track", "wbr")
    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private val TAGNAME = Regex("^[A-Za-z][\\w:-]*")

    fun transform(
        old: TextFieldValue, new: TextFieldValue, lang: Lang,
        indent: String, autoIndent: Boolean, autoClose: Boolean
    ): TextFieldValue {
        val t = new.text
        if (t.length != old.text.length + 1) return new
        if (!old.selection.collapsed || !new.selection.collapsed) return new
        val cur = new.selection.start
        if (cur < 1 || cur - 1 != old.selection.start) return new
        val ch = t[cur - 1]
        val next = if (cur < t.length) t[cur] else '\u0000'

        if (ch == '\n') {
            if (!autoIndent) return new
            val ls = t.lastIndexOf('\n', cur - 2) + 1
            val line = t.substring(ls, cur - 1)
            val base = line.takeWhile { it == ' ' || it == '\t' }
            val last = line.trimEnd().lastOrNull()
            val deeper = last == '{' || last == '[' || last == '(' || (last == ':' && lang == Lang.PYTHON)
            val closer = when (last) {
                '{' -> '}'
                '[' -> ']'
                '(' -> ')'
                else -> null
            }
            val before = t.substring(0, cur)
            val after = t.substring(cur)
            val ins = base + (if (deeper) indent else "")
            return if (deeper && closer != null && next == closer) {
                TextFieldValue(before + ins + "\n" + base + after, TextRange(before.length + ins.length))
            } else {
                TextFieldValue(before + ins + after, TextRange(before.length + ins.length))
            }
        }

        if (!autoClose || lang == Lang.NONE) return new
        if (next == ch && (ch == ')' || ch == ']' || ch == '}' || ch == '"' || ch == '\'' || ch == '`')) {
            return TextFieldValue(old.text, TextRange(cur))
        }

        if (lang == Lang.HTML || lang == Lang.XML) {
            val lt = t.lastIndexOf('<', cur - 2)
            val inTag = lt >= 0 && t.lastIndexOf('>', cur - 2) < lt
            if (ch == '>' && inTag) {
                val inner = t.substring(lt + 1, cur - 1)
                val name = TAGNAME.find(inner)?.value
                if (name != null && !inner.endsWith("/") && (lang == Lang.XML || name.lowercase() !in VOID)) {
                    return TextFieldValue(t.substring(0, cur) + "</" + name + ">" + t.substring(cur), TextRange(cur))
                }
            }
            if ((ch == '"' || ch == '\'') && inTag && !next.isLetterOrDigit()) {
                return TextFieldValue(t.substring(0, cur) + ch + t.substring(cur), TextRange(cur))
            }
            return new
        }

        val closer = PAIRS[ch] ?: return new
        val prev = if (cur >= 2) t[cur - 2] else ' '
        val quote = ch == '"' || ch == '\'' || ch == '`'
        if (quote && (prev.isLetterOrDigit() || prev == '\\')) return new
        if (next.isLetterOrDigit()) return new
        return TextFieldValue(t.substring(0, cur) + closer + t.substring(cur), TextRange(cur))
    }
}

object Csv {
    fun parse(text: String, d: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        q = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> q = true
                    d -> {
                        row.add(sb.toString())
                        sb.setLength(0)
                    }
                    '\n' -> {
                        row.add(sb.toString())
                        sb.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    '\r' -> {}
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            rows.add(row)
        }
        return rows
    }

    fun write(rows: List<List<String>>, d: Char): String =
        rows.joinToString("\n") { r ->
            r.joinToString(d.toString()) { c ->
                if (c.any { it == d || it == '"' || it == '\n' || it == '\r' }) {
                    "\"" + c.replace("\"", "\"\"") + "\""
                } else {
                    c
                }
            }
        } + "\n"
}

object JsonFmt {
    fun pretty(src: String, indent: String): String {
        val sb = StringBuilder()
        var lvl = 0
        var inStr = false
        var esc = false
        fun nl() {
            sb.append('\n')
            repeat(lvl) { sb.append(indent) }
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                sb.append(c)
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> {
                        inStr = true
                        sb.append(c)
                    }
                    '{', '[' -> {
                        var j = i + 1
                        while (j < src.length && src[j].isWhitespace()) j++
                        if (j < src.length && (src[j] == '}' || src[j] == ']')) {
                            sb.append(c).append(src[j])
                            i = j
                        } else {
                            sb.append(c)
                            lvl++
                            nl()
                        }
                    }
                    '}', ']' -> {
                        lvl = maxOf(0, lvl - 1)
                        nl()
                        sb.append(c)
                    }
                    ',' -> {
                        sb.append(c)
                        nl()
                    }
                    ':' -> sb.append(": ")
                    else -> if (!c.isWhitespace()) sb.append(c)
                }
            }
            i++
        }
        return sb.toString() + "\n"
    }

    fun minify(src: String): String {
        val sb = StringBuilder()
        var inStr = false
        var esc = false
        for (c in src) {
            if (inStr) {
                sb.append(c)
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else if (c == '"') {
                inStr = true
                sb.append(c)
            } else if (!c.isWhitespace()) {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}

object Markdown {
    private val H = Regex("^(#{1,6})\\s+(.*)")
    private val UL = Regex("^[-*+]\\s+(.*)")
    private val OL = Regex("^\\d+[.)]\\s+(.*)")
    private val HR = Regex("^(?:-{3,}|\\*{3,}|_{3,})\\z")
    private val CODE = Regex("`([^`]+)`")
    private val IMG = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITAL = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(raw: String): String {
        var s = esc(raw)
        s = CODE.replace(s) { "<code>" + it.groupValues[1] + "</code>" }
        s = IMG.replace(s) { "<img alt=\"" + it.groupValues[1] + "\" src=\"" + it.groupValues[2] + "\">" }
        s = LINK.replace(s) { "<a href=\"" + it.groupValues[2] + "\">" + it.groupValues[1] + "</a>" }
        s = BOLD.replace(s) { "<b>" + it.groupValues[1] + "</b>" }
        s = ITAL.replace(s) { "<i>" + it.groupValues[1] + "</i>" }
        return s
    }

    fun toHtml(md: String): String {
        val out = StringBuilder()
        out.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        out.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
        out.append(":root{color-scheme:light dark}body{font-family:sans-serif;max-width:800px;margin:auto;padding:16px;line-height:1.6}")
        out.append("pre{background:rgba(128,128,128,.15);padding:12px;overflow:auto;border-radius:6px}")
        out.append("code{background:rgba(128,128,128,.15);padding:1px 4px;border-radius:4px}pre code{background:none;padding:0}")
        out.append("blockquote{border-left:4px solid #888;margin:0;padding-left:12px;color:#888}img{max-width:100%}")
        out.append("</style></head><body>")
        var inCode = false
        var list: String? = null
        fun closeList() {
            if (list != null) {
                out.append("</").append(list).append(">")
                list = null
            }
        }
        for (line in md.lines()) {
            if (line.trimStart().startsWith("```")) {
                closeList()
                out.append(if (inCode) "</code></pre>" else "<pre><code>")
                inCode = !inCode
                continue
            }
            if (inCode) {
                out.append(esc(line)).append('\n')
                continue
            }
            val t = line.trim()
            val h = H.find(t)
            val ul = UL.find(t)
            val ol = OL.find(t)
            when {
                t.isEmpty() -> closeList()
                h != null -> {
                    closeList()
                    val n = h.groupValues[1].length
                    out.append("<h").append(n).append(">").append(inline(h.groupValues[2])).append("</h").append(n).append(">")
                }
                HR.containsMatchIn(t) -> {
                    closeList()
                    out.append("<hr>")
                }
                ul != null || ol != null -> {
                    val tag = if (ul != null) "ul" else "ol"
                    if (list != tag) {
                        closeList()
                        out.append("<").append(tag).append(">")
                        list = tag
                    }
                    out.append("<li>").append(inline((ul ?: ol)!!.groupValues[1])).append("</li>")
                }
                t.startsWith(">") -> {
                    closeList()
                    out.append("<blockquote>").append(inline(t.removePrefix(">").trim())).append("</blockquote>")
                }
                else -> {
                    closeList()
                    out.append("<p>").append(inline(t)).append("</p>")
                }
            }
        }
        closeList()
        if (inCode) out.append("</code></pre>")
        out.append("</body></html>")
        return out.toString()
    }
}
