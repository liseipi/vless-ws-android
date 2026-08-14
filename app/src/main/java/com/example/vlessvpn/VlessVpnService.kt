package com.example.vlessvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat

class VlessVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private lateinit var processManager: NativeProcessManager

    override fun onCreate() {
        super.onCreate()
        processManager = NativeProcessManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            else -> {
                val config = ConfigStore.load(this)
                if (!config.isValid()) {
                    broadcastStatus("No valid configuration selected")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                connect(config)
            }
        }
        return START_STICKY
    }

    private fun connect(config: VpnConfig) {
        Thread {
            try {
                val started = processManager.startVlessClient(config) { line ->
                    Log.d(TAG, line)
                }
                if (!started) {
                    broadcastStatus("Failed to start VLESS client")
                    stopSelf()
                    return@Thread
                }

                if (!processManager.waitForSocksReady(config.socksPort)) {
                    broadcastStatus("Timed out waiting for local SOCKS5 port")
                    processManager.stopAll()
                    stopSelf()
                    return@Thread
                }

                val mtu = 1500
                val builder = Builder()
                    .setSession("VlessVPN")
                    .setMtu(mtu)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")

                tunInterface = builder.establish()
                val vpnIface = tunInterface
                if (vpnIface == null) {
                    broadcastStatus("Failed to establish TUN interface")
                    processManager.stopAll()
                    stopSelf()
                    return@Thread
                }

                // 关键一步：Android 应用默认可能给这个 fd 设置了 close-on-exec，
                // 子进程 exec 之后这个 fd 会被自动关掉，tun2socks 就拿不到它了。
                // 显式清掉 FD_CLOEXEC，保证 fork 出来的 tun2socks 子进程能继承到
                // 同一个底层文件描述符。
                Os.fcntlInt(vpnIface.fileDescriptor, OsConstants.F_SETFD, 0)

                val fdInt = vpnIface.fd
                val tunStarted = processManager.startTun2socks(fdInt, config.socksPort, mtu) { line ->
                    Log.d(TAG, line)
                }
                if (!tunStarted) {
                    broadcastStatus("Failed to start tun2socks")
                    processManager.stopAll()
                    vpnIface.close()
                    tunInterface = null
                    stopSelf()
                    return@Thread
                }

                updateNotification("Connected")
                broadcastStatus("Connected")
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                broadcastStatus("Connection failed: ${e.message}")
                stopSelf()
            }
        }.start()
    }

    private fun disconnect() {
        processManager.stopAll()
        tunInterface?.close()
        tunInterface = null
        broadcastStatus("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        processManager.stopAll()
        tunInterface?.close()
        tunInterface = null
        super.onDestroy()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    private fun broadcastStatus(text: String) {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra(EXTRA_STATUS_TEXT, text)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN 状态", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VlessVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "VlessVpnService"
        private const val CHANNEL_ID = "vless_vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.example.vlessvpn.DISCONNECT"
        const val ACTION_STATUS_UPDATE = "com.example.vlessvpn.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
    }
}
