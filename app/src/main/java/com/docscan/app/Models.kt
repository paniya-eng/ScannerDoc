package com.docscan.app

import android.graphics.PointF
import java.io.File
import java.util.Locale

enum class Step { SPLASH, HOME, SCAN, CROP, PAGES, SIZE, QUALITY, SAVE }

enum class Fmt(val label: String, val ext: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    PDF("PDF", "pdf", "application/pdf")
}

enum class ScanFilter(val label: String) {
    ORIGINAL("اصلی"),
    ENHANCED("رنگی بهبودیافته"),
    GRAY("خاکستری"),
    BW("سیاه‌وسفید")
}

/** corners: TL, TR, BR, BL — normalized (0..1) relative to the image */
data class ScanPage(val id: Long, val file: File, val corners: List<PointF>)

data class ExportSpec(
    val wMm: Double,
    val hMm: Double,
    val dpi: Int,
    val quality: Int,
    val filter: ScanFilter,
    val name: String,
    val format: Fmt
)

data class Preset(val name: String, val wMm: Double, val hMm: Double)

val PRESETS = listOf(
    Preset("A4", 210.0, 297.0),
    Preset("A5", 148.0, 210.0),
    Preset("A3", 297.0, 420.0),
    Preset("Letter", 215.9, 279.4),
    Preset("کارت ملی / بانکی", 85.6, 54.0),
    Preset("عکس ۳×۴", 30.0, 40.0),
    Preset("عکس ۴×۶", 40.0, 60.0)
)

val MODES = listOf("سند" to PRESETS[0], "کارت" to PRESETS[4], "عکس ۳×۴" to PRESETS[5])

const val MAX_SIDE_PX = 8000

/** returns (pxW, pxH, wasReduced) */
fun pixelsFor(wMm: Double, hMm: Double, dpi: Int): Triple<Int, Int, Boolean> {
    var w = wMm / 25.4 * dpi
    var h = hMm / 25.4 * dpi
    val m = maxOf(w, h)
    var reduced = false
    if (m > MAX_SIDE_PX) {
        val k = MAX_SIDE_PX / m
        w *= k; h *= k; reduced = true
    }
    return Triple(maxOf(1, Math.round(w).toInt()), maxOf(1, Math.round(h).toInt()), reduced)
}

fun parseNum(t: String): Double? {
    val sb = StringBuilder()
    for (c in t) {
        sb.append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                '٫', ',', '،' -> '.'
                else -> c
            }
        )
    }
    return sb.toString().trim().toDoubleOrNull()
}

fun fmtNum(v: Double): String {
    val s = String.format(Locale.US, "%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}
