# 构建与验证

使用 JDK 21、Android SDK platform 37、build-tools 37.0.0 和仓库内 Gradle wrapper。在 `module/local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`。不要提交本机 SDK 路径或签名文件。

```sh
bash tools/v2/build.sh
```

Windows 也可在 `module/` 中执行 `gradlew.bat :app:assembleDebug`。输出为 `module/app/build/outputs/apk/debug/app-debug.apk`。旧预览包使用 debug 签名，覆盖升级必须使用相同签名；不同机器自行构建的包不保证可直接覆盖已安装包。1.0 Beta 更换应用 ID 后成为独立安装，签名与版本以最终发布产物为准。

本地检查按目的执行：

```sh
bash tools/v2/test_config.sh
python3 tools/v2/test_config_store.py
bash tools/v2/test_input.sh
python3 tools/v2/test_key_target.py
python3 tools/v2/test_native_replay.py
python3 tools/v2/test_official_actions.py
python3 tools/v2/test_ring_dispatch.py
python3 tools/v2/test_runtime_bus.py
python3 tools/v2/test_window_direction.py
python3 tools/v2/test_bootstrap.py
bash tools/v2/test_mouse.sh
python3 tools/v2/test_mouse_session.py
bash tools/v2/test_pro.sh
python3 tools/v2/test_presentation.py
bash tools/v2/test_motion.sh
python3 tools/v2/test_guide_media.py
python3 tools/v2/test_guide_content.py
python3 tools/v2/test_menu_drag.py
python3 tools/v2/test_settings_app_catalog.py
python3 tools/v2/test_update_checker.py
```

测试使用生产状态机或生产宿主代码配合 Android／ROM 替身。它们不证明实笔跟随、屏幕帧率、原生材质、设备声音路由或 Pro 硬件兼容性。输出写入已忽略的 `work/`。

管理器支持完整配置导入导出，当前 schema 为 15。升级前从旧包导出备份，在新包导入；导入不包含调试笔型预览、更新检查偏好或宿主状态。旧包和新包的私有配置互不共享。回退需要对应包名、签名与匹配版本的配置，不应通过清空数据来绕过签名不匹配。

停用时在 LSPosed 中禁用模块并重启。模块不修改官方 APK、系统分区或笔固件。

正式发布构建：在 `module/` 运行 `./gradlew :app:assembleRelease`，输出 `app/build/outputs/apk/release/app-release-unsigned.apk`，再使用自己的私有签名密钥签名。仓库不含发布私钥或密码。

1.0 Beta 的发布证书 SHA-256：`47c6cff47e4d9ca6b01ef66cce8c8355da0ea8c251bdfc96f4862f7656c81d5a`。测试签名至发布签名的 v3 轮换已在当前设备验证保留数据覆盖升级。自行构建的签名不能替代官方发布签名。
