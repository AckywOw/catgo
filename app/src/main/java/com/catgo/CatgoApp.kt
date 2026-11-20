package com.catgo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import java.util.Locale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val light = lightColorScheme(
    primary = Color(0xFF785800), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD75A), onPrimaryContainer = Color(0xFF241B00),
    surface = Color(0xFFFCFAF6), background = Color(0xFFFCFAF6),
    onSurface = Color(0xFF23211C), onSurfaceVariant = Color(0xFF5F5A50),
    outline = Color(0xFF81796B),
)
private val dark = darkColorScheme(
    primary = Color(0xFFFFD75A), onPrimary = Color(0xFF241B00),
    primaryContainer = Color(0xFFFFD75A), onPrimaryContainer = Color(0xFF241B00),
    surface = Color(0xFF1C1B18), background = Color(0xFF1C1B18),
    onSurfaceVariant = Color(0xFFD0C7B8),
)
private val defaultPoint = Point(39.9042, 116.4074)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatgoApp(activity: MainActivity) {
    val prefs = activity.prefs
    var page by rememberSaveable { mutableStateOf("定位") }
    var selected by rememberSaveable { mutableStateOf(prefs.getString("remote", "").orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(prefs.getString("lat", "").orEmpty()) }
    var lon by rememberSaveable { mutableStateOf(prefs.getString("lon", "").orEmpty()) }
    var alt by rememberSaveable { mutableStateOf(prefs.getString("altitude", "0").orEmpty()) }
    var local by rememberSaveable { mutableStateOf(prefs.getString("local", "").orEmpty()) }
    var favorites by remember { mutableStateOf(readFavorites(activity)) }
    val active = activity.status["active"] == true
    var prepare by rememberSaveable { mutableStateOf(false) }
    val home = "定位"
    fun back() { page = if (page == "关于") "设置" else home }
    if (page != home) androidx.activity.compose.BackHandler { back() }
    fun savePlaces(places: List<Place>) {
        favorites = places
        writeFavorites(activity, places)
    }
    fun usePoint(point: Point) {
        lat = point.lat.toString()
        lon = point.lon.toString()
        prepare = true
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
        activity.activationMessage?.let { ActivationDialog(activity, it) }
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(if (page == home) "catgo" else page) },
                navigationIcon = {
                    if (page != home) TextButton(onClick = ::back) { Text("返回") }
                },
                actions = {
                    if (page == home) TextButton(onClick = {
                        local = prefs.getString("local", "").orEmpty()
                        page = "设置"
                    }) { Text("设置") }
                },
            )
        }) { insets ->
            val body = Modifier.fillMaxSize().padding(insets).imePadding()
            when {
                page == home -> HomePage(
                    activity, body, selected, { selected = it }, prepare, { prepare = it }, lat, lon, alt,
                    { value ->
                        val point = runCatching { Point.parse(value) }.getOrNull()
                        if (point == null) lat = value else usePoint(point)
                    },
                    { value ->
                        val point = runCatching { Point.parse(value) }.getOrNull()
                        if (point == null) lon = value else usePoint(point)
                    },
                    { alt = it }, { page = "地点搜索" }, { page = "收藏" },
                    { point, name -> savePlaces(favorites + Place(name, "", point)) },
                    { local = prefs.getString("local", "").orEmpty(); page = "设置" },
                )
                page == "地点搜索" -> SearchPage(activity, body, pointFromFields(lat, lon) ?: defaultPoint) {
                    usePoint(it)
                    page = home
                }
                page == "收藏" -> FavoritesPage(body, favorites, ::savePlaces) {
                    usePoint(it)
                    page = home
                }
                else -> key(page) {
                    Column(body.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ErrorText(activity.message)
                        when (page) {
                            "设置" -> SettingsPage(activity, local, { local = it }, active) { page = it }
                            "关于" -> {
                                PageTitle("catgo")
                                Text("版本 1.0.0 · Android ${android.os.Build.VERSION.RELEASE}")
                                Text("通过蓝牙连接已配对的 iPhone，发送选定的 WGS-84 坐标。")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    activity: MainActivity, modifier: Modifier, selected: String, select: (String) -> Unit,
    prepare: Boolean, showCoordinates: (Boolean) -> Unit, lat: String, lon: String, alt: String,
    changeLat: (String) -> Unit, changeLon: (String) -> Unit, changeAlt: (String) -> Unit,
    search: () -> Unit, favorites: () -> Unit, saveFavorite: (Point, String) -> Unit, settings: () -> Unit,
) {
    val status = activity.status
    val active = status["active"] == true
    val phase = status["phase"]
    val ready = phase in listOf("ready", "pending", "streaming")
    val connectedDevice = activity.devices.firstOrNull { it.address == status["remote"] }
    val device = activity.devices.firstOrNull { it.address == selected } ?: activity.devices.singleOrNull()
    val hasLocalAddress = activity.prefs.getString("local", "").orEmpty()
        .let { runCatching { Wire.mac(it) }.isSuccess }
    var more by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var favoriteName by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val point = pointFromFields(lat, lon)
    val latError = attempted && lat.trim().toDoubleOrNull()?.let { it in -90.0..90.0 } != true
    val lonError = attempted && lon.trim().toDoubleOrNull()?.let { it in -180.0..180.0 } != true
    val altError = attempted && alt.isNotBlank() &&
        alt.trim().toDoubleOrNull()?.let { it in -1000.0..100000.0 } != true
    val title = when {
        ready -> "应用位置"
        active -> "正在连接 iPhone"
        activity.devices.isEmpty() -> "先配对 iPhone"
        else -> "连接 iPhone"
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle(title)
            ErrorText(activity.message)
            if (phase == "error" || (!active && phase == "idle" && status["detail"] != "请选择已配对的 iPhone")) {
                status["detail"]?.toString()?.let { Text(it) }
            }

            if (active) {
                if (ready) {
                    Text("已连接 · ${connectedDevice?.name ?: "iPhone"}", style = MaterialTheme.typography.titleMedium)
                    when (phase) {
                        "pending" -> Text("已应用，等待 iPhone 的下一次位置请求。")
                        "streaming" -> Text("正在持续发送 · 已提交 ${status["reports"] ?: 0} 次")
                        else -> Text("输入 WGS-84 经纬度，或从搜索和收藏填入。")
                    }
                    val targetLat = status["targetLat"]
                    val targetLon = status["targetLon"]
                    if (targetLat != null && targetLon != null) Text("当前应用：$targetLat, $targetLon")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when (phase) {
                        "negotiating" -> "正在建立会话…"
                        "authenticating" -> "正在验证配件…"
                        "subscribing" -> "等待 iPhone 请求位置…"
                        else -> "正在建立蓝牙连接…"
                    })
                }
            }

            if (!active && activity.devices.isEmpty()) {
                Text("在系统蓝牙中配对 iPhone，完成后返回这里。")
            }
            if (!active && activity.devices.isNotEmpty()) {
                for (option in activity.devices) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(option.address == device?.address, role = Role.RadioButton) {
                                select(option.address)
                            }.heightIn(min = 56.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(option.address == device?.address, onClick = null)
                        Column {
                            Text(option.name)
                            if (activity.devices.size > 1) Text(option.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
                if (!hasLocalAddress) Text("首次连接需要填写这台安卓手机的蓝牙地址。")
            }
            if (!active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { activity.refreshDevices() }) { Text("刷新设备") }
                    if (activity.devices.isNotEmpty()) {
                        TextButton(onClick = { activity.bluetoothSettings() }) { Text("系统蓝牙") }
                    }
                }
                TextButton(onClick = { showCoordinates(!prepare) }) { Text(if (prepare) "收起坐标" else "准备坐标") }
            }
            if (active || prepare) {
                CoordinateField("纬度", lat, changeLat, if (latError) "请输入 -90 到 90 之间的纬度" else null)
                CoordinateField("经度", lon, changeLon, if (lonError) "请输入 -180 到 180 之间的经度" else null)
                HorizontalDivider()
                TextButton(onClick = { more = !more }) { Text(if (more) "收起更多选项" else "更多选项") }
                if (more) {
                    CoordinateField("海拔（米）", alt, changeAlt, if (altError) "请输入 -1000 到 100000 之间的海拔" else null)
                    TextButton(onClick = { favoriteName = "" }, enabled = point != null) { Text("收藏当前坐标") }
                    if (notice.isNotEmpty()) Text(notice)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = search) { Text("搜索地点") }
                TextButton(onClick = favorites) { Text("收藏") }
            }
            if (active) {
                TextButton(onClick = { details = !details }) { Text(if (details) "收起连接详情" else "连接详情") }
                if (details) {
                    Text(status["detail"]?.toString().orEmpty())
                    val flags = listOf("connected" to "蓝牙", "identified" to "识别",
                        "authenticated" to "认证", "subscribed" to "订阅")
                    Text(flags.joinToString(" · ") { (key, label) ->
                        "$label ${if (status[key] == true) "✓" else "○"}"
                    })
                }
                TextButton(onClick = activity::disconnect) { Text(if (ready) "断开连接" else "取消连接") }
            }
        }
        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            when {
                active -> {
                    PrimaryAction("应用位置") {
                        focus.clearFocus()
                        attempted = true
                        val altitude = if (alt.isBlank()) 0.0 else alt.trim().toDoubleOrNull()
                        if (altitude == null || altitude !in -1000.0..100000.0) more = true
                        else if (point != null) activity.applyTarget(lat, lon, alt)
                    }
                }
                activity.devices.isEmpty() -> PrimaryAction("去蓝牙设置") { activity.bluetoothSettings() }
                !hasLocalAddress -> PrimaryAction("填写蓝牙地址", onClick = settings)
                else -> PrimaryAction(
                    if (activity.authorizationBusy) "正在校验…" else "连接 iPhone",
                    enabled = device != null && activity.serviceReady && !activity.authorizationBusy,
                ) {
                    device?.let { activity.connect(it.address) }
                }
            }
        }
    }
    favoriteName?.let { name ->
        AlertDialog(
            onDismissRequest = { favoriteName = null },
            title = { Text("收藏坐标") },
            text = { OutlinedTextField(name, { favoriteName = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && point != null, onClick = {
                    point?.let { saveFavorite(it, name.trim()) }
                    favoriteName = null
                    notice = "已添加到收藏"
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { favoriteName = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ErrorText(message: String) {
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
    HorizontalDivider()
}

@Composable
private fun CoordinateField(label: String, value: String, change: (String) -> Unit, error: String?) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
            imeAction = if (label == "纬度") ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}

@Composable
private fun ActivationDialog(activity: MainActivity, message: String) {
    var code by remember { mutableStateOf("") }
    val busy = activity.authorizationBusy
    AlertDialog(
        onDismissRequest = activity::cancelActivation,
        title = { Text("设备激活") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message)
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }.take(12)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("12 位激活码") },
                    singleLine = true,
                    enabled = !busy,
                )
                ErrorText(activity.activationError)
            }
        },
        confirmButton = {
            TextButton(onClick = { activity.confirmActivation(code) }, enabled = !busy && code.length == 12) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = activity::cancelActivation, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun SettingsPage(activity: MainActivity, local: String, onLocal: (String) -> Unit,
                         active: Boolean, navigate: (String) -> Unit) {
    var notice by rememberSaveable { mutableStateOf("") }
    Section("本机蓝牙地址") {
        OutlinedTextField(local, onLocal, Modifier.fillMaxWidth(), enabled = !active,
            label = { Text("安卓手机的真实蓝牙 MAC") }, placeholder = { Text("AA:BB:CC:DD:EE:FF") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (activity.saveLocal(local)) {
                    onLocal(activity.prefs.getString("local", "").orEmpty())
                    notice = "本机蓝牙地址已保存"
                }
            }, enabled = !active) { Text("保存地址") }
            OutlinedButton(onClick = { activity.deviceInfo() }) { Text("关于手机") }
        }
    }
    val clipboard = LocalClipboardManager.current
    Section("本机唯一凭证") {
        val credential = activity.deviceCredential
        Text(credential)
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(credential))
            notice = "本机唯一凭证已复制"
        }, enabled = credential.length == 64) { Text("复制凭证") }
    }
    if (notice.isNotBlank()) Text(notice)
    Section("应用") {
        TextButton(onClick = { activity.notifications() }) { Text("蓝牙连接通知") }
        TextButton(onClick = { navigate("关于") }) { Text("关于 catgo") }
    }
}

@Composable
private fun SearchPage(activity: MainActivity, modifier: Modifier, center: Point, accept: (Point) -> Unit) {
    val mapSettings = remember { runCatching { activity.mapSettings.read() } }
    val settings = mapSettings.getOrDefault(mapOf("nearby" to true))
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Place>()) }
    var searchError by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(query) {
        results = emptyList()
        searchError = ""
        val text = query.trim()
        searching = text.isNotEmpty()
        if (text.isEmpty()) return@LaunchedEffect
        delay(450)
        val result = withContext(Dispatchers.IO) { runCatching { MapSearch.search(text, settings, center) } }
        results = result.getOrDefault(emptyList())
        searchError = when {
            result.isFailure -> "搜索失败，请检查网络、搜索服务或坐标格式。"
            results.isEmpty() -> "未找到匹配地点，试试地点全称或添加城市名。"
            else -> ""
        }
        searching = false
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AmapShareInput(accept)
        HorizontalDivider()
        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(),
            label = { Text("地点或经纬度") }, singleLine = true,
            trailingIcon = { if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("清除") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        if (query.isBlank()) Text("选择搜索结果后，经纬度会填入首页。")
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(searchError)
        for (place in results.take(8)) {
            TextButton(onClick = { focus.clearFocus(); accept(place.point) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun AmapShareInput(accept: (Point) -> Unit) {
    var sharedText by rememberSaveable { mutableStateOf("") }
    var resolved by remember { mutableStateOf<Place?>(null) }
    var error by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(sharedText) {
        if (sharedText.isBlank()) return@LaunchedEffect
        delay(450)
        val result = withContext(Dispatchers.IO) { runCatching { AmapShare.resolve(sharedText) } }
        resolved = result.getOrNull()
        error = when (val failure = result.exceptionOrNull()) {
            null -> ""
            is java.io.IOException -> "无法打开高德地图分享链接，请检查网络后重试。"
            is IllegalStateException -> failure.message.orEmpty()
            else -> "无法解析高德地图分享链接。"
        }
        resolving = false
    }

    OutlinedTextField(sharedText, {
        sharedText = it
        resolved = null
        error = ""
        resolving = it.isNotBlank()
    }, Modifier.fillMaxWidth(),
        label = { Text("高德地图分享链接") },
        placeholder = { Text("粘贴链接或完整分享文案") },
        trailingIcon = {
            if (sharedText.isNotEmpty()) TextButton(onClick = {
                sharedText = ""
                resolved = null
                error = ""
                resolving = false
            }) { Text("清除") }
        },
        maxLines = 3)
    if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorText(error)
    resolved?.let { place ->
        Text(place.name, style = MaterialTheme.typography.titleMedium)
        Text(String.format(Locale.US, "纬度 %.6f，经度 %.6f", place.point.lat, place.point.lon))
        TextButton(onClick = { focus.clearFocus(); accept(place.point) }) { Text("填入首页") }
    }
}

@Composable
private fun FavoritesPage(
    modifier: Modifier, favorites: List<Place>, save: (List<Place>) -> Unit, accept: (Point) -> Unit,
) {
    var removing by remember { mutableStateOf<Int?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (favorites.isEmpty()) {
            PageTitle("暂无收藏")
            Text("在首页输入有效经纬度，然后在「更多选项」中收藏当前坐标。")
        }
        favorites.forEachIndexed { index, place ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { accept(place.point) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                        Text("${place.point.lat}, ${place.point.lon}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { removing = index }) { Text("删除") }
            }
            HorizontalDivider()
        }
    }
    removing?.let { index ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除此收藏？") },
            text = { Text(favorites[index].name) },
            confirmButton = { TextButton(onClick = {
                save(favorites.filterIndexed { i, _ -> i != index })
                removing = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

private fun pointFromFields(lat: String, lon: String): Point? =
    runCatching { Point(lat.trim().toDouble(), lon.trim().toDouble()) }.getOrNull()

private fun readFavorites(activity: MainActivity): List<Place> {
    val list = runCatching { JSONArray(activity.prefs.getString("favorites", "[]")) }.getOrElse { JSONArray() }
    return (0 until list.length()).mapNotNull { index ->
        runCatching {
            val item = list.getJSONObject(index)
            Place(item.getString("name"), "", Point(item.getDouble("lat"), item.getDouble("lon")))
        }.getOrNull()
    }
}

private fun writeFavorites(activity: MainActivity, favorites: List<Place>) {
    val list = JSONArray()
    favorites.forEach { place ->
        list.put(JSONObject().put("name", place.name).put("lat", place.point.lat).put("lon", place.point.lon))
    }
    activity.prefs.edit().putString("favorites", list.toString()).apply()
}
