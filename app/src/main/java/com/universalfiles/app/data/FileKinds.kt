package com.universalfiles.app.data

import android.webkit.MimeTypeMap
import java.io.File

const val MAX_EDIT_BYTES = 2L * 1024 * 1024

enum class FileKind { FOLDER, TEXT, PDF, IMAGE, AUDIO, VIDEO, ARCHIVE, OTHER }

enum class Lang { NONE, HTML, XML, CSS, JS, CLIKE, JSON, PYTHON, SQL, MARKDOWN, YAML, INI, SHELL }

object FileKinds {
    private val textExt = setOf(
        "txt", "md", "markdown", "html", "htm", "xhtml", "css", "scss", "less", "js", "mjs", "cjs",
        "jsx", "tsx", "json", "jsonc", "geojson", "xml", "xsd", "xsl", "xslt", "svg", "plist", "rss",
        "yaml", "yml", "toml", "ini", "env", "conf", "cfg", "properties", "csv", "tsv", "sql", "py",
        "pyw", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "php", "rb",
        "swift", "dart", "lua", "sh", "bash", "zsh", "bat", "ps1", "log", "tex", "gradle", "groovy",
        "scala", "gitignore", "gitattributes", "editorconfig", "vcf", "ics", "eml", "m3u", "m3u8",
        "pls", "xspf", "url", "srt", "vtt", "diff", "patch", "rst", "dockerfile", "makefile", "r",
        "pl", "vue", "svelte", "lock", "csproj", "sln", "manifest"
    )
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "ico", "dng")
    private val audioExt = setOf(
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "amr", "mid", "midi",
        "aiff", "aif", "mka", "ac3", "dts", "ape", "wv"
    )
    private val videoExt = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "mpeg", "mpg", "m4v", "3gp",
        "mts", "m2ts", "vob", "ogv", "asf", "rmvb", "divx", "mxf"
    )
    private val archiveExt = setOf(
        "zip", "jar", "war", "ear", "apk", "apks", "xapk", "aab", "docx", "xlsx", "pptx", "odt",
        "ods", "odp", "epub", "gz", "tgz", "tar", "bz2", "xz", "7z", "rar", "zst", "cab", "iso"
    )

    fun ext(f: File): String = f.name.substringAfterLast('.', "").lowercase()

    fun detect(f: File, sniff: Boolean = true): FileKind {
        if (f.isDirectory) return FileKind.FOLDER
        val e = ext(f)
        return when {
            e == "pdf" -> FileKind.PDF
            e in imageExt -> FileKind.IMAGE
            e in audioExt -> FileKind.AUDIO
            e in videoExt -> FileKind.VIDEO
            e in archiveExt -> FileKind.ARCHIVE
            e == "ts" -> if (!sniff || looksLikeText(f)) FileKind.TEXT else FileKind.VIDEO
            e in textExt -> FileKind.TEXT
            e.isEmpty() || sniff -> if (sniff && looksLikeText(f)) FileKind.TEXT else FileKind.OTHER
            else -> FileKind.OTHER
        }
    }

    fun looksLikeText(f: File): Boolean {
        return try {
            f.inputStream().use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n <= 0) return true
                for (i in 0 until n) if (buf[i] == 0.toByte()) return false
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun language(name: String): Lang {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xhtml", "vue", "svelte" -> Lang.HTML
            "xml", "xsd", "xsl", "xslt", "svg", "plist", "rss", "csproj" -> Lang.XML
            "css", "scss", "less" -> Lang.CSS
            "js", "mjs", "cjs", "ts", "jsx", "tsx" -> Lang.JS
            "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "dart",
            "php", "gradle", "groovy", "scala" -> Lang.CLIKE
            "json", "jsonc", "geojson" -> Lang.JSON
            "py", "pyw" -> Lang.PYTHON
            "sql" -> Lang.SQL
            "md", "markdown" -> Lang.MARKDOWN
            "yaml", "yml" -> Lang.YAML
            "ini", "toml", "properties", "conf", "cfg", "env", "gitignore" -> Lang.INI
            "sh", "bash", "zsh", "ps1" -> Lang.SHELL
            else -> Lang.NONE
        }
    }

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f)) ?: "*/*"
}
