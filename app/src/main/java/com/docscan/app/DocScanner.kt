package com.docscan.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.media.ExifInterface
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocScanner {

    /** Decode a file with EXIF rotation applied, limited to maxDim on the long side. */
    fun load(file: File, maxDim: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > maxDim) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeFile(file.path, o2) ?: error("cannot decode image")
        val deg = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    /** Automatic document edge detection. Returns TL, TR, BR, BL normalized. */
    fun detect(src: Bitmap): List<PointF> {
        val fallback = listOf(
            PointF(0.06f, 0.06f), PointF(0.94f, 0.06f),
            PointF(0.94f, 0.94f), PointF(0.06f, 0.94f)
        )
        try {
            val scale = min(1.0, 800.0 / max(src.width, src.height))
            val full = Mat()
            Utils.bitmapToMat(src, full)
            val m = Mat()
            Imgproc.resize(full, m, Size(src.width * scale, src.height * scale))
            full.release()
            val gray = Mat()
            Imgproc.cvtColor(m, gray, Imgproc.COLOR_RGBA2GRAY)
            m.release()
            val r = detectGray(gray)
            gray.release()
            if (r != null) return r
        } catch (_: Throwable) {
        }
        return fallback
    }

    /** gray: 8-bit single channel, already small. Returns normalized TL, TR, BR, BL or null. */
    fun detectGray(grayIn: Mat): List<PointF>? {
        try {
            val gray = Mat()
            Imgproc.GaussianBlur(grayIn, gray, Size(5.0, 5.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imgArea = gray.width().toDouble() * gray.height()
            val top = contours.sortedByDescending { Imgproc.contourArea(it) }.take(10)
            for (c in top) {
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                if (approx.total() == 4L &&
                    Imgproc.isContourConvex(MatOfPoint(*approx.toArray())) &&
                    Imgproc.contourArea(approx) > 0.15 * imgArea
                ) {
                    return order(approx.toArray(), gray.width(), gray.height())
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** Live detection on a camera frame (uses the Y plane directly as a gray image). */
    fun detectLive(img: ImageProxy): List<PointF>? {
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val w = img.width
            val h = img.height
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val mat = Mat(h, w, CvType.CV_8UC1)
            if (rs == w) {
                mat.put(0, 0, if (bytes.size > w * h) bytes.copyOf(w * h) else bytes)
            } else {
                val row = ByteArray(w)
                for (y in 0 until h) {
                    System.arraycopy(bytes, y * rs, row, 0, w)
                    mat.put(y, 0, row)
                }
            }
            val rot = Mat()
            when (img.imageInfo.rotationDegrees) {
                90 -> Core.rotate(mat, rot, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(mat, rot, Core.ROTATE_180)
                270 -> Core.rotate(mat, rot, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> mat.copyTo(rot)
            }
            val r = detectGray(rot)
            mat.release(); rot.release()
            r
        } catch (_: Throwable) {
            null
        }
    }

    private fun order(p: Array<Point>, w: Int, h: Int): List<PointF> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl).map {
            PointF((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f))
        }
    }

    fun naturalSize(w: Int, h: Int, c: List<PointF>): Pair<Int, Int> {
        fun d(a: PointF, b: PointF) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val ww = ((d(c[0], c[1]) + d(c[3], c[2])) / 2f).roundToInt()
        val hh = ((d(c[0], c[3]) + d(c[1], c[2])) / 2f).roundToInt()
        return Pair(max(1, ww), max(1, hh))
    }

    /** Small preview of the cropped page (natural aspect ratio). */
    fun preview(page: ScanPage, maxDim: Int): Bitmap {
        val src = load(page.file, 1200)
        val (nw, nh) = naturalSize(src.width, src.height, page.corners)
        val k = maxDim.toDouble() / max(nw, nh)
        val out = render(src, page.corners, max(1, (nw * k).roundToInt()), max(1, (nh * k).roundToInt()), ScanFilter.ORIGINAL)
        src.recycle()
        return out
    }

    /** Perspective-correct the page into exactly outW x outH pixels, then apply filter. */
    fun render(src: Bitmap, corners: List<PointF>, outW: Int, outH: Int, filter: ScanFilter): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val from = MatOfPoint2f(
            *corners.map { Point((it.x * src.width).toDouble(), (it.y * src.height).toDouble()) }.toTypedArray()
        )
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val tr = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(srcMat, out, tr, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC)
        srcMat.release()
        applyFilter(out, filter)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        out.release()
        return bmp
    }

    private fun applyFilter(m: Mat, f: ScanFilter) {
        when (f) {
            ScanFilter.ORIGINAL -> Unit
            ScanFilter.ENHANCED -> {
                // remove shadows by dividing by an estimated background, then boost contrast
                val small = Mat()
                Imgproc.resize(
                    m, small,
                    Size(max(1.0, m.width() / 8.0), max(1.0, m.height() / 8.0)),
                    0.0, 0.0, Imgproc.INTER_AREA
                )
                Imgproc.dilate(small, small, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
                Imgproc.GaussianBlur(small, small, Size(21.0, 21.0), 0.0)
                val bg = Mat()
                Imgproc.resize(small, bg, m.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Core.divide(m, bg, m, 255.0)
                m.convertTo(m, -1, 1.1, -8.0)
                small.release(); bg.release()
            }
            ScanFilter.GRAY -> {
                val g = Mat()
                Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
                Core.normalize(g, g, 0.0, 255.0, Core.NORM_MINMAX)
                Imgproc.cvtColor(g, m, Imgproc.COLOR_GRAY2RGBA)
                g.release()
            }
            ScanFilter.BW -> {
                val g = Mat()
                Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.medianBlur(g, g, 3)
                var block = max(15, min(m.width(), m.height()) / 30)
                if (block % 2 == 0) block++
                val bw = Mat()
                Imgproc.adaptiveThreshold(
                    g, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY, block, 12.0
                )
                Imgproc.cvtColor(bw, m, Imgproc.COLOR_GRAY2RGBA)
                g.release(); bw.release()
            }
        }
    }

    // ---------- rotate / mirror ----------
    fun transformFile(src: File, dst: File, xf: Xf) {
        val b = load(src, 6000)
        val m = Matrix()
        when (xf) {
            Xf.ROT_LEFT -> m.postRotate(-90f)
            Xf.ROT_RIGHT -> m.postRotate(90f)
            Xf.FLIP -> m.postScale(-1f, 1f)
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        dst.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (r !== b) r.recycle()
        b.recycle()
    }

    /** corners order stays TL, TR, BR, BL after the transform */
    fun transformCorners(c: List<PointF>, xf: Xf): List<PointF> = when (xf) {
        Xf.ROT_RIGHT -> {
            fun t(p: PointF) = PointF(1f - p.y, p.x)
            listOf(t(c[3]), t(c[0]), t(c[1]), t(c[2]))
        }
        Xf.ROT_LEFT -> {
            fun t(p: PointF) = PointF(p.y, 1f - p.x)
            listOf(t(c[1]), t(c[2]), t(c[3]), t(c[0]))
        }
        Xf.FLIP -> {
            fun t(p: PointF) = PointF(1f - p.x, p.y)
            listOf(t(c[1]), t(c[0]), t(c[3]), t(c[2]))
        }
    }
}

enum class Xf { ROT_LEFT, ROT_RIGHT, FLIP }
