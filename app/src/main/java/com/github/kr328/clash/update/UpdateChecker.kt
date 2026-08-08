package com.github.kr328.clash.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.github.kr328.clash.design.R as DesignR

/**
 * Ввод-вывод обновлений: два запроса к GitHub, загрузка APK, уведомление и
 * вызов установщика. Решение «обновляться ли и чем» принимает чистая функция
 * [decideUpdate] — там же живут все правила каналов.
 */
object UpdateChecker {

    private const val REPO = "fUS1ONd/Prizrak-Box-android"

    private const val PREF_NAME = "prizrak_update"
    private const val PREF_LAST_CHECK = "last_check_ms"
    private const val PREF_DOWNLOAD_ID = "download_id"
    private const val APK_FILE_NAME = "prizrak-box-update.apk"

    const val CHANNEL_ID = "prizrak_update_channel"
    const val NOTIFICATION_ID = 9001

    const val ACTION_SHOW_UPDATE = "com.github.kr328.clash.action.SHOW_UPDATE"
    const val EXTRA_VERSION = "update_version"
    const val EXTRA_VERSION_CODE = "update_version_code"
    const val EXTRA_URL = "update_url"
    const val EXTRA_CHANGELOG = "update_changelog"
    const val EXTRA_RELEASE_URL = "update_release_url"

    /** Канал сборки. Неизвестное значение канала — сборка без обновлений. */
    private val updateChannel: UpdateChannel?
        get() = UpdateChannel.of(BuildConfig.UPDATE_CHANNEL)

    /** Сборка без известного канала не проверяет обновления вовсе. */
    private val checkIntervalMs: Long
        get() = updateChannel?.backgroundCheckIntervalMs ?: Long.MAX_VALUE

    sealed class CheckResult {
        data class UpdateAvailable(
            val versionName: String,
            val versionCode: Long,
            val downloadUrl: String,
            val changelog: String,
            val releaseUrl: String,
        ) : CheckResult() {
            /**
             * Как версия показывается пользователю. Код версии в скобках
             * обязателен: у alpha-сборок имя версии постоянно, и без кода
             * соседние сборки в предложении неотличимы.
             */
            fun displayVersion(): String = "$versionName ($versionCode)"
        }

        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck > checkIntervalMs
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val channel = updateChannel
            ?: return@withContext CheckResult.Error(
                context.getString(DesignR.string.update_error_no_channel)
            )

        try {
            val releaseJson = httpGet(channel.releaseApiUrl(REPO))

            // Метаданные сборки лежат отдельным ассетом рядом с APK — это второй
            // и последний сетевой запрос проверки.
            val metadataUrl = findAssetUrl(releaseJson, BUILD_METADATA_ASSET)
                ?: return@withContext CheckResult.Error(
                    context.getString(DesignR.string.update_error_malformed)
                )
            val metadataJson = httpGet(metadataUrl)

            // Время проверки отмечается только при удавшемся запросе: иначе
            // сеть без доступа съедала бы интервал молча.
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                .apply()

            when (
                val decision = decideUpdate(
                    channel = channel,
                    installedVersionCode = installedVersionCode(context),
                    installedApplicationId = context.packageName,
                    supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
                    releaseJson = releaseJson,
                    metadataJson = metadataJson,
                )
            ) {
                is UpdateDecision.Available -> CheckResult.UpdateAvailable(
                    versionName = decision.versionName,
                    versionCode = decision.versionCode,
                    downloadUrl = decision.downloadUrl,
                    changelog = decision.changelog,
                    releaseUrl = decision.releaseUrl,
                )
                is UpdateDecision.UpToDate -> CheckResult.UpToDate
                is UpdateDecision.Failed -> CheckResult.Error(
                    context.getString(decision.reason.messageRes())
                )
            }
        } catch (_: Exception) {
            // Сеть отвалилась или источник обновлений недоступен: техническую
            // причину пользователю показывать нечего, остальные сообщения
            // проверки тоже локализованы.
            CheckResult.Error(context.getString(DesignR.string.update_error_network))
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }

            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun installedVersionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun UpdateDecision.Reason.messageRes(): Int = when (this) {
        UpdateDecision.Reason.MALFORMED_RELEASE -> DesignR.string.update_error_malformed
        UpdateDecision.Reason.WRONG_CHANNEL -> DesignR.string.update_error_wrong_channel
        UpdateDecision.Reason.FOREIGN_APPLICATION -> DesignR.string.update_error_foreign_application
        UpdateDecision.Reason.NO_SUITABLE_ASSET -> DesignR.string.update_error_no_asset
    }

    fun startDownload(context: Context, update: CheckResult.UpdateAvailable) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Remove previous download if any
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prevId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (prevId != -1L) {
            runCatching { dm.remove(prevId) }
        }

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle(context.getString(DesignR.string.update_notification_title))
            .setDescription(context.getString(DesignR.string.update_download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(request)
        prefs.edit().putLong(PREF_DOWNLOAD_ID, id).apply()
    }

    fun installDownloadedApk(context: Context) {
        val file = apkFile(context)
        if (!file.exists()) return

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.update.provider",
                file
            )
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    /**
     * Предложение из уведомления: решение уже принято проверкой, повторно в
     * сеть за ним ходить незачем. null — интент не про обновление.
     */
    fun readUpdateFromIntent(intent: Intent): CheckResult.UpdateAvailable? {
        if (intent.action != ACTION_SHOW_UPDATE) return null
        val versionName = intent.getStringExtra(EXTRA_VERSION) ?: return null
        val downloadUrl = intent.getStringExtra(EXTRA_URL) ?: return null

        return CheckResult.UpdateAvailable(
            versionName = versionName,
            versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, 0L),
            downloadUrl = downloadUrl,
            changelog = intent.getStringExtra(EXTRA_CHANGELOG).orEmpty(),
            releaseUrl = intent.getStringExtra(EXTRA_RELEASE_URL).orEmpty(),
        )
    }

    fun showUpdateNotification(context: Context, update: CheckResult.UpdateAvailable) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_UPDATE
            putExtra(EXTRA_VERSION, update.versionName)
            putExtra(EXTRA_VERSION_CODE, update.versionCode)
            putExtra(EXTRA_URL, update.downloadUrl)
            putExtra(EXTRA_CHANGELOG, update.changelog)
            putExtra(EXTRA_RELEASE_URL, update.releaseUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(DesignR.string.update_notification_title))
            .setContentText(
                context.getString(DesignR.string.update_notification_text, update.displayVersion())
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(DesignR.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    context.getString(DesignR.string.update_notification_channel_description)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
}
