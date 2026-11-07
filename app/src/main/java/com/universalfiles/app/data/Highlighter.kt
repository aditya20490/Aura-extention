package com.universalfiles.app.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private enum class K { KW, STR, NUM, COM, TAG, ATTR }

private class Hit(val s: Int, val e: Int, val c: Color)

private class Pal(val kw: Color, val str: Color, val num: Color, val com: Color, val tag: Color, val attr: Color)

private class Lexer(rules: List<Pair<String, K>>) {
    val kinds: List<K> = rules.map { it.second }
    val rx = Regex(rules.joinToString("|") { "(" + it.first + ")" })
}

object Highlighter {
    const val MAX_CHARS = 60_000

    private val PAL_L = Pal(
        Color(0xFF8E24AA), Color(0xFFA31515), Color(0xFF098658),
        Color(0xFF6A737D), Color(0xFF0B5CAD), Color(0xFF795E26)
    )
    private val PAL_D = Pal(
        Color(0xFFC586C0), Color(0xFFCE9178), Color(0xFFB5CEA8),
        Color(0xFF6A9955), Color(0xFF4EC9B0), Color(0xFF9CDCFE)
    )

    private const val DQ = "\"(?:[^\"\\\\\\n]|\\\\.)*\"?"
    private const val SQ = "'(?:[^'\\\\\\n]|\\\\.)*'?"
    private const val BQ = "`(?:[^`\\\\]|\\\\[\\s\\S])*`?"
    private const val BLOCK = "/\\*[\\s\\S]*?(?:\\*/|\\z)"
    private const val SLASH = "//[^\\n]*"
    private const val HASH = "#[^\\n]*"
    private const val DASH = "--[^\\n]*"
    private const val NUM = "\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b"

    private const val JS_KW = "const let var function return if else for while do switch case break continue new this class extends import export from default async await try catch finally throw typeof instanceof in of null undefined true false void delete yield static super"
    private const val C_KW = "class interface fun val var void int long float double boolean bool char byte short string String public private protected static final abstract override import package return if else for while do switch case break continue new this super try catch finally throw throws null true false in is as when object data enum struct const let func use mod impl fn pub match self nil namespace using typedef include extends implements async await"
    private const val PY_KW = "def class return if elif else for while in not and or is import from as with try except finally raise pass break continue lambda yield None True False global nonlocal assert del async await self print"
    private const val SQL_KW = "select from where insert into values update set delete create table drop alter add index view join inner left right outer full on group by order having limit offset as and or not null is in like between distinct union all case when then else end primary key foreign references default unique check constraint if exists begin commit rollback transaction count sum avg min max asc desc int integer text varchar real blob boolean date timestamp"
    private const val SH_KW = "if then else elif fi for while do done case esac function return echo exit export local in"

    private fun kw(words: String, ic: Boolean = false): String {
        val core = "\\b(?:" + words.trim().replace(" ", "|") + ")\\b"
        return if (ic) "(?i:$core)" else core
    }

    private val LEXERS: Map<Lang, Lexer> = mapOf(
        Lang.JS to Lexer(listOf(BLOCK to K.COM, SLASH to K.COM, BQ to K.STR, DQ to K.STR, SQ to K.STR, NUM to K.NUM, kw(JS_KW) to K.KW)),
        Lang.CLIKE to Lexer(listOf(BLOCK to K.COM, SLASH to K.COM, DQ to K.STR, SQ to K.STR, "@[A-Za-z_]\\w*" to K.ATTR, NUM to K.NUM, kw(C_KW) to K.KW)),
        Lang.CSS to Lexer(listOf(
            BLOCK to K.COM, DQ to K.STR, SQ to K.STR, "@[A-Za-z-]+" to K.KW,
            "#[0-9a-fA-F]{3,8}\\b" to K.NUM, "[.#][A-Za-z_][\\w-]*" to K.TAG,
            "[A-Za-z-]+(?=\\s*:)" to K.ATTR,
            "(?<![\\w#-])-?\\d*\\.?\\d+(?:px|em|rem|%|vh|vw|ms|s|deg|fr)?" to K.NUM
        )),
        Lang.JSON to Lexer(listOf(
            BLOCK to K.COM, SLASH to K.COM,
            "\"(?:[^\"\\\\\\n]|\\\\.)*\"(?=\\s*:)" to K.ATTR, DQ to K.STR, NUM to K.NUM, kw("true false null") to K.KW
        )),
        Lang.PYTHON to Lexer(listOf(
            "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)" to K.STR, "'''[\\s\\S]*?(?:'''|\\z)" to K.STR,
            HASH to K.COM, DQ to K.STR, SQ to K.STR, NUM to K.NUM, kw(PY_KW) to K.KW
        )),
        Lang.SQL to Lexer(listOf(DASH to K.COM, BLOCK to K.COM, SQ to K.STR, DQ to K.STR, NUM to K.NUM, kw(SQL_KW, true) to K.KW)),
        Lang.SHELL to Lexer(listOf(HASH to K.COM, DQ to K.STR, SQ to K.STR, "\\$\\{?[A-Za-z_]\\w*\\}?" to K.ATTR, kw(SH_KW) to K.KW)),
        Lang.YAML to Lexer(listOf(
            HASH to K.COM, DQ to K.STR, SQ to K.STR,
            "(?m:^[ \\t]*-?[ \\t]*[\\w.\\-/]+(?=[ \\t]*:))" to K.ATTR, NUM to K.NUM, kw("true false null yes no") to K.KW
        )),
        Lang.INI to Lexer(listOf(
            "(?m:^[ \\t]*[;#][^\\n]*)" to K.COM, "(?m:^[ \\t]*\\[[^\\]\\n]*\\])" to K.TAG,
            "(?m:^[ \\t]*[\\w.\\-]+(?=[ \\t]*=))" to K.ATTR, DQ to K.STR, SQ to K.STR, NUM to K.NUM
        )),
        Lang.MARKDOWN to Lexer(listOf(
            "(?s:```.*?(?:```|\\z))" to K.STR, "(?m:^#{1,6} [^\\n]*)" to K.KW,
            "`[^`\\n]+`" to K.STR, "\\*\\*[^*\\n]+\\*\\*" to K.ATTR,
            "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)" to K.TAG, "(?m:^[ \\t]*(?:[-*+]|\\d+\\.)(?= ))" to K.NUM
        ))
    )

    private val MARKUP = Regex("(<!--[\\s\\S]*?-->)|(</?[A-Za-z!?][^<>]*>)")
    private val TAGNAME = Regex("^</?[!?]?[A-Za-z][\\w:-]*")
    private val ATTR = Regex("[A-Za-z_:@][\\w:.\\-]*(?=\\s*=)")
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'")

    private fun col(p: Pal, k: K): Color = when (k) {
        K.KW -> p.kw
        K.STR -> p.str
        K.NUM -> p.num
        K.COM -> p.com
        K.TAG -> p.tag
        K.ATTR -> p.attr
    }

    fun build(text: String, lang: Lang, isDark: Boolean): AnnotatedString {
        if (lang == Lang.NONE || text.length > MAX_CHARS) return AnnotatedString(text)
        val p = if (isDark) PAL_D else PAL_L
        val hits = ArrayList<Hit>()
        if (lang == Lang.HTML || lang == Lang.XML) {
            for (m in MARKUP.findAll(text)) {
                val a = m.range.first
                if (m.groups[1] != null) {
                    hits.add(Hit(a, m.range.last + 1, p.com))
                    continue
                }
                val s = m.value
                TAGNAME.find(s)?.let { hits.add(Hit(a, a + it.value.length, p.tag)) }
                val quotes = QUOTED.findAll(s).map { it.range }.toList()
                for (x in ATTR.findAll(s)) {
                    if (quotes.none { x.range.first in it }) hits.add(Hit(a + x.range.first, a + x.range.last + 1, p.attr))
                }
                for (q in quotes) hits.add(Hit(a + q.first, a + q.last + 1, p.str))
            }
        } else {
            val lx = LEXERS[lang] ?: return AnnotatedString(text)
            for (m in lx.rx.findAll(text)) {
                for (i in lx.kinds.indices) {
                    if (m.groups[i + 1] != null) {
                        hits.add(Hit(m.range.first, m.range.last + 1, col(p, lx.kinds[i])))
                        break
                    }
                }
            }
        }
        return buildAnnotatedString {
            append(text)
            for (h in hits) addStyle(SpanStyle(color = h.c), h.s, h.e)
        }
    }
}

class HighlightTransformation(private val lang: Lang, private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(Highlighter.build(text.text, lang, dark), OffsetMapping.Identity)
}
