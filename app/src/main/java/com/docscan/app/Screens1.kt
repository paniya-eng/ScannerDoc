@file:OptIn(ExperimentalMaterial3Api::class)

package com.docscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.MediaActionSound
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import java.util.concurrent.Executors
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Blue = Color(0xFF2F80FF)
val Green = Color(0xFF2E9E50)
val PanelGray = Color(0xFF2A2A2E)

// ───────────── shared building blocks ─────────────
@Composable
fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
fun Header(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    )
}

/** Bottom bar: always one line, pinned to the bottom. */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Ltr {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun BarIcon(
    icon: ImageVector, desc: String, onClick: () -> Unit,
    enabled: Boolean = true, container: Color? = null
) {
    val colors = if (container == null) IconButtonDefaults.filledTonalIconButtonColors()
    else IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = Color.White)
    FilledIconButton(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.size(58.dp)) {
        Icon(icon, desc, Modifier.size(30.dp))
    }
}

// ───────────── Camera ─────────────
@Composable
fun CameraScreen(vm: ScanVM) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    LaunchedEffect(Unit) { if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPerm) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("برای اسکن، دسترسی به دوربین لازم است.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("اجازه دسترسی به دوربین") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { gallery.launch("image/*") }) { Text("انتخاب از گالری") }
        }
        return
    }

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var autoOn by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf<List<Offset>?>(null) }
    val autoNow by rememberUpdatedState(autoOn)

    // live document edge detection on camera frames
    DisposableEffect(Unit) {
        val exec = Executors.newSingleThreadExecutor()
        var last = 0L
        var miss = 0
        analysis.setAnalyzer(exec) { proxy ->
            val now = System.currentTimeMillis()
            if (!autoNow) {
                live = null
            } else if (now - last > 250) {
                last = now
                val r = DocScanner.detectLive(proxy)
                if (r != null) {
                    live = r.map { Offset(it.x, it.y) }
                    miss = 0
                } else {
                    miss++
                    if (miss > 3) live = null
                }
            }
            proxy.close()
        }
        onDispose { analysis.clearAnalyzer(); exec.shutdown() }
    }

    val last = vm.pages.lastOrNull()
    val thumb by produceState<Bitmap?>(null, last) {
        value = if (last == null) null else withContext(Dispatchers.Default) { DocScanner.preview(last, 200) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // top bar: back · logo + name
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.Filled.ArrowBack, "بازگشت", tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.logo), null,
                        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scanner Doc", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(48.dp))
            }
        }

        // preview 3:4 with live quad overlay
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val w = if (maxWidth / maxHeight > 0.75f) maxHeight * 0.75f else maxWidth
            Box(Modifier.size(w, w / 0.75f).clip(RoundedCornerShape(22.dp))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        PreviewView(c).also { pv ->
                            pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val future = ProcessCameraProvider.getInstance(c)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                                provider.unbindAll()
                                camera = try {
                                    provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview, imageCapture, analysis
                                    )
                                } catch (_: Exception) {
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                                    )
                                }
                            }, ContextCompat.getMainExecutor(c))
                        }
                    }
                )
                val q = live
                if (q != null && autoOn) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p = q.map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply {
                            moveTo(p[0].x, p[0].y)
                            for (i in 1..3) lineTo(p[i].x, p[i].y)
                            close()
                        }
                        drawPath(path, Color(0x332F80FF), style = Fill)
                        drawPath(path, Blue, style = Stroke(width = 4f))
                        p.forEach {
                            drawCircle(Color.White, radius = 17f, center = it)
                            drawCircle(Blue, radius = 12f, center = it)
                        }
                    }
                }
                Text(
                    if (q != null && autoOn) "سند شناسایی شد" else "سند را داخل کادر قرار دهید",
                    color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0x99000000), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // mode strip
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MODES.forEachIndexed { i, m ->
                    val sel = i == vm.modeIndex
                    Text(
                        m.first,
                        color = if (sel) Blue else Color(0xFFBBBBBB),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { vm.setMode(i) }.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // controls: gallery · auto · shutter · flash · last page
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(PanelGray)
                            .clickable { gallery.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.PhotoLibrary, "گالری", tint = Color.White) }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(PanelGray)
                            .clickable { autoOn = !autoOn },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.AutoFixHigh, "تشخیص خودکار",
                            tint = if (autoOn) Blue else Color.Gray
                        )
                    }
                }
                Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(78.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                if (!vm.busy) {
                                    sound.play(MediaActionSound.SHUTTER_CLICK)
                                    val f = File(ctx.cacheDir, "p_${System.nanoTime()}.jpg")
                                    imageCapture.takePicture(
                                        ImageCapture.OutputFileOptions.Builder(f).build(),
                                        ContextCompat.getMainExecutor(ctx),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(r: ImageCapture.OutputFileResults) { vm.addImage(f) }
                                            override fun onError(e: ImageCaptureException) {
                                                vm.error = "خطا در عکس‌برداری: ${e.message}"
                                            }
                                        }
                                    )
                                }
                            }
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(PanelGray)
                            .clickable {
                                torch = !torch
                                camera?.cameraControl?.enableTorch(torch)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            "فلش", tint = if (torch) Color(0xFFFFC107) else Color.White
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(PanelGray)
                                .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
                                .clickable(enabled = vm.pages.isNotEmpty()) { vm.step = Step.PAGES }
                        ) {
                            val t = thumb
                            if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (vm.pages.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(24.dp)
                                    .clip(CircleShape).background(Blue),
                                contentAlignment = Alignment.Center
                            ) { Text("${vm.pages.size}", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ───────────── Crop (with magnifier) ─────────────
@Composable
fun Magnifier(bmp: Bitmap, c: Offset, disp: IntSize, modifier: Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    Canvas(
        modifier.clip(CircleShape).background(Color.Black).border(3.dp, Color.White, CircleShape)
    ) {
        val zoom = 3f
        withTransform({
            translate(
                left = size.width / 2f - c.x * disp.width * zoom,
                top = size.height / 2f - c.y * disp.height * zoom
            )
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            drawImage(img, dstSize = IntSize(disp.width, disp.height))
        }
        val m = Offset(size.width / 2f, size.height / 2f)
        drawLine(Blue, Offset(m.x - 22f, m.y), Offset(m.x + 22f, m.y), strokeWidth = 2f)
        drawLine(Blue, Offset(m.x, m.y - 22f), Offset(m.x, m.y + 22f), strokeWidth = 2f)
        drawCircle(Blue, radius = 7f, center = m, style = Stroke(width = 2f))
    }
}

@Composable
fun CropScreen(vm: ScanVM) {
    val page = vm.pages.getOrNull(vm.editIndex)
    if (page == null) {
        LaunchedEffect(Unit) { vm.step = Step.PAGES }
        return
    }
    val bmp by produceState<Bitmap?>(null, page.file) {
        value = withContext(Dispatchers.Default) { DocScanner.load(page.file, 1600) }
    }
    var pts by remember(page.file, page.corners) { mutableStateOf(page.corners.map { Offset(it.x, it.y) }) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    fun current() = pts.map { PointF(it.x, it.y) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("تنظیم محدوده سند")

        Ltr {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalIconButton(onClick = { vm.transform(Xf.ROT_LEFT, current()) }) {
                    Icon(Icons.Filled.RotateLeft, "چرخش به چپ")
                }
                FilledTonalIconButton(onClick = { vm.transform(Xf.FLIP, current()) }) {
                    Icon(Icons.Filled.Flip, "آینه")
                }
                FilledTonalIconButton(onClick = { vm.transform(Xf.ROT_RIGHT, current()) }) {
                    Icon(Icons.Filled.RotateRight, "چرخش به راست")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val b = bmp
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (b == null) {
                CircularProgressIndicator()
            } else {
                val ar = b.width.toFloat() / b.height
                val w = if (maxWidth / maxHeight > ar) maxHeight * ar else maxWidth
                val h = w / ar
                Ltr {
                    Box(
                        Modifier
                            .size(w, h)
                            .onSizeChanged { boxSize = it }
                            .pointerInput(page.file) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        if (boxSize.width > 0) {
                                            var best = -1
                                            var bd = Float.MAX_VALUE
                                            pts.forEachIndexed { i, o ->
                                                val dx = o.x * boxSize.width - p.x
                                                val dy = o.y * boxSize.height - p.y
                                                val d = dx * dx + dy * dy
                                                if (d < bd) { bd = d; best = i }
                                            }
                                            active = best
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (active >= 0 && boxSize.width > 0) {
                                            val o = pts[active]
                                            val n = Offset(
                                                (o.x + amount.x / boxSize.width).coerceIn(0f, 1f),
                                                (o.y + amount.y / boxSize.height).coerceIn(0f, 1f)
                                            )
                                            pts = pts.toMutableList().also { it[active] = n }
                                        }
                                    },
                                    onDragEnd = { active = -1 },
                                    onDragCancel = { active = -1 }
                                )
                            }
                    ) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            val p = pts.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = Path().apply {
                                moveTo(p[0].x, p[0].y)
                                for (i in 1..3) lineTo(p[i].x, p[i].y)
                                close()
                            }
                            drawPath(path, Color(0x332F80FF), style = Fill)
                            drawPath(path, Blue, style = Stroke(width = 5f))
                            p.forEachIndexed { i, o ->
                                val r = if (i == active) 30f else 24f
                                drawCircle(Color.White, radius = r + 4f, center = o)
                                drawCircle(Blue, radius = r, center = o)
                            }
                        }
                        if (active >= 0 && boxSize.width > 0) {
                            val a = pts[active]
                            val magDp = 124.dp
                            val magPx = with(LocalDensity.current) { magDp.toPx() }
                            val nearTopLeft = a.x * boxSize.width < magPx * 1.9f && a.y * boxSize.height < magPx * 1.9f
                            Magnifier(
                                b, a, boxSize,
                                Modifier
                                    .align(if (nearTopLeft) Alignment.TopEnd else Alignment.TopStart)
                                    .padding(8.dp)
                                    .size(magDp)
                            )
                        }
                    }
                }
            }
        }

        BottomBar {
            BarIcon(Icons.Filled.Add, "عکس مجدد", { vm.retake() })
            BarIcon(Icons.Filled.AutoFixHigh, "تشخیص خودکار", { vm.autoDetect() }, enabled = b != null)
            BarIcon(Icons.Filled.Check, "تأیید", {
                vm.updateCorners(vm.editIndex, current())
                vm.confirmCrop()
            }, enabled = b != null, container = Green)
        }
    }
}

// ───────────── Pages list ─────────────
@Composable
fun PagesScreen(vm: ScanVM) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("صفحه‌ها (${vm.pages.size})")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, p ->
                val thumb by produceState<Bitmap?>(null, p) {
                    value = withContext(Dispatchers.Default) { DocScanner.preview(p, 500) }
                }
                Card {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("صفحه ${i + 1}", style = MaterialTheme.typography.labelLarge)
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            val t = thumb
                            if (t == null) CircularProgressIndicator()
                            else Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        Row {
                            IconButton(onClick = { vm.editPage(i) }) { Icon(Icons.Filled.Edit, "ویرایش") }
                            IconButton(onClick = { vm.deletePage(i) }) { Icon(Icons.Filled.Delete, "حذف") }
                        }
                    }
                }
            }
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, "قبلی", { vm.back() })
            BarIcon(Icons.Filled.Add, "اسکن صفحه بعد", { vm.step = Step.SCAN })
            BarIcon(Icons.Filled.ArrowForward, "ادامه", { vm.step = Step.SIZE }, container = Blue)
        }
    }
}
