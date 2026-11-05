# FocusPen 模块：开启代码裁剪与资源裁剪，但不混淆类名。
# 模块里有大量按字符串 Class.forName / loadClass 的反射调用，混淆会破坏 Xposed 入口与宿主类加载。
-dontobfuscate
-dontwarn **
# Xposed 入口由 assets/xposed_init 按字符串引用，R8 看不见，必须显式保留。
-keep class com.focuspen.enhance.v2.** { *; }
# Xposed 接口与回调同样按名字使用。
-keep class de.robv.android.xposed.** { *; }
