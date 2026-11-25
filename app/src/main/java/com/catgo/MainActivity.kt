package com.catgo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.random.Random

internal data class PairedDevice(val name: String, val address: String)

class MainActivity : ComponentActivity() {
    internal val prefs by lazy { getSharedPreferences("bluetooth_test", MODE_PRIVATE) }
    internal var status by mutableStateOf<Map<String, Any?>>(emptyMap())
        private set
    internal var devices by mutableStateOf<List<PairedDevice>>(emptyList())
        private set
    internal var message by mutableStateOf("")
    internal var serviceReady by mutableStateOf(false)
        private set
    private var service: LocationService? = null
    private var bound = false
    private val bluetoothPermission: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) refreshDevices() else message = "请在系统应用权限中允许附近设备"
    }
    private val bluetoothSettingsResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshDevices()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) message = "可在系统应用设置中开启通知"
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as LocationService.LocalBinder).service().also {
                status = it.snapshot
                it.listener = { state -> status = state }
            }
            serviceReady = true
            if (hasBluetoothPermission()) refreshDevices()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceReady = false
            status = emptyMap()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bound = bindService(Intent(this, LocationService::class.java), connection, BIND_AUTO_CREATE)
        setContent { CatgoApp(this) }
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    internal fun refreshDevices() = operation {
        if (!hasBluetoothPermission()) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return@operation
        }
        devices = emptyList()
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: error("此设备不支持蓝牙")
        check(adapter.isEnabled) { "请先打开系统蓝牙" }
        devices = adapter.bondedDevices.map { PairedDevice(it.name ?: "未命名设备", it.address) }.sortedBy { it.name }
    }

    internal fun saveLocal(value: String): Boolean = operation {
        check(status["active"] != true) { "请先断开当前连接" }
        val local = value.trim().uppercase(Locale.ROOT)
        Wire.mac(local)
        prefs.edit().putString("local", local).apply()
    }

    @SuppressLint("MissingPermission")
    internal fun connect(remote: String) = operation {
        check(hasBluetoothPermission()) { "请先刷新设备并允许附近设备权限" }
        check(serviceReady && status["active"] != true) { "蓝牙服务暂不可用，请稍后重试" }
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: error("此设备不支持蓝牙")
        check(adapter.isEnabled && adapter.bondedDevices.any { it.address == remote }) { "请先在系统蓝牙设置中配对 iPhone" }
        val savedLocal = prefs.getString("local", "").orEmpty()
        val local = if (runCatching { Wire.mac(savedLocal) }.isSuccess) {
            savedLocal
        } else {
            val address = Random.nextBytes(6)
            // Set the local bit and clear the multicast bit.
            address[0] = ((address[0].toInt() and 0xFC) or 0x02).toByte()
            if (address[0] == 2.toByte() && address.drop(1).all { it == 0.toByte() }) {
                address[5] = 1
            }
            val generated = address.joinToString(":") { "%02X".format(it) }
            prefs.edit().putString("local", generated).apply()
            generated
        }
        startForegroundService(Intent(this, LocationService::class.java).setAction(LocationService.CONNECT)
            .putExtra("remote", remote).putExtra("local", local))
        prefs.edit().putString("remote", remote).apply()
    }

    internal fun applyTarget(lat: String, lon: String, altitude: String) = operation {
        val fix = Fix(lat.trim().toDoubleOrNull() ?: Double.NaN, lon.trim().toDoubleOrNull() ?: Double.NaN,
            if (altitude.isBlank()) 0.0 else altitude.trim().toDoubleOrNull() ?: Double.NaN)
        (service ?: error("蓝牙服务尚未就绪")).apply(fix)
        prefs.edit().putString("lat", fix.lat.toString()).putString("lon", fix.lon.toString())
            .putString("altitude", fix.altitude.toString()).apply()
    }

    internal fun disconnect() { service?.disconnect() }
    internal fun bluetoothSettings() = operation { bluetoothSettingsResult.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    internal fun deviceInfo() = operation { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
    internal fun notifications() = operation {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    internal fun operation(action: () -> Unit): Boolean {
        return try {
            message = ""
            action()
            true
        } catch (exception: Exception) {
            message = exception.message ?: "操作失败"
            false
        }
    }

    override fun onDestroy() {
        service?.listener = null
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
