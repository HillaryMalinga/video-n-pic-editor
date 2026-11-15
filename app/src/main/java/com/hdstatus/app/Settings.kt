package com.hdstatus.app

import android.content.Context

/**
 * Output targets. WhatsApp re-compresses status media that exceeds its limits;
 * the goal is to hand it files that already fit so it leaves them alone.
 * These defaults are starting points — tune them by posting test statuses.
 */
data class VideoSettings(
    /** Short side of the output in pixels (720 = 720p, 1080 = 1080p). */
    val shortSide: Int = 1080,
    /** Target video bitrate in megabits per second. */
    val bitrateMbps: Float = 6.0f,
    /** Maximum frame rate; higher-fps sources are reduced to this. */
    val maxFps: Int = 30,
    /** Long videos are split into parts of this many seconds. */
    val segmentSeconds: Int = 60,
    /** 0–100 sharpening strength. */
    val sharpness: Int = 30,
)

data class ImageSettings(
    /** Long side of the output in pixels. Smaller images are not upscaled. */
    val longSide: Int = 2560,
    /** JPEG quality 1–100. */
    val jpegQuality: Int = 95,
    /** 0–100 sharpening strength. */
    val sharpness: Int = 25,
)

object SettingRanges {
    val resolutions = listOf(480, 540, 720, 960, 1080, 1440, 2160)
    val bitrate = 1f..30f
    val fps = listOf(24, 25, 30, 48, 50, 60)
    val segment = 10f..180f
    val longSides = listOf(1080, 1280, 1600, 1920, 2048, 2560, 3072, 3840, 4096)
    val quality = 60f..100f
    val sharpness = 0f..100f
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings_v2", Context.MODE_PRIVATE)

    fun loadVideo(): VideoSettings {
        val d = VideoSettings()
        return VideoSettings(
            shortSide = prefs.getInt("v_short", d.shortSide),
            bitrateMbps = prefs.getFloat("v_bitrate", d.bitrateMbps),
            maxFps = prefs.getInt("v_fps", d.maxFps),
            segmentSeconds = prefs.getInt("v_seg", d.segmentSeconds),
            sharpness = prefs.getInt("v_sharp", d.sharpness),
        )
    }

    fun loadImage(): ImageSettings {
        val d = ImageSettings()
        return ImageSettings(
            longSide = prefs.getInt("i_long", d.longSide),
            jpegQuality = prefs.getInt("i_q", d.jpegQuality),
            sharpness = prefs.getInt("i_sharp", d.sharpness),
        )
    }

    fun save(v: VideoSettings, i: ImageSettings) {
        prefs.edit()
            .putInt("v_short", v.shortSide)
            .putFloat("v_bitrate", v.bitrateMbps)
            .putInt("v_fps", v.maxFps)
            .putInt("v_seg", v.segmentSeconds)
            .putInt("v_sharp", v.sharpness)
            .putInt("i_long", i.longSide)
            .putInt("i_q", i.jpegQuality)
            .putInt("i_sharp", i.sharpness)
            .apply()
    }
}
