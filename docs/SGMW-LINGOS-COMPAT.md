# 五菱 LingOS（SGMW）车机适配实录与通用修复方案

> 来源：2026-09-30 在五菱宝骏 LingOS 车机上的完整排障实录。所有结论均经实机验证。
> 目标：让 DiPlay（未来 50play）在其他"裁剪版"车机上通用，不再重复踩坑。

## 1. 车机环境画像（本次实测）

| 项目 | 值 |
|---|---|
| 机型 | 五菱宝骏 LingOS 车机 |
| SoC | 展锐 Unisoc `spm8666p2_64_mce`（arm64-v8a，4×A55 级别，性能弱） |
| 系统 | Android 9（SDK 28）userdebug，`zh-CN`，内核 4.4.146+ |
| adb | WiFi ADB 由车机"ADB操作"页面开启，端口 **5557**（不是默认 5555！） |
| root | adbd 可 `adb root`（userdebug），应用进程无 root |
| SELinux | **Permissive** |
| dm-verity | **enforcing + bootloader locked + AVB green** → `/system`、`/vendor` 完全不可写 |
| 被裁组件 | `com.android.vpndialogs`（确认：`pm list packages`、`/system/priv-app`、`/system/app` 均无） |
| USB | 主机 xHCI；hub 下挂 4G 模块(Fibocom)、arkmicro NCM gadget(usb0)、iPhone |
| 桌面 | `com.sgmw.lingos.launcher`（priv-app），能识别并列出第三方应用（`thirdApp=true`） |
| SystemUI | 定制 `SysUI#UsbPopup`，**会立即隐藏 USB 权限对话框**（`hidePopupImmediately`） |

## 2. 崩溃 1：`com.android.vpndialogs` 被裁 → 启动即崩（"点图标没反应"）

**现象**：桌面图标正常显示，点击后闪退回桌面。logcat：

```
java.lang.RuntimeException: Unable to start activity .../CarPlayHostActivity:
  android.content.ActivityNotFoundException: Unable to find explicit activity class
  {com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog}
  at CarPlayHostActivity.requestVpnConsent(CarPlayHostActivity.kt:506)
  at CarPlayHostActivity.requestStartupPrerequisites(CarPlayHostActivity.kt:480)
  at CarPlayHostActivity.onCreate(CarPlayHostActivity.kt:423)
```

**根因**：`requestVpnConsent()` 拿到 `VpnService.prepare()` 返回的授权 Intent 后，**无条件** `vpnConsent.launch(intent)`。车机裁掉了 vpndialogs，显式 Intent 解析失败 → 未捕获异常。

**通用版代码修复（common 模块，待实施）**：

```kotlin
private fun requestVpnConsent() {
    val intent = CarPlayVpnService.prepare(this)
    if (intent == null || intent.resolveActivity(packageManager) == null) {
        // 无需授权，或系统根本没有确认对话框（裁剪版车机）：
        // 直接视为已授权，交给 establish() 自己去验证
        vpnReady = true
        maybeStartCarPlay()
    } else {
        awaitingVpnConsent = true
        vpnConsent.launch(intent)
    }
}
```

> `intent.resolveActivity()` 为 null 说明系统没有 ConfirmDialog，此时**不要崩**——如果系统真的要求授权，`establish()` 会返回 null 并走 `AttachResult.Failed`，有优雅降级路径。
> 另：部分 userdebug 车机可用 root 兜底 `appops set <pkg> ACTIVATE_VPN allow`（持久化于 `/data/system/appops.xml`），可做成应用内 RootCompat 自动执行。

**无 root 临时修复（本次使用）**：

```bash
adb shell appops set com.shihab.diplay ACTIVATE_VPN allow
```

## 3. 崩溃 2：MANUAL 热点配置死锁（Android 9 特有）

**现象**：修完崩溃 1 后，进入 CarPlayHostActivity 又崩：

```
java.lang.IllegalArgumentException: manualHotspotSsid is required in manual hotspot mode
  at CarPlayRuntimeConfig.<init>(CarPlayRuntimeConfig.kt:98)
```

**根因**（两层）：
1. `CarPlayRuntimeConfig` 构造时**无条件校验**：`wirelessHotspotMode == MANUAL && manualHotspotSsid == null` → 抛异常。车机 prefs 里 `wireless_hotspot_mode=MANUAL` 但从没写过 SSID。
2. **Android 9 上无法绕开 MANUAL**：`AirPlayPersistence.loadWirelessHotspotMode()` 会把 `LOCAL_ONLY_HOTSPOT` 强制降级为 MANUAL（`WIFI_P2P` 在 SDK<29 时也强制降级），且改完立即写回。改 prefs 无效。

**通用版代码修复（common 模块，待实施）**：加载配置后、构造 RuntimeConfig 前，自动补默认值：

```kotlin
if (wirelessHotspotMode == WirelessHotspotMode.MANUAL && manualHotspotSsid.isNullOrBlank()) {
    manualHotspotSsid = "DiPlay-AP"           // 或 50play-AP
    if (manualHotspotSecurity == ManualHotspotSecurity.WPA2 && manualHotspotPassphrase.isNullOrBlank()) {
        manualHotspotPassphrase = generateDefaultPassphrase() // 8-63 字符
    }
}
```

**本次实机修复**（直接写 prefs，字段类型必须准确）：

```xml
<string name="manual_hotspot_ssid">DiPlay-Car</string>
<string name="manual_hotspot_security">WPA2</string>
<string name="manual_hotspot_band">AUTO</string>
<int name="manual_hotspot_channel" value="0" />          <!-- Int 类型！0=自动，跳过 band 兼容校验 -->
<string name="manual_hotspot_passphrase">8~63位密码</string>
```

注意：改 prefs 必须先 `am force-stop`，push 后恢复属主与 660 权限。

## 4. 障碍 3：USB 权限弹窗被 SystemUI 秒隐藏 → "iPhone 不弹授权"

**现象**：iPhone 插 USB（物理层完全正常：480Mbps HS 枚举、描述符完整、500mA 供电），车机端 `wired iPhone USB permission requested` 后卡死，iPhone 不弹"信任"。

**根因链**：
1. 应用 `hasPermission()==false` → `UsbManager.requestPermission()` → 系统弹 USB 权限对话框
2. 车机定制 SystemUI 的 `UsbPopup` 组件 **立即隐藏** 该对话框 → 用户永远看不到
3. 授权永远批不下来 → 应用拿不到 `UsbDeviceConnection` → 无法发 iAP2 → iPhone 永远不进 CarPlay 模式

**Android 9 USB 授权机制（关键，很多人不知道）**：
- 运行时授权存放在 `UsbUserSettingsManager.mDevicePermissionMap`：**纯内存** HashMap
- key = `device.getDeviceName()` = **`/dev/bus/usb/001/NNN`，NNN 是 devnum**
- value = uid → boolean
- **不持久化**；设备重新枚举 devnum 递增（006→007→008…），**授权随之失效**
- `IUsbManager.grantDevicePermission(UsbDevice, int uid)`（transaction code 11）可直接写入该 map，**binder calling uid=0（root）时跳过 MANAGE_USB 权限检查**

**root 自救方案（已验证可用）**：`GrantUsb` 工具（源码见 `asset/GrantUsb.java`，dex 已部署车机 `/data/local/tmp/grantusb.dex`）：

```bash
# 一次性授权当前枚举出的 iPhone
adb shell "CLASSPATH=/data/local/tmp/grantusb.dex app_process /system/bin GrantUsb"

# 常驻守护：每 2 秒自动授权（脱离会话存活，车机重启后失效需重跑）
adb shell "CLASSPATH=/data/local/tmp/grantusb.dex nohup app_process /system/bin GrantUsb watch >/data/local/tmp/watch.log 2>&1 &"
```

实现要点：
- `app_process` + `ActivityThread.systemMain()` 拿 systemContext → `UsbManager.getDeviceList()` 拿真实 UsbDevice → 反射调 `grantPermission(device, uid)`（双参版本优先，**uid 必须传目标应用 uid**，单参版本会授给 root 自己）
- **陷阱**：授权不会触发应用 `requestPermission` 的 PendingIntent 回调，卡住的应用需要"回桌面→再点连接"或 `am start` 重新走 `onNewIntent → requestStartupPrerequisites` 才会苏醒

**通用版根治方案（已实测）**：改 `res/xml/usb_device_filter.xml` 只按 vendor 匹配（**不要写死 PID**）：

```xml
<usb-device vendor-id="1452" />   <!-- 0x05AC = Apple 全部设备通配 -->
```

原理：manifest 声明 `USB_DEVICE_ATTACHED` + filter 后，**系统在设备枚举时自动授予匹配应用权限**（标准 Android 机制），不再依赖被隐藏的对话框。本次已实测打包进重签版。

**为什么不能写死 PID**：实测某 iPhone 是 `05ac:12a8`，但这只是 Apple 移动设备若干 PID 之一——iPhone 15 USB-C、iPad、不同 iOS 枚举模式（PTP/NCM 重新枚举）的 PID 都可能不同（0x129x 系列）。只匹配 vendor-id 即可通配全部 Apple 设备；副作用（iPad 插车机也被授权/自动拉起）无害。
副作用：iPhone 插入时系统可能弹"用 50play 打开"选择框（选默认即可），并自动拉起 `CarPlayHostActivity`（它本来就带 USB_DEVICE_ATTACHED intent-filter）。

## 4b. 运行时权限在定制 ROM 上（"设置里全点不动"）

实测 LingOS 的设置 → 应用 → 权限管理页**是摆设**：DiPlay 的麦克风/定位、沙发管家的电话权限等开关**点了无反应**（ROM 缺陷，不是个案）。运行时权限弹窗是否也被 SystemUI 拦截未逐一验证，但按 USB 弹窗的前科不能指望。

**可靠通道（不需要 root）**：`pm grant` 对 shell 开放，adb 即可：

```bash
adb shell pm grant com.shihab.diplay android.permission.RECORD_AUDIO
adb shell pm grant com.shihab.diplay android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.shihab.diplay android.permission.ACCESS_COARSE_LOCATION
```

**给别人装应用时的正解**：`adb install -g xxx.apk` 安装时自动授予全部运行时权限（实测生效）。若用应用商店/沙发管家侧载则没有这一步——所以通用版必须做到：
1. 应用启动时**主动请求**全部所需运行时权限（包括有线模式请求麦克风——当前代码只在无线路径请求，有线模式漏了）
2. 设置页内置"权限诊断"，逐项显示 granted 状态并给出对应 `pm grant` 命令
3. `RootCompat`（root 车机）直接 `ProcessBuilder("pm","grant",...)` 静默授权

## 5. 重打包注意事项（extractNativeLibs 陷阱）

- 原 APK manifest 声明 `android:extractNativeLibs="false"`（so 不压缩+页对齐）
- **apktool 重建会破坏该条件** → `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries`
- 修复：反编译目录 `AndroidManifest.xml` 改为 `android:extractNativeLibs="true"` 再 `apktool b`
- 流程：`apktool d` → 改资源 → `apktool b` → `zipalign -f 4` → `apksigner sign`（v1+v2，minSdk28 必须 v2）
- 签名密钥：`asset/diplay.keystore`（storepass/keypass 均为 diplay123，**升级必须用同一把**）

## 6. 性能（低配 SoC 卡顿）

- 第三方 APK 装在 /data 默认解释执行 + JIT：**`adb shell cmd package compile -m speed -f com.shihab.diplay`** 全量 AOT 编译，启动与运行明显改善（实测 Success）
- 应用卸载重装后 AOT 失效，需重跑
- 帧率进一步优化方向：DiPlay 设置中调低分辨率/帧率、开启 HEVC（若车机硬解支持）、检查 MediaCodec 是否硬解（logcat 抓 `MediaCodec` + `c2.*`/`OMX.*`）

## 7. 本次验证过的完整部署清单

1. `adb connect 172.20.10.6:5557`（车机 ADB 页面开启 WiFi ADB 后）
2. `adb root`
3. 安装重签版 APK（**带 `-g`：`adb install -g`，一步授予全部运行时权限**；WiFi streamed install 传大 APK 易超时失败，改用 `adb push` + `pm install -r -g` 更稳）
4. `appops set com.shihab.diplay ACTIVATE_VPN allow` + `pm grant` 麦克风/定位
5. 恢复/初始化 prefs（manual hotspot 五字段）
6. `cmd package compile -m speed -f com.shihab.diplay`
7. GrantUsb 一次性授权 + 启动 watch 守护
8. 启动应用 → Connect with USB → 车机出画面

一键脚本：Mac 端 `Downloads/diplay_fix.sh`（含上述 1/4/7 步与守护管理）。

## 8. 通用版（50play）待办清单

- [x] `requestVpnConsent()` 加 `resolveActivity` 防御（见 §2）——已在本地分支实施
- [x] MANUAL hotspot 自动补默认值（见 §3）——已在本地分支实施
- [x] `usb_device_filter.xml` 改为 **vendor-only**（`<usb-device vendor-id="1452" />`，通配全部 Apple 设备，已实测）
- [x] 品牌改造：应用名 50play、launcher/通知/诊断文件名/AirPlay 广播身份（DEFAULT_MANUFACTURER/MODEL）已替换；launcher 图标已换宝骏钻石标
- [ ] 有线模式启动前置也请求**麦克风**+定位运行时权限（核注：`onCreate` 409-419 行已有 `microphonePermission.launch`，有线/无线均覆盖——真正缺的是被裁 ROM 上弹窗不可达，靠 `install -g` / `pm grant` / 权限诊断页兜底）
- [ ] 设置页"权限诊断"：逐项 granted 状态 + 对应 `pm grant` 命令展示
- [ ] `RootCompat`：检测 root（`su`/`/system/xbin`），可用时自动执行 appops、pm grant、GrantUsb 等价逻辑（ProcessBuilder 调 app_process）
- [ ] 无 root 车机：设置页内置"诊断/自救"页，展示一键 adb 命令 + 导出诊断日志
- [ ] 品牌：应用名 DiPlay → 50play，launcher/应用图标 → 宝骏钻石标（asset/baojun_logo.png），代码内字符串/资源全面替换
- [ ] 与上游 shihabal3amri/DiPlay 对齐（本地 fork 基于其 main，上游 release v0.2.0，实测 APK 0.2.6 更新）
