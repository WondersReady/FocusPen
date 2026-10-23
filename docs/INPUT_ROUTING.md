# 焦点笔输入归属图

适用 ROM：`Xiaomi/piano/piano:17/CP2A.260605.016/OS4.0.2.0.XPYCNXM`。这是当前源码与设备提取代码的路径图，不等于实笔全部通过。标准版可继续设备验证；Pro 暂无实笔。

## 入队顺序

```text
实体笔 / 固件
  -> Android KeyEvent，携带 isXiaomiStylus() 笔型
  -> BaseMiuiPhoneWindowManager.interceptKeyBeforeQueueingInternal
       -> MiuiStylusTouchFilmManager.interceptKeyBeforeQueueing
            Pro type 8，key 194-197；模块的 Pro Hook 在官方分发前判定
       -> 其他系统策略
       -> MiuiStylusShortcutManager.shouldInterceptKey
            标准版 type 3：速记 93、截图 92、焦点 193/194
            模块的标准版 Hook 在整个策略方法入口先判定
       -> 系统允许时交给前台应用
```

标准版由 `PenKeyHost` 和 `KeyArbiter` 仲裁短按、长按、双击；Pro 解锁后由 `ProInput` 仲裁。已接管的按下和对应抬起必须成对消费。“交给前台应用”绕过官方快捷策略，保留原笔的按键身份；显式选择“原生处理”时才重新经过系统策略。两个重放路径不能混用。

## 归属与依赖

| 输入 | 解锁后的判定者 | 仍依赖系统的部分 | 边界 |
| --- | --- | --- | --- |
| 标准版速记键、截图键 | `PenKeyHost`；鼠标左右键与演示换色优先 | 原生动作重放、官方功能入口 | 仅 type 3；游戏、相机、锁屏分别处理 |
| 标准版焦点键 | `PenPresentation` 状态机，经 `PenKeyHost` 从策略入口调用 | 空中坐标、蓝牙模式信号 | 锁屏目前仍由官方处理；坐标链需实笔复验 |
| Pro 轻捏、笔身双击、上下滑 | `ProInput`，在触控膜管理器入口运行 | 手势硬件开关、连接状态、需要时的官方轻捏面板 | 无 Pro 实笔；关闭硬件或游戏场景仍保留官方保护 |
| Pro 锁屏轻捏 | `ProGestureGate` 只处理固定圆环 | 系统锁屏、硬件状态 | 与解锁后的 `ProInput` 是不同场景，不是解锁态双重仲裁 |
| 悬浮、接触和空中运动 | `SystemPenMotion` 观察系统指针流；TouchService 提供预览事件 | 系统输入映射与蓝牙运动流 | 按键状态机本身不能生成空中坐标 |

模块自己的浮层已绘制激光、圈注、笔迹和鼠标光标，但标准版仍从 `LaserPointerController` 获取位置，并用 `sendModeToBluetooth` 开启空中数据；Pro 仍调用 `enableVirtualLaser`。因此“换掉官方绘制”不等于“坐标也脱离系统”。曾出现的光标固定左上角，需要同时记录控制器位置回调和模块浮层坐标；仅检查 GUI 无法判断故障在哪一侧。

## 小米创作特例

`MiuiStylusTouchFilmManager` 内置 `com.miui.creation` 包名，当前台是小米创作时允许 Pro 笔身双击和上下滑分发。这不是 APK 清单中的声明。轻捏采用另一条路径：小米创作相关页面在 `onResume()` 调用 `MiuiInputManager.enableStylusCaseForApp(packageName, true)`，在 `onPause()` 取消；系统据此选择向应用分发还是打开默认轻捏面板。收到按键后，小米创作自己解释 196/197 等事件。模块的“默认”动作要保留完整按下／抬起，不能把组合重新送进官方计时器。

## 下一轮设备核对

1. 记录安装 APK 哈希、LSPosed 加载版本、ROM 指纹。
2. 用标准版笔分别在解锁和锁屏状态采集焦点键按下／抬起、原生控制器位置回调、模块浮层位置更新。
3. 进入鼠标后用手指切换前台应用，再移动笔；会话应保留并改向新窗口。真实笔尖落屏应退出鼠标。
4. 核对两个侧键的短按、长按、双击以及原生／应用重放是否只执行一次。
5. Pro 轻捏、笔身双击和滑动保持“未实笔验证”。标准版严重回归修复并实笔通过前不发布新版。

依据：设备提取的 `BaseMiuiPhoneWindowManager.java`、`MiuiStylusShortcutManager.java`、`MiuiStylusTouchFilmManager.java`、`LaserPointerController.java` 与 `Creation.apk`；项目中的 `PenKeyHost`、`ProInput`、`PenPresentation`、`SystemPenMotion`、`NativeKeyReplay`。
