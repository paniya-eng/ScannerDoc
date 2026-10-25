@file:OptIn(ExperimentalMaterial3Api::class)

package com.docscan.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF1B2333)
private val Muted = Color(0xFF8A93A6)

// ───────────── Splash / welcome ─────────────
@Composable
fun SplashScreen(vm: ScanVM) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1500, easing = LinearEasing))
        vm.step = Step.HOME
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            painterResource(R.drawable.logo), null,
            Modifier.size(160.dp).clip(RoundedCornerShape(38.dp))
        )
        Spacer(Modifier.height(28.dp))
        Text("خوش آمدید", fontSize = 18.sp, color = Muted)
        Spacer(Modifier.height(4.dp))
        Text("Scanner Doc", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(14.dp))
        Text(
            "اسکن دقیق مدارک با ابعاد واقعی\nخروجی PDF، JPG و PNG",
            fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD9DEE8))
        ) {
            Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(Blue))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────── Home ─────────────
private fun DrawScope.brackets(
    left: Float, top: Float, right: Float, bottom: Float,
    arm: Float, color: Color, width: Float
) {
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
    l(left, top, left + arm, top); l(left, top, left, top + arm)
    l(right, top, right - arm, top); l(right, top, right, top + arm)
    l(left, bottom, left + arm, bottom); l(left, bottom, left, bottom - arm)
    l(right, bottom, right - arm, bottom); l(right, bottom, right, bottom - arm)
}

@Composable
fun ScanFrame(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(
        initialValue = 0.34f, targetValue = 0.66f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
        label = "y"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        brackets(10f, 10f, w - 10f, h - 10f, w * 0.26f, Color(0xFFDDE2EA), 22f)
        brackets(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f, w * 0.14f, Blue, 16f)
        val ly = h * y
        val a = Offset(w * 0.1f, ly)
        val b = Offset(w * 0.9f, ly)
        drawLine(Blue.copy(alpha = 0.12f), a, b, strokeWidth = 42f, cap = StrokeCap.Round)
        drawLine(Blue.copy(alpha = 0.25f), a, b, strokeWidth = 22f, cap = StrokeCap.Round)
        drawLine(Blue, a, b, strokeWidth = 9f, cap = StrokeCap.Round)
    }
}

@Composable
fun ModeCard(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color.White)
            .border(2.dp, if (selected) Blue else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = if (selected) Blue else Color(0xFF3A4252))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = if (selected) Blue else Ink)
    }
}

@Composable
fun HomeScreen(vm: ScanVM) {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    var about by remember { mutableStateOf(false) }
    val icons = listOf(Icons.Filled.Description, Icons.Filled.CreditCard, Icons.Filled.Person)

    Column(Modifier.fillMaxSize()) {
        // top bar
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { about = true }) { Icon(Icons.Filled.Menu, "درباره", tint = Ink) }
                Text(
                    buildAnnotatedString {
                        append("Scanner ")
                        withStyle(SpanStyle(color = Blue)) { append("Doc") }
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                Spacer(Modifier.size(48.dp))
            }
        }

        // mode cards
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MODES.forEachIndexed { i, m ->
                ModeCard(icons[i], m.first, vm.modeIndex == i, Modifier.weight(1f)) { vm.setMode(i) }
            }
        }

        // scan frame
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ScanFrame(Modifier.size(230.dp))
            Spacer(Modifier.height(16.dp))
            Text("برای شروع اسکن، دکمه‌ی آبی را بزنید", fontSize = 13.sp, color = Muted)
        }

        // continue banner (only when there are scanned pages)
        if (vm.pages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable { vm.step = Step.PAGES }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFE4ECFF)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Description, null, tint = Blue) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ادامه‌ی کار", fontWeight = FontWeight.Bold, color = Ink)
                    Text("${vm.pages.size} صفحه اسکن‌شده", fontSize = 12.sp, color = Muted)
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Blue),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowForward, null, tint = Color.White) }
            }
        }

        // bottom navigation
        Ltr {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(Color.White)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { gallery.launch("image/*") }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Folder, "انتخاب از گالری", Modifier.size(28.dp), tint = Muted)
                    }
                    Box(
                        Modifier.size(68.dp)
                            .shadow(14.dp, CircleShape, ambientColor = Blue, spotColor = Blue)
                            .background(Blue, CircleShape)
                            .clickable { vm.step = Step.SCAN },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.CropFree, "اسکن", Modifier.size(34.dp), tint = Color.White) }
                    Box(Modifier.size(52.dp)) {
                        IconButton(
                            onClick = { if (vm.pages.isNotEmpty()) vm.step = Step.PAGES },
                            modifier = Modifier.fillMaxSize()
                        ) { Icon(Icons.Filled.Layers, "صفحه‌ها", Modifier.size(28.dp), tint = Muted) }
                        if (vm.pages.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Blue),
                                contentAlignment = Alignment.Center
                            ) { Text("${vm.pages.size}", color = Color.White, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            confirmButton = { TextButton(onClick = { about = false }) { Text("باشه") } },
            title = { Text("Scanner Doc") },
            text = { Text("نسخه 2.0\nاسکن چندصفحه‌ای مدارک با ابعاد دقیق") }
        )
    }
}
