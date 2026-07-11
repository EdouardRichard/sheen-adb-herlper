package com.sheen.adb.core

data class AdbEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
    }

    fun redacted(): String = "<地址已脱敏>:$port"
}

sealed interface EndpointParseResult {
    data class Valid(val endpoint: AdbEndpoint) : EndpointParseResult
    data class Invalid(val reason: String) : EndpointParseResult
}

object AdbEndpointParser {
    private val hostname = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    fun parse(raw: String): EndpointParseResult {
        val value = raw.trim()
        if (value.isEmpty()) return EndpointParseResult.Invalid("请输入 IP:端口")

        val (host, portText) = when {
            value.startsWith('[') -> {
                val closing = value.indexOf(']')
                if (closing <= 1 || closing + 1 >= value.length || value[closing + 1] != ':') {
                    return EndpointParseResult.Invalid("IPv6 地址必须使用 [地址]:端口")
                }
                value.substring(1, closing) to value.substring(closing + 2)
            }

            value.count { it == ':' } == 1 -> value.substringBefore(':') to value.substringAfter(':')
            value.count { it == ':' } > 1 -> return EndpointParseResult.Invalid("IPv6 地址必须使用方括号")
            else -> return EndpointParseResult.Invalid("地址必须包含端口")
        }

        if (!isValidHost(host)) return EndpointParseResult.Invalid("主机名或 IP 地址无效")
        val port = portText.toIntOrNull()
            ?: return EndpointParseResult.Invalid("端口必须是 1 到 65535 的数字")
        if (port !in 1..65535) return EndpointParseResult.Invalid("端口必须是 1 到 65535")
        return EndpointParseResult.Valid(AdbEndpoint(host, port))
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.any { it.isWhitespace() }) return false
        if (':' in host) {
            val address = host.substringBefore('%')
            val zone = host.substringAfter('%', "")
            val addressValid = address.isNotBlank() &&
                address.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it in ":." }
            val zoneValid = '%' !in host || zone.isNotBlank() && zone.all { it.isLetterOrDigit() || it in "_-" }
            return addressValid && zoneValid
        }
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return ipv4Parts.all { it.length <= 3 && it.toIntOrNull() in 0..255 }
        }
        return hostname.matches(host) && !host.contains("..")
    }
}
