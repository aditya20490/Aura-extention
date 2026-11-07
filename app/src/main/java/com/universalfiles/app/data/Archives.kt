package com.universalfiles.app.data

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

data class ArcEntry(val name: String, val size: Long, val dir: Boolean)

object Archives {
    fun kind(f: File): String {
        val n = f.name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> "tbz"
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> "txz"
            n.endsWith(".tar") -> "tar"
            n.endsWith(".7z") -> "7z"
            n.endsWith(".gz") -> "gz"
            n.endsWith(".bz2") -> "bz2"
            n.endsWith(".xz") -> "xz"
            n.endsWith(".rar") -> "rar"
            n.endsWith(".iso") || n.endsWith(".cab") || n.endsWith(".zst") -> "unsupported"
            else -> "zip"
        }
    }

    private fun single(f: File, k: String): InputStream {
        val raw = BufferedInputStream(FileInputStream(f))
        return when (k) {
            "gz" -> GzipCompressorInputStream(raw)
            "bz2" -> BZip2CompressorInputStream(raw)
            else -> XZCompressorInputStream(raw)
        }
    }

    private fun tarStream(f: File, k: String): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(f))
        val s: InputStream = when (k) {
            "tgz" -> GzipCompressorInputStream(raw)
            "tbz" -> BZip2CompressorInputStream(raw)
            "txz" -> XZCompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(s)
    }

    fun list(f: File): List<ArcEntry> {
        val out = ArrayList<ArcEntry>()
        when (val k = kind(f)) {
            "zip" -> FileOps.openZip(f).use { z ->
                for (e in java.util.Collections.list(z.entries())) out.add(ArcEntry(e.name, e.size, e.isDirectory))
            }
            "tar", "tgz", "tbz", "txz" -> tarStream(f, k).use { t ->
                var e = t.nextTarEntry
                while (e != null) {
                    out.add(ArcEntry(e.name, e.size, e.isDirectory))
                    e = t.nextTarEntry
                }
            }
            "7z" -> SevenZFile(f).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    out.add(ArcEntry(e.name, e.size, e.isDirectory))
                    e = z.nextEntry
                }
            }
            "gz", "bz2", "xz" -> out.add(ArcEntry(f.name.substringBeforeLast('.'), -1, false))
            "rar" -> throw IOException("RAR archives aren't supported (the RAR format is proprietary).")
            else -> throw IOException("This archive format isn't supported.")
        }
        return out
    }

    fun extractAll(f: File, dest: File): Int {
        dest.mkdirs()
        val base = dest.canonicalPath
        fun target(name: String): File? {
            val o = File(dest, name)
            val c = o.canonicalPath
            return if (c == base || c.startsWith(base + File.separator)) o else null
        }
        var n = 0
        when (val k = kind(f)) {
            "zip" -> return FileOps.unzip(f, dest)
            "tar", "tgz", "tbz", "txz" -> tarStream(f, k).use { t ->
                var e = t.nextTarEntry
                while (e != null) {
                    val o = target(e.name)
                    if (o != null) {
                        if (e.isDirectory) {
                            o.mkdirs()
                        } else {
                            o.parentFile?.mkdirs()
                            o.outputStream().use { s -> t.copyTo(s) }
                            n++
                        }
                    }
                    e = t.nextTarEntry
                }
            }
            "7z" -> SevenZFile(f).use { z ->
                val buf = ByteArray(65536)
                var e = z.nextEntry
                while (e != null) {
                    val o = target(e.name)
                    if (o != null) {
                        if (e.isDirectory) {
                            o.mkdirs()
                        } else {
                            o.parentFile?.mkdirs()
                            o.outputStream().use { s ->
                                while (true) {
                                    val r = z.read(buf)
                                    if (r < 0) break
                                    s.write(buf, 0, r)
                                }
                            }
                            n++
                        }
                    }
                    e = z.nextEntry
                }
            }
            "gz", "bz2", "xz" -> {
                val o = target(f.name.substringBeforeLast('.').ifEmpty { "output" })
                    ?: throw IOException("Bad file name")
                single(f, kind(f)).use { i -> o.outputStream().use { s -> i.copyTo(s) } }
                n = 1
            }
            else -> throw IOException("This archive format isn't supported.")
        }
        return n
    }

    fun extractEntry(f: File, name: String, out: File) {
        out.parentFile?.mkdirs()
        when (val k = kind(f)) {
            "zip" -> FileOps.openZip(f).use { z ->
                val e = z.getEntry(name) ?: throw IOException("Entry not found")
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            "tar", "tgz", "tbz", "txz" -> tarStream(f, k).use { t ->
                var e = t.nextTarEntry
                while (e != null) {
                    if (e.name == name) {
                        out.outputStream().use { o -> t.copyTo(o) }
                        return
                    }
                    e = t.nextTarEntry
                }
                throw IOException("Entry not found")
            }
            "7z" -> SevenZFile(f).use { z ->
                val buf = ByteArray(65536)
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == name) {
                        out.outputStream().use { o ->
                            while (true) {
                                val r = z.read(buf)
                                if (r < 0) break
                                o.write(buf, 0, r)
                            }
                        }
                        return
                    }
                    e = z.nextEntry
                }
                throw IOException("Entry not found")
            }
            "gz", "bz2", "xz" -> single(f, k).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            else -> throw IOException("This archive format isn't supported.")
        }
    }
}
