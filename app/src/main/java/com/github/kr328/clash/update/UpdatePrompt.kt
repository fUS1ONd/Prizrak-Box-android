package com.github.kr328.clash.update

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.design.R as DesignR

/**
 * Предложение обновиться — одно на обе точки входа: и на фоновую проверку из
 * главного экрана, и на ручную из справки.
 */

/**
 * Потолок ченджлога в диалоге. Дальше текст обрезается многоточием — кому
 * обрезанного мало, идёт на страницу релиза.
 */
private const val CHANGELOG_LIMIT = 2000

/**
 * Показывает предложение обновиться с ченджлогом релиза. Отказ никуда не
 * записывается: предложение повторится на следующей проверке.
 */
fun Activity.showUpdateAvailableDialog(update: UpdateChecker.CheckResult.UpdateAvailable) {
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(DesignR.string.update_available_title)
        .setMessage(updateMessage(update))
        .setPositiveButton(DesignR.string.update_download) { _, _ ->
            UpdateChecker.startDownload(this, update)
        }
        .setNegativeButton(DesignR.string.cancel, null)

    if (update.releaseUrl.isNotEmpty()) {
        builder.setNeutralButton(DesignR.string.update_release_page, null)
    }

    val dialog = builder.create()

    // Страница релиза открывается, не закрывая предложение: пользователь идёт
    // читать ченджлог целиком и возвращается к кнопке «Скачать».
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
            }
        }
    }

    dialog.show()
}

/**
 * Текст предложения: версия и следом описание релиза как есть. Markdown не
 * рендерится — тянуть зависимость ради трёх решёток не стоит.
 */
private fun Activity.updateMessage(update: UpdateChecker.CheckResult.UpdateAvailable): String {
    val header = getString(DesignR.string.update_available_message, update.displayVersion())
    val changelog = update.changelog.trim()

    // Пустое описание не должно ломать предложение — обновиться по-прежнему можно.
    if (changelog.isEmpty()) return header

    val trimmed = if (changelog.length > CHANGELOG_LIMIT) {
        changelog.take(CHANGELOG_LIMIT).trimEnd() + "…"
    } else {
        changelog
    }

    return "$header\n\n$trimmed"
}
