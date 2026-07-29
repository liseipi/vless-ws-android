package com.example.vlessvpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class VpnConfig(
    var host: String = "",
    var port: String = "443",
    var path: String = "/api",
    var useTls: Boolean = true,
    var sni: String = "",
    var wsHost: String = "",
    var uuid: String = "",
    var token: String = "",
    var socksPort: Int = 10808
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("path", path)
        put("useTls", useTls)
        put("sni", sni)
        put("wsHost", wsHost)
        put("uuid", uuid)
        put("token", token)
        put("socksPort", socksPort)
    }

    fun isValid(): Boolean = host.isNotBlank() && uuid.isNotBlank()

    fun toVlessClientArgs(): List<String> {
        val args = mutableListOf(
            "-host", host,
            "-port", port,
            "-path", path,
            "-tls=${if (useTls) "true" else "false"}",
            "-uuid", uuid,
            "-local-ip", "127.0.0.1",
            "-local-port", socksPort.toString(),
            "-insecure=false",
            "-log-level", "info"
        )
        if (token.isNotBlank()) args += listOf("-token", token)
        if (sni.isNotBlank()) args += listOf("-sni", sni)
        if (wsHost.isNotBlank()) args += listOf("-ws-host", wsHost)
        return args
    }

    companion object {
        fun fromJson(obj: JSONObject): VpnConfig = VpnConfig(
            host = obj.optString("host", ""),
            port = obj.optString("port", "443"),
            path = obj.optString("path", "/api"),
            useTls = obj.optBoolean("useTls", true),
            sni = obj.optString("sni", ""),
            wsHost = obj.optString("wsHost", ""),
            uuid = obj.optString("uuid", ""),
            token = obj.optString("token", ""),
            socksPort = obj.optInt("socksPort", 10808)
        )
    }
}

object ConfigStore {
    private const val PREFS_NAME = "vless_vpn_prefs"
    private const val KEY_CONFIG = "config_json"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): VpnConfig {
        val raw = prefs(context).getString(KEY_CONFIG, null) ?: return VpnConfig()
        return try {
            VpnConfig.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            VpnConfig()
        }
    }

    fun save(context: Context, config: VpnConfig) {
        prefs(context).edit().putString(KEY_CONFIG, config.toJson().toString()).apply()
    }
}
