package com.docscan.app

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt

enum class TargetKind { STORAGE, APP, CHOOSER }

class ShareTarget(val label: String, val icon: ImageBitmap?, val component: ComponentName)

object Exporter {

    /**
     * toStorage = true  -> saved to the phone's public folders (MediaStore)
     * toStorage = false -> written only to the app's private cache and shared via FileProvider
     */
    fun export(
        ctx: Context, pages: List<ScanPage>, s: ExportSpec,
        toStorage: Boolean, onProgress: (Int) -> Unit
    ): List<Uri> {
        if (!toStorage) File(ctx.cacheDir, "share").deleteRecursively()
        val (pw, ph, _) = pixelsFor(s.wMm, s.hMm, s.dpi)
        val base = sanitize(s.name)
        val uris = mutableListOf<Uri>()

        if (s.format == Fmt.PDF) {
            uris += insert(ctx, "$base.pdf", s.format.mime, false, toStorage) { out ->
                val pdf = SimplePdf(out)
                pages.forEachIndexed { i, p ->
                    onProgress(i + 1)
                    val bmp = renderPage(p, pw, ph, s.filter)
                    val jpg = encode(bmp, Fmt.JPG, s.quality, s.dpi)
                    bmp.recycle()
                    pdf.addPage(jpg, pw, ph, s.wMm / 25.4 * 72.0, s.hMm / 25.4 * 72.0)
                }
                pdf.finish()
            }
        } else {
            pages.forEachIndexed { i, p ->
                onProgress(i + 1)
                val bmp = renderPage(p, pw, ph, s.filter)
                val bytes = encode(bmp, s.format, s.quality, s.dpi)
                bmp.recycle()
                val fname = if (pages.size == 1) "$base.${s.format.ext}" else "${base}_${i + 1}.${s.format.ext}"
                uris += insert(ctx, fname, s.format.mime, true, toStorage) { it.write(bytes) }
            }
        }
        return uris
    }

    private fun renderPage(p: ScanPage, w: Int, h: Int, f: ScanFilter): Bitmap {
        val src = DocScanner.load(p.file, 6000)
        val out = DocScanner.render(src, p.corners, w, h, f)
        src.recycle()
        return out
    }

    private fun encode(bmp: Bitmap, fmt: Fmt, quality: Int, dpi: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        return if (fmt == Fmt.PNG) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            pngWithDpi(bos.toByteArray(), dpi)
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val b = bos.toByteArray()
            if (b.size > 18 && b[2] == 0xFF.toByte() && b[3] == 0xE0.toByte() && b[6] == 'J'.code.toByte()) {
                b[13] = 1
                b[14] = (dpi shr 8).toByte(); b[15] = dpi.toByte()
                b[16] = (dpi shr 8).toByte(); b[17] = dpi.toByte()
            }
            b
        }
    }

    private fun pngWithDpi(png: ByteArray, dpi: Int): ByteArray {
        if (png.size < 33 || png[12] != 'I'.code.toByte()) return png
        val ppm = (dpi / 0.0254).roundToInt()
        val type = "pHYs".toByteArray(Charsets.ISO_8859_1)
        val data = ByteBuffer.allocate(9).putInt(ppm).putInt(ppm).put(1).array()
        val crc = CRC32().apply { update(type); update(data) }.value
        val chunk = ByteBuffer.allocate(21).putInt(9).put(type).put(data).putInt(crc.toInt()).array()
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }

    private fun sanitize(n: String): String {
        val c = n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return c.ifEmpty { "scan" }
    }

    private fun insert(
        ctx: Context, name: String, mime: String, image: Boolean,
        toStorage: Boolean, write: (OutputStream) -> Unit
    ): Uri {
        if (!toStorage) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, name)
            f.outputStream().use { write(it) }
            return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }
        val r = ctx.contentResolver
        val collection = if (image)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/DocScanner" else "Download/DocScanner")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, v) ?: error("cannot create file")
        try {
            (r.openOutputStream(uri) ?: error("cannot open file")).use { write(it) }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, v, null, null)
        } catch (t: Throwable) {
            r.delete(uri, null, null)
            throw t
        }
        return uri
    }

    // ---------- sharing ----------
    fun buildSend(uris: List<Uri>, mime: String): Intent {
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = mime
        val cd = ClipData.newRawUri("scan", uris[0])
        for (u in uris.drop(1)) cd.addItem(ClipData.Item(u))
        i.clipData = cd
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    /** All installed apps that can receive this file type (Drive, WhatsApp, Telegram, Bale, ...). */
    fun shareTargets(ctx: Context, mime: String, multi: Boolean): List<ShareTarget> {
        val pm = ctx.packageManager
        val probe = Intent(if (multi) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply { type = mime }
        val infos = pm.queryIntentActivities(probe, 0)
        val seen = HashSet<String>()
        val out = ArrayList<ShareTarget>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString()
            if (!seen.add(ai.packageName + "|" + label)) continue
            val icon = try {
                ri.loadIcon(pm).toBitmap(120, 120).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
            out += ShareTarget(label, icon, ComponentName(ai.packageName, ai.name))
        }
        return out.sortedBy { rank(it.component.packageName) }
    }

    private fun rank(pkg: String): Int {
        val l = pkg.lowercase()
        return when {
            "com.google.android.apps.docs" in l -> 1
            l.startsWith("com.whatsapp") -> 2
            l.startsWith("org.telegram") -> 3
            "ir.nasim" in l -> 4
            "ir.resaneh1" in l -> 5
            "soroush" in l -> 6
            "eitaa" in l -> 7
            l == "com.google.android.gm" -> 8
            else -> 100
        }
    }
}
