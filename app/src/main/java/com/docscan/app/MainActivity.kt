package com.docscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = lightColorScheme(
                primary = Color(0xFF2F80FF),
                onPrimary = Color.White,
                background = Color(0xFFF3F5FA),
                surface = Color(0xFFF3F5FA),
                onSurface = Color(0xFF1B2333),
                surfaceVariant = Color.White,
                onSurfaceVariant = Color(0xFF5B6475),
                secondaryContainer = Color(0xFFE4ECFF),
                onSecondaryContainer = Color(0xFF1F4FD8),
                outline = Color(0xFFB8C0D0),
                outlineVariant = Color(0xFFD9DEE8)
            )
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App(vm: ScanVM = viewModel()) {
    val ctx = LocalContext.current
    BackHandler(enabled = vm.canGoBack()) { vm.back() }

    LaunchedEffect(vm.pendingIntent) {
        val i = vm.pendingIntent
        if (i != null) {
            try {
                ctx.startActivity(i)
            } catch (t: Throwable) {
                vm.error = "ارسال ممکن نشد: ${t.message}"
            }
            vm.pendingIntent = null
        }
    }

    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        when (vm.step) {
            Step.SPLASH -> SplashScreen(vm)
            Step.HOME -> HomeScreen(vm)
            Step.SCAN -> CameraScreen(vm)
            Step.CROP -> CropScreen(vm)
            Step.PAGES -> PagesScreen(vm)
            Step.SIZE -> SizeScreen(vm)
            Step.QUALITY -> QualityScreen(vm)
            Step.SAVE -> SaveScreen(vm)
        }

        if (vm.busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) {},
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(vm.busyText, color = Color.White)
                }
            }
        }

        vm.error?.let { msg ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = { TextButton(onClick = { vm.error = null }) { Text("باشه") } },
                text = { Text(msg) }
            )
        }
        vm.info?.let { msg ->
            AlertDialog(
                onDismissRequest = { vm.info = null },
                confirmButton = { TextButton(onClick = { vm.info = null }) { Text("باشه") } },
                text = { Text(msg) }
            )
        }
    }
}
