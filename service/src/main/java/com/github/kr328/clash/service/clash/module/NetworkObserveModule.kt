package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.*
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.asSocketAddressText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NetworkObserveModule(service: Service) : Module<Network>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val networks: Channel<Network> = Channel(Channel.UNLIMITED)
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private data class NetworkInfo(
        @Volatile var losingMs: Long = 0,
        @Volatile var dnsList: List<InetAddress> = emptyList()
    ) {
        fun isAvailable(): Boolean = losingMs < System.currentTimeMillis()
    }

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()

    @Volatile
    private var curDnsList = emptyList<String>()

    // Сеть-победитель по приоритету транспорта (см. transportToInt). null — сетей нет.
    // Отдельный флаг отличает «ещё не выбирали» от «выбрали, но сетей не было»:
    // первое заполнение — не смена сети, появление сети после полного пропадания — смена.
    @Volatile
    private var bestNetwork: Network? = null

    @Volatile
    private var bestNetworkKnown = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            networkInfos[network] = NetworkInfo()
            notifyNetworkChangeIfNeeded()
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i("NetworkObserve onLosing network=$network")
            networkInfos[network]?.losingMs = System.currentTimeMillis() + maxMsToLive
            notifyDnsChange()

            networks.trySend(network)
        }

        override fun onLost(network: Network) {
            Log.i("NetworkObserve onLost network=$network")
            networkInfos.remove(network)
            notifyNetworkChangeIfNeeded()
            notifyDnsChange()

            networks.trySend(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // На onAvailable capabilities сети ещё не известны и
            // getNetworkCapabilities возвращает null — только что появившийся WiFi
            // получает худший ранг и победителем не становится. Реальный ранг
            // известен здесь, поэтому победителя пересчитываем и на этом событии.
            notifyNetworkChangeIfNeeded()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i("NetworkObserve onLinkPropertiesChanged network=$network $linkProperties")
            networkInfos[network]?.dnsList = linkProperties.dnsServers
            notifyNetworkChangeIfNeeded()
            notifyDnsChange()

            networks.trySend(network)
        }

        override fun onUnavailable() {
            Log.i("NetworkObserve onUnavailable")
        }
    }

    private fun register(): Boolean {
        Log.i("NetworkObserve start register")
        return try {
            connectivity.registerNetworkCallback(request, callback)

            true
        } catch (e: Exception) {
            Log.w("NetworkObserve register failed", e)

            false
        }
    }

    private fun unregister(): Boolean {
        Log.i("NetworkObserve start unregister")
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve unregister failed", e)
        }

        return false
    }

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        return transportToInt(entry.key) + (if (entry.value.isAvailable()) 0 else 10)
    }

    private fun transportToInt(network: Network): Int {
        val capabilities = connectivity.getNetworkCapabilities(network)
        // calculate priority based on transport type, available state
        // lower value means higher priority
        // wifi > ethernet > usb tethering > bluetooth tethering > cellular > satellite > other
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
            // TRANSPORT_LOWPAN / TRANSPORT_THREAD / TRANSPORT_WIFI_AWARE are not for general internet access, which will not set as default route.
            else -> 20
        }
    }

    // Явный сигнал ядру «дефолтная сеть сменилась» (см. docs/adr/0001).
    // Победитель пересчитывается на каждом событии; сигнал уходит только при его
    // фактической смене, поэтому смена DNS без смены сети форс-чек не вызывает.
    // При пропадании всех сетей сигнала нет (стрелять некуда), но null запоминается:
    // появление сети после полного пропадания — тоже смена.
    private fun notifyNetworkChangeIfNeeded() {
        val newBest = selectBestNetwork()
        val prevBest = bestNetwork
        if (!bestNetworkKnown) {
            bestNetworkKnown = true
            bestNetwork = newBest
            return
        }
        if (newBest == prevBest) {
            return
        }
        bestNetwork = newBest
        if (newBest != null) {
            Log.i("NetworkObserve best network changed $prevBest -> $newBest")
            Clash.forceHealthCheckAll()
        }
    }

    // Победитель считается по транспорту и только по нему. Слагаемое
    // isAvailable() из networkToInt здесь не годится: losingMs задаётся один раз
    // в onLosing и потом никем не снимается, поэтому по истечении maxMsToLive
    // сеть молча возвращает себе балл — и следующее же произвольное событие
    // выдало бы «сеть сменилась» без всякой смены сети.
    private fun selectBestNetwork(): Network? {
        val best = networkInfos.keys.minByOrNull { transportToInt(it) } ?: return null
        val prev = bestNetwork

        // Ничья по транспорту (две WiFi-сети) не должна давать сигнал: порядок
        // обхода ConcurrentHashMap не определён, и победитель прыгал бы сам по
        // себе. Пока прежний в строю и не хуже нового — держимся за него.
        if (prev != null && networkInfos.containsKey(prev) && transportToInt(prev) <= transportToInt(best)) {
            return prev
        }

        return best
    }

    private fun notifyDnsChange() {
        val dnsList = (networkInfos.asSequence().minByOrNull { networkToInt(it) }?.value?.dnsList
            ?: emptyList()).map { x -> x.asSocketAddressText(53) }
        val prevDnsList = curDnsList
        if (dnsList.isNotEmpty() && prevDnsList != dnsList) {
            Log.i("notifyDnsChange $prevDnsList -> $dnsList")
            curDnsList = dnsList
            Clash.notifyDnsChanged(dnsList)
        }
    }

    override suspend fun run() {
        register()

        try {
            while (true) {
                val quit = select {
                    networks.onReceive {
                        enqueueEvent(it)

                        false
                    }
                }
                if (quit) {
                    return
                }
            }
        } finally {
            withContext(NonCancellable) {
                unregister()

                Log.i("NetworkObserve dns = []")
                Clash.notifyDnsChanged(emptyList())
            }
        }
    }
}