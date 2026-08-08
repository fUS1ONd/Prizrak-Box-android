package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
    private companion object {
        // Минимальный интервал между форс-чеками по включению экрана. Дебаунс
        // ядра схлопывает только пачку в пределах секунды, а экран включают
        // десятки раз в день: без своего порога каждая разблокировка стоила бы
        // залпа проб по всем нодам подписки плюс дозвонов гейта готовности —
        // заметный расход батареи и мобильного трафика.
        const val SCREEN_ON_FORCE_CHECK_INTERVAL_MS = 60_000L
    }

    // elapsedRealtime, а не currentTimeMillis: перевод часов не должен ни
    // открывать, ни запирать порог.
    private var lastForceCheckAt: Long? = null

    override suspend fun run() {
        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true

        Clash.suspendCore(!interactive)

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        try {
            while (true) {
                when (screenToggle.receive().action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Clash.suspendCore(false)

                        // Экран включили — пользователь сейчас пойдёт в сеть, и данные о
                        // живости нод к этому моменту могли протухнуть: Doze режет фоновые
                        // таймеры, и штатный тик health-check мог не отработать. Форсим
                        // проверку, но с порогом (см. forceHealthCheckThrottled).
                        //
                        // Ядро при этом НЕ усыплено: Clash.suspendCore на нашей стороне —
                        // no-op (core/src/main/golang/native/tunnel/suspend.go), там прямо
                        // запрещено дёргать OnSuspend/OnRunning ядра.
                        forceHealthCheckThrottled()

                        Log.d("Clash resumed")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Clash.suspendCore(true)

                        Log.d("Clash suspended")
                    }
                    else -> {
                        // unreachable

                        Clash.healthCheckAll()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                Clash.suspendCore(false)
            }
        }
    }

    // Форс-чек не чаще раза в SCREEN_ON_FORCE_CHECK_INTERVAL_MS. Вызывается
    // только из цикла run(), одной корутиной, — синхронизация не нужна.
    private fun forceHealthCheckThrottled() {
        val now = SystemClock.elapsedRealtime()
        val last = lastForceCheckAt
        if (last != null && now - last < SCREEN_ON_FORCE_CHECK_INTERVAL_MS) {
            return
        }

        lastForceCheckAt = now
        Clash.forceHealthCheckAll()
    }
}