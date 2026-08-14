package com.example.vlessvpn

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Parses and builds `vless://` share links, e.g.
 * vless://uuid@host:port?encryption=none&security=tls&type=ws&host=wsHost&path=%2Fapi&sni=example.com#remark
 */
object VlessLink {

    fun parse(raw: String): VpnConfig? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

        return try {
            val withoutScheme = trimmed.substring("vless://".length)

            val hashIndex = withoutScheme.indexOf('#')
            val remark = if (hashIndex >= 0) {
                decode(withoutScheme.substring(hashIndex + 1))
            } else ""
            val beforeHash = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme

            val atIndex = beforeHash.lastIndexOf('@')
            if (atIndex < 0) return null
            val uuid = beforeHash.substring(0, atIndex)
            val rest = beforeHash.substring(atIndex + 1)

            val qIndex = rest.indexOf('?')
            val hostPort = if (qIndex >= 0) rest.substring(0, qIndex) else rest
            val query = if (qIndex >= 0) rest.substring(qIndex + 1) else ""

            val colonIndex = hostPort.lastIndexOf(':')
            if (colonIndex < 0) return null
            val host = hostPort.substring(0, colonIndex)
            val port = hostPort.substring(colonIndex + 1)
            if (host.isBlank() || uuid.isBlank()) return null

            val params = mutableMapOf<String, String>()
            if (query.isNotBlank()) {
                query.split('&').forEach { pair ->
                    if (pair.isBlank()) return@forEach
                    val idx = pair.indexOf('=')
                    if (idx < 0) {
                        params[pair] = ""
                    } else {
                        params[pair.substring(0, idx)] = decode(pair.substring(idx + 1))
                    }
                }
            }

            val security = params["security"] ?: ""
            val useTls = security.equals("tls", true) || security.equals("reality", true)
            val path = (params["path"] ?: "/").ifBlank { "/" }
            val sni = params["sni"] ?: params["peer"] ?: ""
            val wsHost = params["host"] ?: ""
            val token = params["token"] ?: ""

            VpnConfig(
                id = UUID.randomUUID().toString(),
                name = remark.ifBlank { host },
                host = host,
                port = port.ifBlank { "443" },
                path = path,
                useTls = useTls,
                sni = sni,
                wsHost = wsHost,
                uuid = uuid,
                token = token
            )
        } catch (e: Exception) {
            null
        }
    }

    fun build(config: VpnConfig): String {
        val params = mutableListOf<String>()
        params += "encryption=none"
        params += "type=ws"
        params += "security=${if (config.useTls) "tls" else "none"}"
        if (config.path.isNotBlank()) params += "path=${encode(config.path)}"
        if (config.wsHost.isNotBlank()) params += "host=${encode(config.wsHost)}"
        if (config.sni.isNotBlank()) params += "sni=${encode(config.sni)}"
        if (config.token.isNotBlank()) params += "token=${encode(config.token)}"

        val query = params.joinToString("&")
        val remark = encode(config.name.ifBlank { config.host })
        return "vless://${config.uuid}@${config.host}:${config.port}?$query#$remark"
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
