package vn.unlimit.vpngate.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.AuthMethod
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Сканер серверов VPN Gate.
 * Перебирает непроверенные серверы из базы, пытается подключиться к каждому
 * через SoftEtherVpnService, помечает успешные (isVerified=true).
 *
 * Работает в фоне, сообщает о прогрессе через ScanListener.
 */
object ServerScanner {

    private const val TAG = "ServerScanner"
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val PAUSE_BETWEEN_ATTEMPTS_MS = 1_000L

    /**
     * Интерфейс для UI — подписывается на события сканирования.
     */
    interface ScanListener {
        fun onScanStarted(total: Int)
        fun onScanProgress(current: Int, total: Int, serverName: String, success: Boolean)
        fun onScanComplete(verifiedCount: Int)
        fun onScanStopped(verifiedCount: Int)
        fun onScanError(message: String)
    }

    @Volatile private var isRunning: Boolean = false
    @Volatile private var shouldStop: Boolean = false
    private var scanJob: Job? = null
    private var scope: CoroutineScope? = null
    private var listener: ScanListener? = null

    fun isScanning(): Boolean = isRunning

    fun setListener(l: ScanListener?) {
        listener = l
    }

    /**
     * Запускает сканирование всех НЕпроверенных серверов.
     * Проверенные (isVerified = true) пропускаются.
     */
    fun startScan(context: Context) {
        if (isRunning) {
            Log.w(TAG, "Scan already running, ignoring start request")
            return
        }
        val app = context.applicationContext as App
        val dao = app.vpnGateItemDao

        val allServers = dao.getAll()
        val candidates = allServers.filter { !it.isVerified }

        if (candidates.isEmpty()) {
            Log.i(TAG, "No servers to scan — all are already verified")
            listener?.onScanError("Нет серверов для проверки — все уже проверены")
            return
        }

        isRunning = true
        shouldStop = false
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        scanJob = newScope.launch {
            val total = candidates.size
            listener?.onScanStarted(total)
            Log.i(TAG, "Scan started: $total servers to check")

            var verifiedCount = 0
            var index = 0

            for (server in candidates) {
                if (shouldStop) {
                    Log.i(TAG, "Scan stopped by user")
                    break
                }
                index++

                val success = tryConnectToServer(context, server)

                if (success) {
                    // Помечаем в БД как проверенный
                    val updated = server.copy(isVerified = true)
                    dao.insert(updated)
                    verifiedCount++
                    Log.i(TAG, "✅ ${server.hostName} — verified")
                } else {
                    Log.i(TAG, "❌ ${server.hostName} — failed")
                }

                listener?.onScanProgress(index, total, server.hostName, success)

                // Небольшая пауза, чтобы сервис успел освободиться
                delay(PAUSE_BETWEEN_ATTEMPTS_MS)
            }

            isRunning = false
            if (shouldStop) {
                listener?.onScanStopped(verifiedCount)
            } else {
                listener?.onScanComplete(verifiedCount)
            }
            Log.i(TAG, "Scan finished. Verified: $verifiedCount")
        }
    }

    /**
     * Останавливает текущее сканирование (после текущей попытки).
     */
    fun stopScan(context: Context) {
        if (!isRunning) return
        Log.i(TAG, "Stop requested")
        shouldStop = true
        // Форсированно отключаем текущее соединение
        try {
            val intent = Intent(context, SoftEtherVpnService::class.java).apply {
                action = SoftEtherVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending disconnect", e)
        }
    }

    /**
     * Полностью освобождает ресурсы сканера (например, при закрытии приложения).
     */
    fun shutdown() {
        shouldStop = true
        scanJob?.cancel()
        scanJob = null
        scope?.cancel()
        scope = null
        isRunning = false
    }

    /**
     * Пытается подключиться к одному серверу. Возвращает true при успехе.
     */
    private suspend fun tryConnectToServer(context: Context, server: VPNGateItem): Boolean {
        // Определяем порт и режим (UDP/TCP)
        val useUdp = server.isUdpOnly || server.seUdpPort > 0
        val serverPort = when {
            server.isUdpOnly -> server.seUdpPort
            useUdp && server.seUdpPort > 0 -> server.seUdpPort
            server.seTcpPort > 0 -> server.seTcpPort
            else -> 443
        }

        // Формируем имя сессии
        val sessionName = "${server.countryShort ?: "??"}_${server.hostName}"

        val config = ConnectionConfig(
            serverHost = server.ip ?: server.hostName,
            serverPort = serverPort,
            username = "vpn",
            password = "vpn",
            virtualHub = "vpngate",
            sessionName = sessionName,
            localAddress = "10.21.0.2",
            prefixLength = 19,
            dnsServer = "8.8.8.8",
            secondaryDnsServer = "8.8.4.4",
            routes = listOf(Route("0.0.0.0", 0)),
            mtu = 1500,
            isMetered = false,
            useTcp = !useUdp,
            useUdp = useUdp,
            udpPort = server.seUdpPort,
            udpOnly = server.isUdpOnly,
            authMethod = AuthMethod.AUTO,
            clientProductName = "VPN Gate Connector",
            clientVersion = "1.0.0",
            clientBuild = 1
        )

        val result = CompletableDeferred<Boolean>()

        val stateListener = object : SoftEtherVpnService.StateListener {
            override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
                when (state) {
                    SoftEtherVpnService.STATE_CONNECTED -> {
                        if (!result.isCompleted) result.complete(true)
                    }
                    SoftEtherVpnService.STATE_ERROR -> {
                        if (!result.isCompleted) result.complete(false)
                    }
                    // STATE_DISCONNECTED игнорируем: он приходит и как «стартовое»
                    // состояние при подписке, и как событие от старого сервиса.
                    // Если сервер не ответит — сработает таймаут.
                }
            }
        }

        // Подписываемся ДО запуска сервиса, чтобы не пропустить CONNECTED
        SoftEtherVpnService.addStateListener(stateListener)

        try {
            val intent = Intent(context, SoftEtherVpnService::class.java).apply {
                action = SoftEtherVpnService.ACTION_CONNECT
                putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            // Ждём либо CONNECTED/ERROR, либо таймаут
            val success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { result.await() } ?: false

            // Всегда отправляем DISCONNECT, чтобы освободить сервис
            try {
                val stopIntent = Intent(context, SoftEtherVpnService::class.java).apply {
                    action = SoftEtherVpnService.ACTION_DISCONNECT
                }
                context.startService(stopIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service for ${server.hostName}", e)
            }

            // Даём сервису время полностью освободиться
            delay(2_000L)

            return success
        } catch (e: Exception) {
            Log.e(TAG, "Exception during connect to ${server.hostName}", e)
            return false
        } finally {
            SoftEtherVpnService.removeStateListener(stateListener)
        }
    }
}