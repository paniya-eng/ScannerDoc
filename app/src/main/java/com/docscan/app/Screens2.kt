@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.docscan.app

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ───────────── Size ─────────────
@Composable
fun SizeScreen(vm: ScanVM) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header("ابعاد خروجی")

            Text("واحد", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.unitCm, onClick = { vm.setUnit(false) }, label = { Text("میلی‌متر") })
                FilterChip(selected = vm.unitCm, onClick = { vm.setUnit(true) }, label = { Text("سانتی‌متر") })
            }
            Spacer(Modifier.height(12.dp))

            val unit = if (vm.unitCm) "cm" else "mm"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.wText, onValueChange = { vm.wText = it; vm.presetName = null },
                    label = { Text("عرض ($unit)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = vm.hText, onValueChange = { vm.hText = it; vm.presetName = null },
                    label = { Text("طول ($unit)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = { vm.swapSize() }) { Text("جابه‌جایی طول و عرض (افقی/عمودی)") }

            Spacer(Modifier.height(8.dp))
            Text("اندازه‌های آماده", style = MaterialTheme.typography.labelLarge)
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(vm.presetName ?: "انتخاب اندازه آماده", modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name}  (${fmtNum(p.wMm)}×${fmtNum(p.hMm)} mm)") },
                            onClick = { vm.applyPreset(p); expanded = false }
                        )
                    }
                }
            }

            if (!vm.sizeValid) {
                Spacer(Modifier.height(8.dp))
                Text("اندازه معتبر وارد کنید (۵ تا ۲۰۰۰ میلی‌متر).", color = MaterialTheme.colorScheme.error)
            }
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, "قبلی", { vm.back() })
            BarIcon(Icons.Filled.ArrowForward, "بعدی", { vm.step = Step.QUALITY }, enabled = vm.sizeValid, container = Blue)
        }
    }
}

// ───────────── Quality & filter ─────────────
@Composable
fun QualityScreen(vm: ScanVM) {
    val wMm = vm.wMm ?: 210.0
    val hMm = vm.hMm ?: 297.0
    val (pw, ph, reduced) = pixelsFor(wMm, hMm, vm.dpi)

    val preview by produceState<Bitmap?>(null, vm.filter, wMm, hMm) {
        val page = vm.pages.firstOrNull()
        value = if (page == null) null else withContext(Dispatchers.Default) {
            val ar = wMm / hMm
            val w = if (ar >= 1) 900 else (900 * ar).roundToInt().coerceAtLeast(1)
            val h = (w / ar).roundToInt().coerceAtLeast(1)
            val src = DocScanner.load(page.file, 1600)
            val r = DocScanner.render(src, page.corners, w, h, vm.filter)
            src.recycle()
            r
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header("کیفیت و فیلتر")

            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) CircularProgressIndicator()
                else Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(12.dp))

            Text("فیلتر", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.values().forEach { f ->
                    FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(f.label) })
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("وضوح (DPI)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 200, 300, 600).forEach { d ->
                    FilterChip(selected = vm.dpi == d, onClick = { vm.dpi = d }, label = { Text("$d") })
                }
            }
            Text(
                "خروجی: $pw × $ph پیکسل" + if (reduced) " (برای جلوگیری از کمبود حافظه کاهش یافت)" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            if (vm.format != Fmt.PNG) {
                Text("کیفیت JPEG: ${vm.quality}٪", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.quality.toFloat(), onValueChange = { vm.quality = it.roundToInt() },
                    valueRange = 50f..100f
                )
            }
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, "قبلی", { vm.back() })
            BarIcon(Icons.Filled.ArrowForward, "بعدی", { vm.step = Step.SAVE }, container = Blue)
        }
    }
}

// ───────────── Save & send ─────────────
@Composable
fun SaveScreen(vm: ScanVM) {
    var showSheet by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ذخیره و ارسال", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmNew = true }) { Text("اسکن جدید") }
            }

            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("نام فایل") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("فرمت", style = MaterialTheme.typography.labelLarge)
            Fmt.values().forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = vm.format == f, onClick = { vm.format = f })
                    Text(
                        when (f) {
                            Fmt.JPG -> "JPG — حجم کم"
                            Fmt.PNG -> "PNG — بدون افت کیفیت"
                            Fmt.PDF -> "PDF — همه صفحه‌ها در یک فایل"
                        }
                    )
                }
            }
            if (vm.format != Fmt.PDF && vm.pages.size > 1) {
                Text(
                    "هر صفحه به‌صورت فایل جدا ارسال/ذخیره می‌شود (نام_1، نام_2، ...).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${vm.pages.size} صفحه • ${fmtNum(vm.wMm ?: 0.0)}×${fmtNum(vm.hMm ?: 0.0)} mm • ${vm.dpi} DPI • ${vm.filter.label}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "تا زمانی که «حافظه گوشی» را انتخاب نکنید، فایل در گوشی ذخیره نمی‌شود.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, "قبلی", { vm.back() })
            BarIcon(Icons.Filled.Share, "ارسال", { showSheet = true }, enabled = !vm.busy, container = Blue)
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ShareSheet(vm) { kind, comp ->
                showSheet = false
                vm.send(kind, comp)
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("اسکن جدید") },
            text = { Text("همه صفحه‌های فعلی پاک می‌شوند. ادامه می‌دهید؟") },
            confirmButton = { TextButton(onClick = { confirmNew = false; vm.newScan() }) { Text("بله") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("انصراف") } }
        )
    }
}

@Composable
fun ShareSheet(vm: ScanVM, onPick: (TargetKind, ComponentName?) -> Unit) {
    val ctx = LocalContext.current
    val multi = vm.format != Fmt.PDF && vm.pages.size > 1
    val apps by produceState<List<ShareTarget>?>(null, vm.format, multi) {
        value = withContext(Dispatchers.Default) { Exporter.shareTargets(ctx, vm.format.mime, multi) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("ارسال یا ذخیره با ...", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    TargetCell("حافظه گوشی", { Icon(Icons.Filled.Folder, null, Modifier.size(46.dp), tint = Color(0xFFFFC107)) }) {
                        onPick(TargetKind.STORAGE, null)
                    }
                }
                items(list) { t ->
                    TargetCell(t.label, {
                        val ic = t.icon
                        if (ic != null) Image(ic, null, Modifier.size(46.dp))
                        else Icon(Icons.Filled.Share, null, Modifier.size(40.dp))
                    }) { onPick(TargetKind.APP, t.component) }
                }
                item {
                    TargetCell("سایر برنامه‌ها", { Icon(Icons.Filled.Share, null, Modifier.size(40.dp)) }) {
                        onPick(TargetKind.CHOOSER, null)
                    }
                }
            }
        }
    }
}

@Composable
fun TargetCell(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}
