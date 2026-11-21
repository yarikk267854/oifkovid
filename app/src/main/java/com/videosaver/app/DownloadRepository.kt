package com.videosaver.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Вся логика скачивания: очередь, прогресс, сохранение в галерею.
 * Живёт, пока жив процесс приложения; сервис держит процесс активным в фоне.
 */
object DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2) // одновременно не больше двух загрузок
    private lateinit var appContext: Context

    /** Виден ли экран приложения (чтобы не слать уведомления «готово», пока пользователь смотрит). */
    @Volatile
    var uiVisible: Boolean = false

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _engine = MutableStateFlow<EngineState>(EngineState.Loading)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // ───────────────────────── Движок (yt-dlp + ffmpeg) ─────────────────────────

    fun init(app: Application) {
        appContext = app.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _engine.value = EngineState.Ready

                // Сайты постоянно меняются, поэтому yt-dlp обновляем раз в 12 часов.
                val sinceUpdate = System.currentTimeMillis() - Prefs.lastUpdate(app)
                if (sinceUpdate > 12 * 60 * 60 * 1000L) doUpdate(silent = true)
            } catch (e: Throwable) {
                _engine.value = EngineState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun updateEngine() {
        if (_items.value.any { it.isActive }) {
            _events.tryEmit("Дождитесь завершения загрузок")
            return
        }
        scope.launch {
            if (_engine.value is EngineState.Ready) doUpdate(silent = false)
        }
    }

    private fun doUpdate(silent: Boolean) {
        _engine.value = EngineState.Updating
        try {
            YoutubeDL.getInstance().updateYoutubeDL(appContext, UpdateChannel.STABLE)
            Prefs.setLastUpdate(appContext, System.currentTimeMillis())
            if (!silent) _events.tryEmit("yt-dlp обновлён до последней версии")
        } catch (e: Exception) {
            if (!silent) _events.tryEmit("Не удалось обновить: ${e.message?.take(120) ?: "нет сети"}")
        } finally {
            _engine.value = EngineState.Ready
        }
    }

    // ───────────────────────── Очередь ─────────────────────────

    /** @return false, если в тексте не нашлось ссылки. */
    fun enqueue(text: String, quality: Quality): Boolean {
        val url = extractUrl(text) ?: return false
        val item = DownloadItem(id = UUID.randomUUID().toString(), url = url, quality = quality)
        _items.update { listOf(item) + it }
        launchProcess(item.id)
        return true
    }

    fun cancel(id: String) {
        val item = find(id) ?: return
        if (!item.isActive) return
        patch(id) { it.copy(status = Status.CANCELED) }
        scope.launch {
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (_: Throwable) {
            }
        }
    }

    fun retry(id: String) {
        val item = find(id) ?: return
        if (item.isActive) return
        patch(id) {
            it.copy(status = Status.QUEUED, progress = 0f, etaSeconds = 0, error = null, resultUri = null)
        }
        launchProcess(id)
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id && !it.isActive } }
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.isActive } }
    }

    private fun launchProcess(id: String) {
        DownloadService.start(appContext)
        scope.launch { semaphore.withPermit { process(id) } }
    }

    // ───────────────────────── Сама загрузка ─────────────────────────

    private suspend fun process(id: String) {
        val ctx = appContext
        val item = find(id) ?: return
        if (item.status == Status.CANCELED) return

        // Ждём, пока движок распакуется / обновится.
        _engine.first { it is EngineState.Ready || it is EngineState.Error }
        (_engine.value as? EngineState.Error)?.let {
            fail(id, "Не удалось запустить загрузчик: ${it.message}")
            return
        }

        val tmpDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tmp/$id").apply { mkdirs() }
        try {
            val ydl = YoutubeDL.getInstance()

            // 1. Название и превью
            patchActive(id) { it.copy(status = Status.FETCHING, progress = 0f, error = null) }
            val info = ydl.getInfo(YoutubeDLRequest(item.url).apply { addOption("--no-playlist") })
            patchActive(id) { it.copy(title = info.title, thumbnail = info.thumbnail) }
            if (find(id)?.status == Status.CANCELED) return

            // 2. Скачивание
            patchActive(id) { it.copy(status = Status.DOWNLOADING) }
            val request = buildRequest(item, tmpDir)
            ydl.execute(request = request, processId = id, callback = { progress, eta, _ ->
                patchActive(id) {
                    it.copy(
                        status = Status.DOWNLOADING,
                        progress = (progress / 100f).coerceIn(0f, 1f),
                        etaSeconds = eta,
                    )
                }
            })
            if (find(id)?.status == Status.CANCELED) return

            // 3. Сохранение в галерею
            patchActive(id) { it.copy(status = Status.PROCESSING, progress = 1f, etaSeconds = 0) }
            val file = tmpDir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() !in TEMP_EXTENSIONS }
                .maxByOrNull { it.length() }
                ?: throw IOException("Файл не найден после загрузки")
            val saved = saveToMediaStore(ctx, file, item.quality.audioOnly)
            patchActive(id) {
                it.copy(
                    status = Status.DONE,
                    resultUri = saved.uri.toString(),
                    mimeType = saved.mime,
                    savedTo = saved.folder,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = find(id)
            if (current != null && current.status != Status.CANCELED) {
                fail(id, friendlyError(e.message ?: e.toString()))
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun buildRequest(item: DownloadItem, tmpDir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(item.url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--windows-filenames")
        request.addOption("-N", "4") // параллельная загрузка фрагментов
        request.addOption("-o", "${tmpDir.absolutePath}/%(title).100s.%(ext)s")

        if (item.quality.audioOnly) {
            request.addOption("-f", "ba/b")
            request.addOption("-x")
            request.addOption("--audio-format", "m4a")
        } else {
            request.addOption("-f", "bv*+ba/b")
            // Лучшее разрешение не выше выбранного; при равенстве предпочитаем H.264 + AAC (играет везде).
            val res = item.quality.maxHeight?.let { "res:$it" } ?: "res"
            request.addOption("-S", "$res,vcodec:h264,acodec:m4a")
            request.addOption("--merge-output-format", "mp4/mkv")
        }
        return request
    }

    // ───────────────────────── Сохранение в MediaStore ─────────────────────────

    private class Saved(val uri: Uri, val mime: String, val folder: String)

    private fun saveToMediaStore(ctx: Context, file: File, preferAudio: Boolean): Saved {
        val mime = guessMime(file, preferAudio)
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, folder) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to "Movies/VideoSaver"
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume) to "Music/VideoSaver"
            else -> MediaStore.Downloads.getContentUri(volume) to "Download/VideoSaver"
        }

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Не удалось создать файл в галерее")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл для записи")
            out.use { output -> file.inputStream().use { it.copyTo(output) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Saved(uri, mime, folder)
    }

    private fun guessMime(file: File, preferAudio: Boolean): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> if (preferAudio) "audio/mp4" else "video/mp4"
            "mkv" -> "video/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (preferAudio) "audio/mp4" else "video/mp4"
        }
    }

    // ───────────────────────── Вспомогательное ─────────────────────────

    private fun find(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun patch(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** Меняет элемент только если он ещё активен (чтобы запоздавший прогресс не «воскресил» отменённую загрузку). */
    private fun patchActive(id: String, transform: (DownloadItem) -> DownloadItem) {
        patch(id) { if (it.isActive) transform(it) else it }
    }

    private fun fail(id: String, message: String) {
        patch(id) { it.copy(status = Status.FAILED, error = message) }
    }

    private fun friendlyError(raw: String): String {
        val lower = raw.lowercase()
        val detail = raw.lines()
            .lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?.substringAfter("ERROR:")
            ?.trim()
            ?.take(220)
            ?: raw.take(220)

        val main = when {
            "unsupported url" in lower ->
                "Ссылка не поддерживается или это не страница с видео."
            "sign in to confirm" in lower ->
                "YouTube просит подтвердить, что вы не бот. Попробуйте позже или обновите yt-dlp (кнопка вверху)."
            "login required" in lower || "log in" in lower || "cookies" in lower || "private" in lower ->
                "Нужен вход в аккаунт: видео приватное или доступно только авторизованным (так часто у Instagram)."
            "429" in lower || "too many requests" in lower ->
                "Слишком много запросов — подождите немного и повторите."
            "unable to resolve" in lower || "name or service not known" in lower ||
                "network is unreachable" in lower || "failed to resolve" in lower ->
                "Нет подключения к интернету."
            "video unavailable" in lower || "not available" in lower || "removed" in lower ->
                "Видео недоступно: удалено или ограничено в вашем регионе."
            else -> null
        }
        return if (main != null) "$main\n$detail" else detail
    }

    private val TEMP_EXTENSIONS = setOf("part", "ytdl", "temp", "tmp", "json", "aria2")
}
