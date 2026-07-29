package com.example.vlessvpn

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class NativeProcessManager(private val context: Context) {

    private var vlessProcess: Process? = null
    private var tun2socksProcess: Process? = null

    private fun nativeLibPath(name: String): String =
        "${context.applicationInfo.nativeLibraryDir}/$name"

    fun startVlessClient(config: VpnConfig, onLog: (String) -> Unit): Boolean {
        val binPath = nativeLibPath("libvlessclient.so")
        val args = listOf(binPath) + config.toVlessClientArgs()
        Log.i(TAG, "starting vless client: $args")
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            vlessProcess = proc
            pumpOutput(proc, "vless", onLog)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start vless client", e)
            onLog("启动 vless 客户端失败: ${e.message}")
            false
        }
    }

    fun waitForSocksReady(port: Int, timeoutMs: Long = 8000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }

    fun startTun2socks(tunFd: Int, socksPort: Int, mtu: Int, onLog: (String) -> Unit): Boolean {
        val binPath = nativeLibPath("libtun2socks.so")
        val args = listOf(
            binPath,
            "-device", "fd://$tunFd",
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-mtu", mtu.toString(),
            "-loglevel", "info"
        )
        Log.i(TAG, "starting tun2socks: $args")
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            tun2socksProcess = proc
            pumpOutput(proc, "tun2socks", onLog)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start tun2socks", e)
            onLog("启动 tun2socks 失败: ${e.message}")
            false
        }
    }

    private fun pumpOutput(proc: Process, tag: String, onLog: (String) -> Unit) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLog("[$tag] $line")
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    fun stopAll() {
        tun2socksProcess?.destroy()
        vlessProcess?.destroy()
        tun2socksProcess = null
        vlessProcess = null
    }

    fun isRunning(): Boolean =
        (vlessProcess?.isAlive == true) && (tun2socksProcess?.isAlive == true)

    companion object {
        private const val TAG = "NativeProcessManager"
    }
}
