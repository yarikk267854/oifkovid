package com.videosaver.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.videosaver.app.DownloadItem
import com.videosaver.app.DownloadRepository
import com.videosaver.app.EngineState
import com.videosaver.app.Prefs
import com.videosaver.app.Quality
import com.videosaver.app.Status
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sharedText: String?,
    onSharedConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by DownloadRepository.items.collectAsStateWithLifecycle()
    val engine by DownloadRepository.engine.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var input by rememberSaveable { mutableStateOf("") }
    var quality by remember { mutableStateOf(Prefs.quality(context)) }

    // Разрешение на уведомления (Android 13+)
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        DownloadRepository.events.collect { snackbar.showSnackbar(it) }
    }

    fun start(text: String) {
        if (DownloadRepository.enqueue(text, quality)) {
            input = ""
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Загрузка добавлена")
            }
        } else {
            scope.launch { snackbar.showSnackbar("Не нашёл ссылку — вставьте адрес видео") }
        }
    }

    // Ссылка пришла через «Поделиться» — качаем сразу.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            start(sharedText)
            onSharedConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("VideoSaver") },
                actions = {
                    IconButton(
                        onClick = { DownloadRepository.updateEngine() },
                        enabled = engine is EngineState.Ready,
                    ) {
                        Icon(Icons.Rounded.Update, contentDescription = "Обновить yt-dlp")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EngineBanner(engine) }

            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true,
                    label = { Text("Ссылка на видео") },
                    placeholder = { Text("https://…") },
                    supportingText = { Text("YouTube, Instagram, TikTok или прямая ссылка на файл") },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    trailingIcon = {
                        if (input.isEmpty()) {
                            IconButton(onClick = { input = readClipboard(context) }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Вставить")
                            }
                        } else {
                            IconButton(onClick = { input = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (input.isNotBlank()) start(input) }
                    ),
                )
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Quality.entries.forEach { q ->
                        FilterChip(
                            selected = q == quality,
                            onClick = {
                                quality = q
                                Prefs.setQuality(context, q)
                            },
                            label = { Text(q.label) },
                            leadingIcon = {
                                Icon(
                                    if (q.audioOnly) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { start(input) },
                    enabled = input.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Скачать", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Загрузки",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (items.any { !it.isActive }) {
                        TextButton(onClick = { DownloadRepository.clearFinished() }) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Очистить")
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item { EmptyState() }
            } else {
                items(items, key = { it.id }) { item -> DownloadCard(item) }
            }
        }
    }
}

@Composable
private fun EngineBanner(engine: EngineState) {
    when (engine) {
        EngineState.Ready -> Unit
        EngineState.Loading, EngineState.Updating -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (engine == EngineState.Loading) "Подготавливаю загрузчик…" else "Обновляю yt-dlp…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        is EngineState.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    "Не удалось запустить загрузчик: ${engine.message}",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Пока пусто",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Вставьте ссылку или нажмите «Поделиться» в YouTube, Instagram или TikTok и выберите VideoSaver",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadCard(item: DownloadItem) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.thumbnail != null) {
                        AsyncImage(
                            model = item.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            if (item.quality.audioOnly) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        item.title ?: item.url,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statusText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == Status.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.isActive) {
                    IconButton(onClick = { DownloadRepository.cancel(item.id) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Отменить")
                    }
                } else {
                    IconButton(onClick = { DownloadRepository.remove(item.id) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Убрать из списка")
                    }
                }
            }

            if (item.isActive) {
                Spacer(Modifier.height(10.dp))
                if (item.status == Status.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            if (item.status == Status.DONE && item.resultUri != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { openFile(context, item) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Открыть")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { shareFile(context, item) }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Поделиться")
                    }
                }
            }

            if (item.status == Status.FAILED || item.status == Status.CANCELED) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { DownloadRepository.retry(item.id) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Повторить")
                }
            }
        }
    }
}

private fun statusText(item: DownloadItem): String = when (item.status) {
    Status.QUEUED -> "В очереди…"
    Status.FETCHING -> "Получаю информацию о видео…"
    Status.DOWNLOADING -> buildString {
        append("Скачивание ${(item.progress * 100).toInt()}%")
        if (item.etaSeconds > 0) append(" · осталось ${formatEta(item.etaSeconds)}")
    }
    Status.PROCESSING -> "Обработка и сохранение…"
    Status.DONE -> "Готово · ${item.savedTo ?: "галерея"}"
    Status.FAILED -> item.error ?: "Ошибка"
    Status.CANCELED -> "Отменено"
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

private fun openFile(context: Context, item: DownloadItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(item.resultUri), item.mimeType ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, item: DownloadItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mimeType ?: "video/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.resultUri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Поделиться"))
}
