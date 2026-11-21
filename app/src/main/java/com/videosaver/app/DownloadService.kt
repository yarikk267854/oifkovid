package com.videosaver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: не даёт системе убить приложение, пока идут загрузки,
 * показывает прогресс и присылает уведомление, когда всё готово.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Загрузки", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Завершённые загрузки", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = DownloadRepository.items.value.filter { it.isActive }
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS,
            progressNotification(active),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        job?.cancel()
        job = scope.launch {
            DownloadRepository.items.collect { handle(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(list: List<DownloadItem>) {
        val nm = getSystemService(NotificationManager::class.java)

        // Уведомления о завершении (по разу на каждое состояние).
        list.filter { it.status == Status.DONE || it.status == Status.FAILED }
            .filter { notified.add("${it.id}:${it.status}") }
            .forEach { item ->
                if (!DownloadRepository.uiVisible) {
                    nm.notify(item.id.hashCode(), finishedNotification(item))
                }
            }

        val active = list.filter { it.isActive }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            nm.notify(NOTIF_PROGRESS, progressNotification(active))
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(active: List<DownloadItem>): Notification {
        val first = active.firstOrNull()
        val title = when {
            first == null -> "Подготовка…"
            active.size > 1 -> "Загрузки: ${active.size}"
            else -> first.title ?: "Получаю информацию…"
        }
        val builder = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())

        if (first != null && first.status == Status.DOWNLOADING) {
            val percent = (first.progress * 100).toInt()
            builder.setContentText("$percent%")
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText(if (first?.status == Status.PROCESSING) "Сохраняю…" else "Подождите…")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun finishedNotification(item: DownloadItem): Notification {
        val builder = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setAutoCancel(true)

        if (item.status == Status.DONE && item.resultUri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(item.resultUri), item.mimeType ?: "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            builder.setContentTitle(item.title ?: "Загрузка завершена")
                .setContentText("Сохранено в ${item.savedTo}")
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        item.id.hashCode(),
                        view,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
        } else {
            builder.setContentTitle("Не удалось скачать")
                .setContentText(item.error?.lines()?.firstOrNull() ?: "Ошибка")
                .setContentIntent(openAppIntent())
        }
        return builder.build()
    }

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private val notified = mutableSetOf<String>()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
