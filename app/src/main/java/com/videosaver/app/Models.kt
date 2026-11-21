package com.videosaver.app

import android.content.Context

enum class Quality(val label: String, val maxHeight: Int?, val audioOnly: Boolean = false) {
    BEST("Лучшее", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    AUDIO("Только звук", null, true),
}

enum class Status { QUEUED, FETCHING, DOWNLOADING, PROCESSING, DONE, FAILED, CANCELED }

data class DownloadItem(
    val id: String,
    val url: String,
    val quality: Quality,
    val title: String? = null,
    val thumbnail: String? = null,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = 0,
    val error: String? = null,
    val resultUri: String? = null,
    val mimeType: String? = null,
    val savedTo: String? = null,
) {
    val isActive: Boolean
        get() = status == Status.QUEUED ||
            status == Status.FETCHING ||
            status == Status.DOWNLOADING ||
            status == Status.PROCESSING
}

sealed interface EngineState {
    data object Loading : EngineState
    data object Ready : EngineState
    data object Updating : EngineState
    data class Error(val message: String) : EngineState
}

/** Небольшое хранилище настроек. */
object Prefs {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("videosaver", Context.MODE_PRIVATE)

    fun quality(ctx: Context): Quality =
        runCatching { Quality.valueOf(prefs(ctx).getString("quality", Quality.BEST.name)!!) }
            .getOrDefault(Quality.BEST)

    fun setQuality(ctx: Context, quality: Quality) {
        prefs(ctx).edit().putString("quality", quality.name).apply()
    }

    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong("last_update", 0L)

    fun setLastUpdate(ctx: Context, time: Long) {
        prefs(ctx).edit().putLong("last_update", time).apply()
    }
}

/** Достаёт ссылку из текста (например, из «Поделиться» в YouTube: «Название https://youtu.be/…»). */
fun extractUrl(text: String): String? {
    val match = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)
    if (match != null) return match.value.trimEnd('.', ',', ')', ']', ';', '!')

    val trimmed = text.trim()
    val looksLikeDomain = trimmed.length > 4 &&
        trimmed.contains('.') &&
        trimmed.none { it.isWhitespace() }
    return if (looksLikeDomain) "https://$trimmed" else null
}
