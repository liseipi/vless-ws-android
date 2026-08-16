package com.example.vlessvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.InetAddress

class VlessVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private lateinit var processManager: NativeProcessManager

    /** Guards against a second connect() running concurrently (e.g. duplicate taps,
     *  or a stray onStartCommand while a previous attempt is still in flight). Without
     *  this, two overlapping connect() calls fight over the same local SOCKS port and
     *  TUN interface, which is what produced the "address already in use" /
     *  "bad file descriptor" failures. */
    @Volatile
    private var isConnecting = false

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
                if (isConnecting) {
                    Log.w(TAG, "Already connecting, ignoring duplicate start request")
                    return START_STICKY
                }
                val config = ConfigStore.load(this)
                if (!config.isValid()) {
                    broadcastStatus("No valid configuration selected")
                    stopSelf()
                    return START_NOT_STICKY
                }
                isConnecting = true
                broadcastStatus("Connecting…")
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                connect(config)
            }
        }
        return START_STICKY
    }

    private fun connect(config: VpnConfig) {
        Thread {
            try {
                // Always start from a clean slate. If a previous attempt crashed or
                // was killed without calling disconnect(), its native processes and/or
                // TUN interface may still be alive and will collide with the new ones
                // (stale port binding, invalidated fd once a second establish() runs).
                processManager.stopAll()
                processManager.killOrphans()
                tunInterface?.close()
                tunInterface = null

                val resolvedConfig = resolveServerAddress(config)

                val started = processManager.startVlessClient(resolvedConfig) { line ->
                    Log.d(TAG, line)
                }
                if (!started) {
                    broadcastStatus("Failed to start VLESS client")
                    processManager.stopAll()
                    stopSelf()
                    return@Thread
                }
                // Give the process a brief moment to crash-fast on bad args, a
                // port already in use, etc., instead of trusting that "the process
                // launched" means "the process is actually working".
                Thread.sleep(300)
                if (!processManager.isVlessAlive()) {
                    broadcastStatus("VLESS client exited immediately — check server/port/UUID, or a previous connection didn't clean up")
                    processManager.stopAll()
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

                // Crucial: exclude our own app from the tunnel. The native vless
                // client process runs under this same app's UID, and it needs a
                // real network path to actually reach the VLESS server and resolve
                // its DNS. Without this, its own outbound traffic (including DNS
                // lookups) gets captured by our own TUN interface and routed into
                // tun2socks -> SOCKS5 -> vless client — a circular dependency that
                // can never resolve ("connection refused" on every DNS lookup).
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "failed to exclude self from VPN routing: ${e.message}")
                }

                val vpnIface = builder.establish()
                if (vpnIface == null) {
                    broadcastStatus("Failed to establish TUN interface")
                    processManager.stopAll()
                    stopSelf()
                    return@Thread
                }
                tunInterface = vpnIface

                val tunStarted = processManager.startTun2socks(vpnIface.fileDescriptor, config.socksPort, mtu) { line ->
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
                Thread.sleep(300)
                if (!processManager.isTunAlive()) {
                    broadcastStatus("tun2socks exited immediately after starting")
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
                processManager.stopAll()
                stopSelf()
            } finally {
                isConnecting = false
            }
        }.start()
    }

    /**
     * The bundled vless-client binary is a statically linked Go executable. Go's
     * own (non-cgo) DNS resolver reads /etc/resolv.conf directly — a file that
     * doesn't exist / isn't populated for regular apps on Android, since Android
     * delivers DNS config to apps through netd instead of a resolv.conf file.
     * With no nameservers found, Go's resolver falls back to querying localhost
     * (127.0.0.1:53 / [::1]:53), where nothing is listening — hence
     * "connection refused" on every lookup, no matter which candidate server IP
     * it tries, and regardless of any VPN routing.
     *
     * We sidestep this by resolving the hostname ourselves through Android's
     * proper DNS path (java.net.InetAddress, which works correctly) and handing
     * the vless client a literal IP for -host — it then never needs to do its
     * own DNS lookup. -sni / -ws-host stay as the original domain so TLS and
     * the HTTP Host header still match what the server expects.
     */
    private fun resolveServerAddress(config: VpnConfig): VpnConfig {
        return try {
            // Prefer IPv4: getByName() hands the Go client whatever comes first
            // (usually the AAAA record), and on this device's network the IPv6
            // path to Cloudflare measured ~75% packet loss while IPv4 was clean.
            // A dead IPv6 uplink makes the client's WSS connect fail its retries
            // and silently stop relaying — the local SOCKS port keeps accepting,
            // so the UI says Connected but no traffic flows (DNS retry storms).
            // Fall back to IPv6 only when the domain has no A record at all.
            val addrs = InetAddress.getAllByName(config.host)
            val chosen = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
            val resolvedIp = chosen.hostAddress
            if (resolvedIp.isNullOrBlank() || resolvedIp == config.host) {
                config
            } else {
                Log.i(TAG, "resolved ${config.host} -> $resolvedIp (${if (chosen is Inet4Address) "IPv4" else "IPv6"})")
                config.copy(
                    host = resolvedIp,
                    sni = config.sni.ifBlank { config.host },
                    wsHost = config.wsHost.ifBlank { config.host }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to pre-resolve ${config.host}, vless client will try its own DNS: ${e.message}")
            config
        }
    }

    private fun disconnect() {
        processManager.stopAll()
        tunInterface?.close()
        tunInterface = null
        isConnecting = false
        broadcastStatus("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        processManager.stopAll()
        tunInterface?.close()
        tunInterface = null
        isConnecting = false
        super.onDestroy()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    private fun broadcastStatus(text: String) {
        lastStatusText = text
        // Primary delivery path: call the in-process listener directly. On
        // HyperOS (Xiaomi) the implicit sendBroadcast below never even reaches
        // the system — the intent vanishes before AMS enqueues it (confirmed via
        // `dumpsys activity broadcasts`: our STATUS_UPDATE never appears in the
        // broadcast history while same-window system broadcasts do), so the UI
        // would sit at "Connecting…" until the 15s timeout falsely reports
        // "Disconnected" even though the tunnel is up. Activity and service run
        // in the same process, so a plain callback is both simpler and immune to
        // ROM-level broadcast filtering. The broadcast is kept as a fallback.
        statusListener?.invoke(text)
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

        /**
         * The most recent status text this service broadcast, kept in-process so a
         * freshly (re)created MainActivity can learn the real current state
         * immediately — instead of always assuming "Disconnected" and waiting for a
         * broadcast that will never come if the tunnel was already up and running
         * in the background. Resets to the default on a fresh process start, which
         * is correct: if the whole app process died, the tunnel died with it.
         */
        @Volatile
        var lastStatusText: String = "Disconnected"
            private set

        /**
         * In-process subscriber notified (on whatever thread broadcastStatus ran)
         * with each status change. The Activity sets this in onCreate and clears
         * it in onDestroy; it must hop to the main thread before touching views.
         */
        @Volatile
        var statusListener: ((String) -> Unit)? = null
    }
}
