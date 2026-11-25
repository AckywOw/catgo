package com.catgo

import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection

internal object AmapShare {
    private const val maxResponseBytes = 1024 * 1024
    private val link = Regex("""(?:https://|androidamap://|iosamap://)[^\s'"<>]+""", RegexOption.IGNORE_CASE)
    private val parameter = Regex("""[?&](?:amp;)?(position|p|q|name)=([^&#\s'"<>]+)""", RegexOption.IGNORE_CASE)

    fun resolve(sharedText: String): Place {
        var uri = link.findAll(sharedText)
            .mapNotNull { match -> parseUri(match.value.trimEnd('。', '，', '；', '、', '）', '】', '》')) }
            .firstOrNull { isAmapWebUrl(it) || isAmapMarker(it) }
            ?: error("请粘贴高德地图分享链接或完整分享文案。")

        repeat(6) { hop ->
            extract(uri.rawQuery?.let { "?$it" }.orEmpty())?.let { return it }
            check(isAmapWebUrl(uri) && hop < 5) { "未能从高德地图链接中解析出经纬度。" }
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("高德地图链接缺少跳转地址。")
                    uri = uri.resolve(location)
                    check(isAmapWebUrl(uri) || isAmapMarker(uri)) { "高德地图链接跳转到了不支持的地址。" }
                    return@repeat
                }
                check(status in 200..299) { "无法打开高德地图分享链接，请检查链接是否有效。" }
                val body = connection.inputStream.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        check(buffer.size() + count <= maxResponseBytes) { "高德地图分享页面过大，无法解析。" }
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toString(Charsets.UTF_8.name())
                }
                return extract(body) ?: error("未能从高德地图链接中解析出经纬度。")
            } finally {
                connection.disconnect()
            }
        }
        error("高德地图链接跳转次数过多。")
    }

    private fun extract(text: String): Place? {
        val parameters = parameter.findAll(text)
            .mapNotNull { match ->
                val value = runCatching { URLDecoder.decode(match.groupValues[2], "UTF-8") }.getOrNull()
                value?.let { match.groupValues[1].lowercase() to it }
            }
            .toList()
        val markerName = parameters.firstOrNull { it.first == "name" }?.second.orEmpty()
        for ((key, value) in parameters) {
            val parts = value.split(',', limit = if (key == "p") 4 else 3)
            val (latitude, longitude, name) = when (key) {
                "position" -> Triple(parts.getOrNull(1), parts.first(), markerName)
                "p" -> Triple(parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(3).orEmpty())
                "q" -> Triple(parts.first(), parts.getOrNull(1), parts.getOrNull(2).orEmpty())
                else -> continue
            }
            val lat = latitude?.trim()?.toDoubleOrNull() ?: continue
            val lon = longitude?.trim()?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            return Place(name.trim().ifBlank { "高德地图分享地点" }, "", Point(lat, lon).wgs())
        }
        return null
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun isAmapWebUrl(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            listOf("amap.com", "autonavi.com").any { host == it || host.endsWith(".$it") }
    }

    private fun isAmapMarker(uri: URI): Boolean =
        uri.scheme?.lowercase() in setOf("androidamap", "iosamap") &&
            uri.host.equals("viewMap", ignoreCase = true) && uri.userInfo == null
}
