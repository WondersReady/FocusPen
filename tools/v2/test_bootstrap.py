#!/usr/bin/env python3
"""Compile the actual UnlockGate against minimal framework fakes, then exercise boot races.

These tests cover callback ordering and exception containment, not Android integration.
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/bootstrap-test"
stubs = {
    "android/content/Context.java": """
package android.content;
import android.os.Handler;
public abstract class Context {
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public abstract <T> T getSystemService(Class<T> type);
    public abstract Context createDeviceProtectedStorageContext();
    public abstract Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String p, Handler h, int flags);
    public abstract void unregisterReceiver(BroadcastReceiver r);
}
""",
    "android/content/BroadcastReceiver.java": """
package android.content;
public abstract class BroadcastReceiver { public abstract void onReceive(Context c, Intent i); }
""",
    "android/content/Intent.java": """
package android.content;
public class Intent {
    public static final String ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";
    private final String action;
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
}
""",
    "android/content/IntentFilter.java": """
package android.content;
public class IntentFilter { public IntentFilter(String action) {} }
""",
    "android/os/UserManager.java": """
package android.os;
public class UserManager { public boolean unlocked; public boolean isUserUnlocked() { return unlocked; } }
""",
    "android/os/Looper.java": """
package android.os;
public class Looper {
    public static final Looper MAIN = new Looper(); public static Looper current = MAIN;
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return current; }
}
""",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    private static final java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();
    public Handler(Looper l) {}
    public boolean post(Runnable r) { return queue.add(r); }
    public static void drain() { Looper.current = Looper.MAIN; while (!queue.isEmpty()) queue.remove().run(); }
}
""",
    "de/robv/android/xposed/XposedBridge.java": """
package de.robv.android.xposed;
public class XposedBridge {
    public static int failures;
    public static void log(String s) {}
    public static void log(Throwable t) { failures++; }
}
""",
}
sources = []
for name, code in stubs.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(code, encoding="utf-8")
    sources.append(str(path))
sources += [str(root / "module/app/src/main/java/com/focuspen/enhance/v2/UnlockGate.java"),
            str(root / "tools/v2/UnlockGateTest.java")]
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *sources], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.UnlockGateTest"], check=True)
