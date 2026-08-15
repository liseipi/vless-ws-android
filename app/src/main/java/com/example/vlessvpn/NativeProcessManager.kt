package com.example.vlessvpn

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
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

            // The bundled binary is a statically linked Go executable. Go's
            // crypto/x509 package looks for a system CA bundle at a handful of
            // hardcoded Linux-distro paths (e.g. /etc/ssl/certs/ca-certificates.crt)
            // — none of which exist on Android. With no root CAs found, EVERY TLS
            // handshake fails with "certificate signed by unknown authority",
            // regardless of whether the server's certificate is actually valid.
            // Go respects the SSL_CERT_FILE env var to override that lookup, so we
            // point it at a CA bundle we ship as an asset.
            val caBundlePath = ensureCaBundle()
            if (caBundlePath != null) {
                pb.environment()["SSL_CERT_FILE"] = caBundlePath
            }

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

    /**
     * Copies the bundled CA certificate bundle (assets/cacert.pem) out to a real
     * file the native process can read — assets live inside the APK and aren't
     * directly accessible via a filesystem path. Cached after the first call.
     */
    private fun ensureCaBundle(): String? {
        val outFile = File(context.filesDir, "cacert.pem")
        return try {
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open("cacert.pem").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "failed to prepare CA bundle: ${e.message}")
            null
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

    fun startTun2socks(tunFd: FileDescriptor, socksPort: Int, mtu: Int, onLog: (String) -> Unit): Boolean {
        val binPath = nativeLibPath("libtun2socks.so")
        // Android's process-spawning code closes every file descriptor above
        // stdin/stdout/stderr before exec()'ing a child — regardless of the
        // FD_CLOEXEC flag. That's exactly why passing "-device fd://<N>" for our
        // own TUN fd number never works here: no matter what number we pass, the
        // child never actually has it open ("bad file descriptor").
        //
        // Standard fds 0/1/2 are the one exception — they're explicitly
        // preserved/redirectable. So instead we duplicate the TUN fd onto our
        // own stdin, tell the child to inherit our stdin, and point tun2socks
        // at fd 0 instead of the real fd number.
        val args = listOf(
            binPath,
            "-device", "fd://0",
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-mtu", mtu.toString(),
            "-loglevel", "info"
        )
        Log.i(TAG, "starting tun2socks: $args")

        var savedStdin: FileDescriptor? = null
        return try {
            savedStdin = Os.dup(FileDescriptor.`in`)
            Os.dup2(tunFd, 0)

            val pb = ProcessBuilder(args)
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            tun2socksProcess = proc
            pumpOutput(proc, "tun2socks", onLog)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start tun2socks", e)
            onLog("启动 tun2socks 失败: ${e.message}")
            false
        } finally {
            // Restore our own stdin so nothing else in this process is affected
            // by having briefly pointed fd 0 at the TUN device.
            try {
                if (savedStdin != null) {
                    Os.dup2(savedStdin, 0)
                    Os.close(savedStdin)
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to restore stdin: ${e.message}")
            }
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

    /** True if the vless client process we started is still alive (hasn't exited/crashed). */
    fun isVlessAlive(): Boolean = vlessProcess?.isAlive == true

    /** True if the tun2socks process we started is still alive (hasn't exited/crashed). */
    fun isTunAlive(): Boolean = tun2socksProcess?.isAlive == true

    fun isRunning(): Boolean = isVlessAlive() && isTunAlive()

    /** Stops processes we currently hold a [Process] handle for (this instance only). */
    fun stopAll() {
        tun2socksProcess?.destroy()
        vlessProcess?.destroy()
        tun2socksProcess = null
        vlessProcess = null
    }

    /**
     * Best-effort cleanup of orphaned vless/tun2socks binaries left running from a
     * previous connection attempt that didn't shut down cleanly (e.g. the app process
     * was killed without going through [stopAll]). Since a fresh [NativeProcessManager]
     * instance has no [Process] handle to those orphans, we fall back to `pkill -f` on
     * the binary's full path, which is unique to this app install and only matches our
     * own processes. This is safe to call even when nothing is orphaned.
     */
    fun killOrphans() {
        killByPath(nativeLibPath("libvlessclient.so"))
        killByPath(nativeLibPath("libtun2socks.so"))
    }

    private fun killByPath(path: String) {
        try {
            val proc = ProcessBuilder("pkill", "-f", path).start()
            proc.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "pkill unavailable or failed for $path: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NativeProcessManager"
    }
}
