package com.hdstatus.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent { AppTheme { MainScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** Handles "Share → HD Status" and "Open with → HD Status" from other apps. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)?.firstOrNull()
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) vm.pick(uri, intent.type)
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun streamListExtra(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.pick(it) }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("HD Status") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null)
                Text(if (vm.picked == null) "  Pick photo or video" else "  Pick a different one")
            }

            if (vm.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("Loading…")
                }
            }

            when (val p = vm.picked) {
                null -> if (!vm.loading) Text(
                    "Pick a photo or video, or open your gallery, tap Share and choose HD Status. " +
                        "It's converted to a size and quality WhatsApp accepts without re-compressing, so your status stays sharp.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is Picked.Image -> ImageSection(vm, p)
                is Picked.Video -> VideoSection(vm, p)
            }

            if (vm.picked != null) {
                if (vm.busy) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Converting… ${(vm.progress * 100).roundToInt()}%")
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                } else {
                    Button(onClick = vm::convert, modifier = Modifier.fillMaxWidth()) { Text("Convert for status") }
                }
            }

            if (vm.outputs.isNotEmpty() && !vm.busy) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ready", style = MaterialTheme.typography.titleMedium)
                        vm.outputs.forEach { f ->
                            Text("${f.name} · ${"%.1f".format(f.length() / 1_048_576.0)} MB", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { Sharing.shareToWhatsApp(ctx, vm.outputs, vm.outputMime) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Share, null)
                            Text("  Share to WhatsApp")
                        }
                        OutlinedButton(onClick = vm::saveToGallery, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Done, null)
                            Text("  Save to gallery")
                        }
                        Text(
                            "In WhatsApp choose \"My status\". If WhatsApp shows an HD button on the edit screen, turn it on before posting.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ImageSection(vm: MainViewModel, p: Picked.Image) {
    val cropW = (vm.crop.width * p.width).roundToInt()
    val cropH = (vm.crop.height * p.height).roundToInt()
    InfoCard(
        "Photo · ${p.width}×${p.height} · ${ratioLabel(p.width, p.height)}",
        "Crop: ${cropW}×${cropH} (${ratioLabel(cropW, cropH)}) · 9:16 fills the status screen",
    )
    ImageCropper(
        bitmap = p.preview,
        crop = vm.crop,
        lockedRatio = vm.lockedRatio(),
        onCropChange = { vm.crop = it },
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AspectOption.entries.forEach { o ->
            FilterChip(selected = vm.aspect == o, onClick = { vm.selectAspect(o) }, label = { Text(o.label) }, enabled = !vm.busy)
        }
    }
    ImageSettingsCard(vm.imageSettings, vm::updateImage, vm::resetSettings, enabled = !vm.busy)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSection(vm: MainViewModel, p: Picked.Video) {
    val s = vm.videoSettings
    val (w, h) = VideoConverter.targetSize(p.info, s.shortSide)
    val len = vm.trimEndMs - vm.trimStartMs
    val segMs = s.segmentSeconds * 1000L
    val parts = ((len + segMs - 1) / segMs).coerceAtLeast(1)
    val estMb = s.bitrateMbps * (len / 1000.0) / 8 + 0.016 * (len / 1000.0) // video + ~128kbps audio
    InfoCard(
        "Video · ${p.info.width}×${p.info.height} · ${ratioLabel(p.info.width, p.info.height)} · ${fmtTime(p.info.durationMs)}",
        "Output: ${w}×${h}, ${"%.1f".format(s.bitrateMbps)} Mbps, ~${"%.1f".format(estMb)} MB" +
            if (parts > 1) ", $parts parts" else "",
    )
    VideoTrimmer(
        uri = p.uri,
        info = p.info,
        thumbnails = vm.thumbnails,
        startMs = vm.trimStartMs,
        endMs = vm.trimEndMs,
        onTrimChange = vm::setTrim,
    )
    VideoSettingsCard(s, vm::updateVideo, vm::resetSettings, enabled = !vm.busy)
}

@Composable
private fun InfoCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Slider over a fixed list of values. */
@Composable
private fun <T> StepSlider(label: String, values: List<T>, selected: T, format: (T) -> String, enabled: Boolean, onChange: (T) -> Unit) {
    val idx = values.indexOf(selected).coerceAtLeast(0)
    Text("$label: ${format(selected)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = idx.toFloat(),
        onValueChange = { onChange(values[it.roundToInt().coerceIn(0, values.lastIndex)]) },
        valueRange = 0f..values.lastIndex.toFloat(),
        steps = (values.size - 2).coerceAtLeast(0),
        enabled = enabled,
    )
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Text("$label: $text", style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

@Composable
private fun SettingsHeader(onReset: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Quality settings", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onReset, enabled = enabled) { Text("Reset") }
    }
}

@Composable
private fun VideoSettingsCard(s: VideoSettings, onChange: (VideoSettings) -> Unit, onReset: () -> Unit, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsHeader(onReset, enabled)
            StepSlider("Resolution", SettingRanges.resolutions, s.shortSide, { "${it}p" }, enabled) { onChange(s.copy(shortSide = it)) }
            ValueSlider("Bitrate", s.bitrateMbps, SettingRanges.bitrate, "${"%.1f".format(s.bitrateMbps)} Mbps", enabled) {
                onChange(s.copy(bitrateMbps = (it * 2).roundToInt() / 2f))
            }
            ValueSlider("Sharpness", s.sharpness.toFloat(), SettingRanges.sharpness, "${s.sharpness}%", enabled) {
                onChange(s.copy(sharpness = it.roundToInt()))
            }
            StepSlider("Max frame rate", SettingRanges.fps, s.maxFps, { "$it fps" }, enabled) { onChange(s.copy(maxFps = it)) }
            ValueSlider("Split every", s.segmentSeconds.toFloat(), SettingRanges.segment, "${s.segmentSeconds}s", enabled) {
                onChange(s.copy(segmentSeconds = ((it / 5).roundToInt() * 5).coerceAtLeast(10)))
            }
        }
    }
}

@Composable
private fun ImageSettingsCard(s: ImageSettings, onChange: (ImageSettings) -> Unit, onReset: () -> Unit, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsHeader(onReset, enabled)
            StepSlider("Long side", SettingRanges.longSides, s.longSide, { "$it px" }, enabled) { onChange(s.copy(longSide = it)) }
            ValueSlider("JPEG quality", s.jpegQuality.toFloat(), SettingRanges.quality, "${s.jpegQuality}%", enabled) {
                onChange(s.copy(jpegQuality = it.roundToInt()))
            }
            ValueSlider("Sharpness", s.sharpness.toFloat(), SettingRanges.sharpness, "${s.sharpness}%", enabled) {
                onChange(s.copy(sharpness = it.roundToInt()))
            }
        }
    }
}

/** "9:16", "4:5" etc. for common shapes, otherwise a simplified W:H. */
private fun ratioLabel(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "?"
    val r = w.toFloat() / h
    val common = listOf(
        "9:16" to 9f / 16, "9:19.5" to 9f / 19.5f, "9:20" to 9f / 20, "3:4" to 3f / 4, "4:5" to 4f / 5,
        "1:1" to 1f, "4:3" to 4f / 3, "3:2" to 3f / 2, "2:3" to 2f / 3, "16:9" to 16f / 9,
    )
    common.firstOrNull { abs(it.second - r) < 0.01f }?.let { return it.first }
    return if (r < 1) "1:${"%.2f".format(1 / r)}" else "${"%.2f".format(r)}:1"
}
