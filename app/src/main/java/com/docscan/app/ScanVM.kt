package com.docscan.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

class ScanVM(app: Application) : AndroidViewModel(app) {

    init { OpenCVLoader.initLocal() }

    var step by mutableStateOf(Step.SPLASH)
    val pages = mutableStateListOf<ScanPage>()
    var editIndex by mutableIntStateOf(-1)
    var editingNew by mutableStateOf(false)

    var unitCm by mutableStateOf(false)
    var wText by mutableStateOf("210")
    var hText by mutableStateOf("297")
    var presetName by mutableStateOf<String?>("A4")
    var modeIndex by mutableIntStateOf(0)
    var dpi by mutableIntStateOf(300)
    var quality by mutableIntStateOf(90)
    var filter by mutableStateOf(ScanFilter.ORIGINAL)
    var name by mutableStateOf("")
    var format by mutableStateOf(Fmt.PDF)

    var busy by mutableStateOf(false)
    var busyText by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var pendingIntent by mutableStateOf<Intent?>(null)

    private val cache get() = getApplication<Application>().cacheDir
    private val factor get() = if (unitCm) 10.0 else 1.0
    val wMm: Double? get() = parseNum(wText)?.times(factor)
    val hMm: Double? get() = parseNum(hText)?.times(factor)
    val sizeValid: Boolean
        get() {
            val w = wMm; val h = hMm
            return w != null && h != null && w >= 5 && h >= 5 && w <= 2000 && h <= 2000
        }

    fun setUnit(cm: Boolean) {
        if (cm == unitCm) return
        val w = wMm; val h = hMm
        unitCm = cm
        if (w != null) wText = fmtNum(w / factor)
        if (h != null) hText = fmtNum(h / factor)
    }

    fun applyPreset(p: Preset) {
        wText = fmtNum(p.wMm / factor)
        hText = fmtNum(p.hMm / factor)
        presetName = p.name
    }

    fun setMode(i: Int) { modeIndex = i; applyPreset(MODES[i].second) }

    fun swapSize() { val t = wText; wText = hText; hText = t; presetName = null }

    // ---- pages ----
    fun importUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val file = File(cache, "p_${System.nanoTime()}.jpg")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                        file.outputStream().use { i.copyTo(it) }
                    }
                    file
                }
                addImage(f)
            } catch (t: Throwable) { error = "خطا در بارگذاری تصویر: ${t.message}" }
        }
    }

    fun addImage(file: File) {
        viewModelScope.launch {
            busy = true; busyText = "تشخیص لبه‌های سند..."
            try {
                val corners = withContext(Dispatchers.Default) {
                    val b = DocScanner.load(file, 1200)
                    val c = DocScanner.detect(b)
                    b.recycle()
                    c
                }
                pages.add(ScanPage(System.nanoTime(), file, corners))
                editIndex = pages.lastIndex
                editingNew = true
                step = Step.CROP
            } catch (t: Throwable) {
                error = "خطا: ${t.message}"
            } finally { busy = false }
        }
    }

    fun updateCorners(i: Int, c: List<PointF>) {
        if (i in pages.indices) pages[i] = pages[i].copy(corners = c)
    }

    fun autoDetect() {
        val i = editIndex
        val p = pages.getOrNull(i) ?: return
        viewModelScope.launch {
            busy = true; busyText = "تشخیص خودکار..."
            try {
                val c = withContext(Dispatchers.Default) {
                    val b = DocScanner.load(p.file, 1200)
                    val r = DocScanner.detect(b)
                    b.recycle()
                    r
                }
                if (i in pages.indices && pages[i].id == p.id) pages[i] = pages[i].copy(corners = c)
            } catch (t: Throwable) {
                error = "خطا: ${t.message}"
            } finally { busy = false }
        }
    }

    fun transform(xf: Xf, current: List<PointF>) {
        val i = editIndex
        val p = pages.getOrNull(i) ?: return
        viewModelScope.launch {
            busy = true; busyText = "در حال اعمال..."
            try {
                val nf = File(cache, "p_${System.nanoTime()}.jpg")
                withContext(Dispatchers.Default) { DocScanner.transformFile(p.file, nf, xf) }
                if (i in pages.indices && pages[i].id == p.id) {
                    pages[i] = p.copy(file = nf, corners = DocScanner.transformCorners(current, xf))
                    p.file.delete()
                }
            } catch (t: Throwable) {
                error = "خطا: ${t.message}"
            } finally { busy = false }
        }
    }

    fun confirmCrop() { step = if (editingNew) Step.SCAN else Step.PAGES }

    fun retake() {
        if (editIndex in pages.indices) pages.removeAt(editIndex)
        step = Step.SCAN
    }

    fun editPage(i: Int) { editIndex = i; editingNew = false; step = Step.CROP }
    fun deletePage(i: Int) {
        if (i in pages.indices) pages.removeAt(i)
        if (pages.isEmpty()) step = Step.SCAN
    }

    // ---- navigation ----
    fun canGoBack() = step != Step.SPLASH && step != Step.HOME

    fun back() {
        step = when (step) {
            Step.SPLASH -> Step.SPLASH
            Step.HOME -> Step.HOME
            Step.SCAN -> Step.HOME
            Step.CROP -> Step.PAGES
            Step.PAGES -> Step.SCAN
            Step.SIZE -> Step.PAGES
            Step.QUALITY -> Step.SIZE
            Step.SAVE -> Step.QUALITY
        }
    }

    fun newScan() {
        pages.clear(); name = ""; step = Step.HOME
    }

    // ---- export / send ----
    fun send(kind: TargetKind, comp: ComponentName?) {
        val w = wMm ?: return
        val h = hMm ?: return
        val spec = ExportSpec(w, h, dpi, quality, filter, name, format)
        val list = pages.toList()
        val toStorage = kind == TargetKind.STORAGE
        viewModelScope.launch {
            busy = true; busyText = "در حال پردازش..."
            try {
                val uris = withContext(Dispatchers.Default) {
                    Exporter.export(getApplication(), list, spec, toStorage) { busyText = "پردازش صفحه $it از ${list.size}" }
                }
                if (toStorage) {
                    info = "ذخیره شد ✓\n" + if (spec.format == Fmt.PDF) "Download/DocScanner" else "Pictures/DocScanner"
                } else {
                    val i = Exporter.buildSend(uris, spec.format.mime)
                    pendingIntent = if (kind == TargetKind.APP) {
                        i.component = comp
                        i
                    } else {
                        Intent.createChooser(i, "ارسال")
                    }
                }
            } catch (t: Throwable) {
                error = "خطا: ${t.message}"
            } finally { busy = false }
        }
    }
}
