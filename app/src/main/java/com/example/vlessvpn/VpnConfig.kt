package com.example.vlessvpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VpnConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
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
        put("id", id)
        put("name", name)
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

    /** Short transport tag shown in the configuration list, e.g. "TLS" or "WS". */
    fun transportTag(): String = if (useTls) "TLS" else "WS"

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
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", ""),
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

/**
 * Stores a list of saved [VpnConfig] entries plus which one is currently selected
 * for connecting. Automatically migrates data saved by the older single-config
 * version of this app (a single JSON object under "config_json").
 */
object ConfigStore {
    private const val PREFS_NAME = "vless_vpn_prefs"
    private const val KEY_CONFIGS = "configs_json"
    private const val KEY_SELECTED_ID = "selected_config_id"
    private const val KEY_CONFIG_LEGACY = "config_json"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(context: Context): MutableList<VpnConfig> {
        val p = prefs(context)
        val raw = p.getString(KEY_CONFIGS, null)
        if (raw == null) {
            // Try migrating the legacy single-config format.
            val legacyRaw = p.getString(KEY_CONFIG_LEGACY, null)
            val list = mutableListOf<VpnConfig>()
            if (legacyRaw != null) {
                try {
                    val legacy = VpnConfig.fromJson(JSONObject(legacyRaw))
                    if (legacy.name.isBlank()) legacy.name = "Default"
                    list.add(legacy)
                    saveAll(context, list)
                    setSelectedId(context, legacy.id)
                } catch (_: Exception) {
                    // ignore malformed legacy data
                }
            }
            return list
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VpnConfig>()
            for (i in 0 until arr.length()) {
                list.add(VpnConfig.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(context: Context, configs: List<VpnConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    fun getSelectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED_ID, null)

    fun setSelectedId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED_ID, id).apply()
    }

    /** Returns the currently selected config, falling back to the first saved one. */
    fun load(context: Context): VpnConfig {
        val configs = loadAll(context)
        val selectedId = getSelectedId(context)
        return configs.firstOrNull { it.id == selectedId } ?: configs.firstOrNull() ?: VpnConfig()
    }

    fun upsert(context: Context, config: VpnConfig) {
        val configs = loadAll(context)
        val idx = configs.indexOfFirst { it.id == config.id }
        if (idx >= 0) configs[idx] = config else configs.add(config)
        saveAll(context, configs)
    }

    fun delete(context: Context, id: String) {
        val configs = loadAll(context)
        configs.removeAll { it.id == id }
        saveAll(context, configs)
        if (getSelectedId(context) == id) {
            val next = configs.firstOrNull()
            if (next != null) {
                setSelectedId(context, next.id)
            } else {
                prefs(context).edit().remove(KEY_SELECTED_ID).apply()
            }
        }
    }
}
