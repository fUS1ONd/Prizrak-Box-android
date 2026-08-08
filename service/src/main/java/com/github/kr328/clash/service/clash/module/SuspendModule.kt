package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
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

                        // Пока экран был выключен, ядро было усыплено и данные о живости
                        // нод протухли: без форс-чека первое открытое приложение ждало бы
                        // штатного тика health-check. Стреляем на каждое включение экрана,
                        // без порогов: мигание экраном схлопывает дебаунс ядра (~1 с).
                        Clash.forceHealthCheckAll()

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
}