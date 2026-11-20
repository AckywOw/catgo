# catgo

以 Kotlin 和 Jetpack Compose 重写的独立安卓客户端，应用 ID 为 `com.catgo`。项目最低支持 Android 8.0（API 26），目标 SDK 为 API 36。项目沿用仓库根目录的 GPL-3.0 许可。

## 构建

安装 JDK 17、Android SDK 36 和 SDK Build Tools 35.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME`，准备下文所述的配件认证材料，再在本目录执行：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Gradle 输出 `app/build/outputs/apk/debug/app-debug.apk`，它使用本机调试证书签名。交付文件命名为 `catgo.apk`。

## 连接

1. 在两台设备的系统蓝牙设置中配对，然后打开 catgo。
2. 在设置保存安卓手机真实的本机蓝牙 MAC 地址。若系统允许，刷新已配对设备时也会读取并保存本机地址。
3. 选择 iPhone 并连接。直接输入 WGS-84 经纬度，或从地点搜索和收藏填入坐标，然后点击“应用位置”。

当前项目以新的应用 ID 安装，拥有独立的设置和收藏。地点搜索支持高德与 Photon。蓝牙会话依照 iPhone 发出的订阅周期发送定位数据，可通过首页或服务通知断开。

## 内置配件认证

请自行准备获准随 APK 分发的配件认证材料，并放入 `catgo/identity/`：

- `certificate.p7b`：PKCS#7 证书。
- `identity.pk8`：DER 格式的 P-256 PKCS#8 私钥。

目录内的 `.gitignore` 随源码分发，材料文件由 Git 忽略。Gradle 默认从该目录读取这两个文件，放好后即可执行上面的构建命令。

如需使用其他目录，可显式指定：

```sh
./gradlew :app:assembleDebug -PcatgoIdentityDir=/path/to/authorized-identity
```

应用直接读取安装包内的配件认证材料。构建时必须提供两份文件。APK 的持有者可读取随包分发的私钥。
