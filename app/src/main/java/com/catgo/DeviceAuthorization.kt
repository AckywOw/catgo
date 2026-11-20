package com.catgo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

internal class DeviceAuthorization(private val context: Context) {
    data class Result(val status: String, val message: String = "")

    private val cache = context.getSharedPreferences("catgo_authorization", Context.MODE_PRIVATE)
    private val requests = Mutex()
    private val proof by lazy {
        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        check(!androidId.isNullOrBlank()) { "无法读取本机唯一凭证" }
        sha256("catgo-device-v1:$androidId")
    }
    val credential by lazy { sha256(proof) }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun metadata() = JSONObject()
        .put("brand", Build.BRAND)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("androidVersion", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT.toString())
        .put("securityPatch", Build.VERSION.SECURITY_PATCH)
        .put("buildId", Build.ID)
        .put("abi", Build.SUPPORTED_ABIS.joinToString(","))
        .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())

    suspend fun sync(allowOffline: Boolean): Result = requests.withLock {
        withContext(Dispatchers.IO) {
            try {
                request("sync", JSONObject().put("metadata", metadata()))
            } catch (error: SSLException) {
                throw IllegalStateException("无法验证授权服务器，请稍后重试", error)
            } catch (error: IOException) {
                val boot = bootCount()
                if (allowOffline && boot >= 0 && cache.getInt("boot", -1) == boot &&
                    cache.getString("credential", null) == credential &&
                    SystemClock.elapsedRealtime() < cache.getLong("validUntil", 0)
                ) Result("active") else throw IllegalStateException("无法连接授权服务器，请联网后重试", error)
            }
        }
    }

    suspend fun redeem(code: String): Result = requests.withLock {
        withContext(Dispatchers.IO) {
            try {
                request("redeem", JSONObject().put("code", code))
            } catch (error: IOException) {
                throw IllegalStateException("激活结果暂未确认，请联网后重试同一个激活码", error)
            }
        }
    }

    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun request(action: String, body: JSONObject): Result {
        val started = SystemClock.elapsedRealtime()
        val connection = URL("https://ios.iirrll.top/api/catgo/device/$action").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $proof")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val httpStatus = connection.responseCode
            if (httpStatus >= 500) throw IOException("授权服务暂不可用")
            val stream = if (httpStatus == 200) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { JSONObject(it.readText()) }
                ?: error("授权服务器返回异常")
            if (httpStatus != 200) error(response.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { "授权请求失败，请稍后重试" })
            val status = response.getString("status")
            when (status) {
                "active" -> {
                    val remaining = response.getLong("remainingMs")
                    check(remaining > 0) { "授权服务器返回异常" }
                    val validUntil = started + remaining
                    cache.edit().putString("credential", credential).putInt("boot", bootCount())
                        .putLong("validUntil", validUntil).apply()
                    check(SystemClock.elapsedRealtime() < validUntil) { "授权已到期，请重新连接" }
                }
                "expired", "disabled" -> cache.edit().clear().apply()
                else -> error("授权服务器返回异常")
            }
            return Result(status, response.optString("message"))
        } catch (error: JSONException) {
            throw IllegalStateException("授权服务器返回异常", error)
        } finally {
            connection.disconnect()
        }
    }
}
