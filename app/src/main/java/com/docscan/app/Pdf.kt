package com.docscan.app

import java.io.OutputStream
import java.util.Locale

/** Minimal PDF writer: embeds JPEG data as-is, page size equals the physical size exactly. */
class SimplePdf(private val out: OutputStream) {
    private val offsets = HashMap<Int, Long>()
    private var pos = 0L
    private var n = 0

    private fun raw(b: ByteArray) { out.write(b); pos += b.size }
    private fun txt(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun begin(num: Int) { offsets[num] = pos; txt("$num 0 obj\n") }

    init {
        txt("%PDF-1.4\n")
    }

    fun addPage(jpeg: ByteArray, pxW: Int, pxH: Int, wPt: Double, hPt: Double) {
        val pageNo = 3 + 3 * n
        val contentNo = pageNo + 1
        val imageNo = pageNo + 2
        begin(imageNo)
        txt("<< /Type /XObject /Subtype /Image /Width $pxW /Height $pxH /ColorSpace /DeviceRGB " +
            "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        raw(jpeg)
        txt("\nendstream\nendobj\n")
        val content = "q ${f(wPt)} 0 0 ${f(hPt)} 0 0 cm /Im0 Do Q\n"
        begin(contentNo)
        txt("<< /Length ${content.length} >>\nstream\n${content}endstream\nendobj\n")
        begin(pageNo)
        txt("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(wPt)} ${f(hPt)}] " +
            "/Resources << /XObject << /Im0 $imageNo 0 R >> >> /Contents $contentNo 0 R >>\nendobj\n")
        n++
    }

    fun finish() {
        begin(1)
        txt("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
        begin(2)
        txt("<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")
        val total = 3 + 3 * n
        val xref = pos
        txt("xref\n0 $total\n")
        txt("0000000000 65535 f \n")
        for (i in 1 until total) txt(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(i)))
        txt("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }
}

