# DiPlay 中文版（五菱/宝骏适配 fork）构建说明

本 fork 基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) v0.2.0，主要变更：

## 与上游的差异

1. **界面全面简体中文**：约 370 条用户可见字符串抽取为资源（`common/src/main/res/values/` 为默认中文，`values-en/` 为英文兜底）。系统语言为英文时显示英文，其余（含 zh-CN）显示中文。品牌名 DiPlay、协议字段、iAP2/AirPlay 标识保持英文。
2. **修复：Car home 按钮闪退**——`DiPlayActivity` 返回车机桌面时 `startActivity(CATEGORY_HOME)` 无异常保护，在部分车机 ROM（无响应 HOME 解析的桌面）上抛 `ActivityNotFoundException` 直接闪退。已加 `runCatching` 并兜底 `moveTaskToBack`。
3. **修复：Android 14+ 前台服务崩溃**——`DiPlaySessionService` 的 `connectedDevice` 类型声明在蓝牙权限/USB 授权未满足时可抛异常，现降级为无类型声明。
4. **修复：损坏的偏好存储导致启动死循环崩溃**、**vpnLatch 一次性竞态导致无线模式永久连不上**、**iAP2 隧道畸形包导致静默假死**。
5. **性能**：
   - 无线会话持有低延迟 WifiLock（此前 Wi-Fi 省电造成的 radio gap 是音频断续的最可疑根因）；
   - 音频解码输入缓冲拿不到时先排空输出再重试，不再直接丢包（丢一包 AAC ≈ 23ms 可闻咔哒声）；
   - 调试日志关闭时跳过全部热路径 hex 转储（`TraceGate`）；
   - ChaCha20-Poly1305 每流复用、BufferInfo 复用、视频队列计数器化、事件/iAP 通道禁用 Nagle。
6. **五菱适配**：默认帧率降为 30fps（设置里可调回 60）。BYD 专属的 HUD/仪表导航在非 BYD 车机上自动失效且不影响主流程。

## 本地打包（含离线 MFi 身份）

**密钥永不入库**：上游 `.gitignore` 已覆盖 `*.pk8`、`*.p7b`、`*.jks` 等；本 fork 另把所有密钥放在仓库目录之外。

```
~/Desktop/50carplay-auth-assets/          # 仓库外，git 不可见
├── apk-assets/offline-mfi/
│   ├── identity.pk8                      # EC P-256 PKCS#8 私钥
│   └── certificate.p7b                   # Apple Accessories CA 签发的配件证书
├── diplay-release.jks                    # APK 签名密钥库
└── diplay-signing.env                    # 签名密码（chmod 600，勿上传）
```

一键打包（仓库根目录）：

```sh
./build-cn.sh          # release：注入 offline-mfi 身份 + 签名
./build-cn.sh debug    # debug：无身份（源码验证用）
```

`build-cn.sh` 不入库（`.git/info/exclude` 本地排除）。环境变量语义见上游 `docs/BUILD.md`：
`DIPLAY_AUTH_ASSETS_DIR` 指向**只包含** `offline-mfi/` 两个文件的目录（构建自带凭据防护，
多余凭据文件会被 `rejectBundledCredentials` 任务拒绝）；签名走 `ANDROID_KEYSTORE_*` 四个变量。

> 注意：Gradle daemon 启动时固定环境变量。改过签名配置后如遇"keystore password was incorrect"，先 `./gradlew --stop`。

## 安装到车机

输出的 `mobile/build/outputs/apk/release/mobile-release.apk` 直接安装到车机（需允许安装未知应用）。
不同签名的 APK 不能覆盖安装官方版，需先卸载。运行时应用会把 `offline-mfi` 身份复制到私有目录并做
私钥-证书配对自检，失败会在主界面提示。

## 推送自己的 fork

```sh
git remote add origin git@github.com:<你的用户名>/DiPlay.git
git push -u origin main
```

密钥文件在仓库外，不会被推送。请勿把 `diplay-signing.env` 或密钥拷回仓库目录后再提交。
