package com.universalfiles.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class Place(val name: String, val path: String, val kind: String)

object Storage {
    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    fun workspace(ctx: Context): File =
        if (hasAccess(ctx)) File(primaryRoot, "UniversalFiles")
        else (ctx.getExternalFilesDir(null) ?: ctx.filesDir)

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        out.add(Place("Internal storage", primaryRoot.absolutePath, "storage"))
        ContextCompat.getExternalFilesDirs(ctx, null).drop(1).filterNotNull().forEach { d ->
            val root = d.absolutePath.substringBefore("/Android/data")
            if (File(root).exists()) out.add(Place("SD card / USB", root, "sd"))
        }
        return out
    }

    fun quick(ctx: Context): List<Place> {
        val list = ArrayList<Place>()
        list.add(Place("Projects", workspace(ctx).absolutePath, "projects"))
        val common = listOf(
            Triple("Downloads", "Download", "download"),
            Triple("Documents", "Documents", "documents"),
            Triple("Pictures", "Pictures", "pictures"),
            Triple("Camera", "DCIM", "camera"),
            Triple("Music", "Music", "music"),
            Triple("Movies", "Movies", "movies")
        )
        for ((n, d, k) in common) {
            val f = File(primaryRoot, d)
            if (f.isDirectory) list.add(Place(n, f.absolutePath, k))
        }
        ctx.getExternalFilesDir(null)?.let { list.add(Place("App storage", it.absolutePath, "app")) }
        return list
    }
}

object Fmt {
    fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }

    fun date(ms: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
}

object FileOps {
    fun uniqueName(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val c = File(dir, "$base ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }

    fun copyTo(src: File, target: File) {
        if (src.isDirectory) {
            val sc = src.canonicalPath
            val tc = target.canonicalPath
            if (tc == sc || tc.startsWith(sc + File.separator)) {
                throw IOException("Can't copy a folder into itself")
            }
            target.mkdirs()
            src.listFiles()?.forEach { copyTo(it, File(target, it.name)) }
        } else {
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = false)
        }
    }

    fun copyInto(src: File, dir: File): File {
        val t = uniqueName(dir, src.name)
        copyTo(src, t)
        return t
    }

    fun move(src: File, dir: File): File {
        if (src.parentFile?.canonicalPath == dir.canonicalPath) return src
        val t = uniqueName(dir, src.name)
        if (src.renameTo(t)) return t
        copyTo(src, t)
        src.deleteRecursively()
        return t
    }

    fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    fun zip(sources: List<File>, out: File) {
        val outCanon = out.canonicalPath
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            fun add(f: File, prefix: String) {
                val name = prefix + f.name
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$name/"))
                    zos.closeEntry()
                    f.listFiles()?.forEach { add(it, "$name/") }
                } else {
                    if (f.canonicalPath == outCanon) return
                    val e = ZipEntry(name)
                    e.time = f.lastModified()
                    zos.putNextEntry(e)
                    f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    fun openZip(f: File): ZipFile =
        try {
            ZipFile(f)
        } catch (e: Exception) {
            ZipFile(f, Charset.forName("ISO-8859-1"))
        }

    fun unzip(zip: File, destDir: File): Int {
        destDir.mkdirs()
        var count = 0
        val destCanon = destDir.canonicalPath
        openZip(zip).use { zf ->
            for (e in java.util.Collections.list(zf.entries())) {
                val out = File(destDir, e.name)
                val oc = out.canonicalPath
                if (oc != destCanon && !oc.startsWith(destCanon + File.separator)) continue
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    if (e.time > 0) out.setLastModified(e.time)
                    count++
                }
            }
        }
        return count
    }

    fun gunzip(src: File, out: File) {
        GZIPInputStream(src.inputStream().buffered()).use { i ->
            out.outputStream().use { o -> i.copyTo(o) }
        }
    }

    fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun hash(f: File, algo: String): String {
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { i ->
            val b = ByteArray(65536)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object FileActions {
    fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    fun share(ctx: Context, files: List<File>) {
        try {
            val uris = ArrayList(files.map { uriFor(ctx, it) })
            val i = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = FileKinds.mime(files[0])
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't share: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun openWith(ctx: Context, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(ctx, f), FileKinds.mime(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}

object Templates {
    private val HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>My Page</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <h1>Hello</h1>
  <script src="script.js"></script>
</body>
</html>
""".trimIndent() + "\n"

    fun content(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> HTML
        "css" -> "* {\n  box-sizing: border-box;\n}\n\nbody {\n  margin: 0;\n  font-family: sans-serif;\n}\n"
        "js" -> "'use strict';\n\nconsole.log('Hello');\n"
        "json" -> "{\n  \n}\n"
        "csv" -> "id,name,value\n1,Example,100\n"
        "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n  \n</root>\n"
        "sql" -> "-- Query\nSELECT * FROM table_name;\n"
        "md" -> "# Title\n\nWrite here.\n"
        "py" -> "def main():\n    print(\"Hello\")\n\n\nif __name__ == \"__main__\":\n    main()\n"
        "yml", "yaml" -> "key: value\n"
        "sh" -> "#!/bin/sh\n\n"
        else -> ""
    }
}
