#!/usr/bin/env python3
"""Exercise failed first boot initialization and one clean delayed retry."""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
subprocess.run(["python3", str(root / "tools/v2/test_key_target.py")], check=True)
source_dir = root / "work/v2/key-target-test"
out = root / "work/v2/key-init-lifecycle-test"
if not out.resolve().is_relative_to((root / "work/v2").resolve()):
    raise RuntimeError("Lifecycle test output must stay under work/v2")
if out.exists():
    shutil.rmtree(out)
shutil.copytree(source_dir, out)

client = out / "com/focuspen/enhance/v2/ConfigClient.java"
code = client.read_text(encoding="utf-8")
code = code.replace("static ConfigClient last;", "static ConfigClient last; static int attempts,closed; static boolean failNext;")
code = code.replace("ConfigClient(android.content.Context c){last=this;}",
                    "ConfigClient(android.content.Context c){attempts++;last=this;}")
code = code.replace("void close(){}", "void close(){closed++;}")
code = code.replace("void add(Listener l){listener=l;l.changed(state);}",
                    "void add(Listener l){if(failNext){failNext=false;throw new IllegalStateException(\"temporary failure\");}listener=l;l.changed(state);}")
client.write_text(code, encoding="utf-8")

bus = out / "com/focuspen/enhance/v2/RuntimeBus.java"
code = bus.read_text(encoding="utf-8")
code = code.replace("static RuntimeBus last;", "static RuntimeBus last; static int closed;")
code = code.replace("void close(){}", "void close(){closed++;}")
bus.write_text(code, encoding="utf-8")

handler = out / "android/os/Handler.java"
code = handler.read_text(encoding="utf-8")
code = code.replace("public final java.util.ArrayDeque<Runnable> queue", "public static Runnable retry; public final java.util.ArrayDeque<Runnable> queue")
code = code.replace("public boolean postDelayed(Runnable r,long delay){timer=r;return true;}",
                    "public boolean postDelayed(Runnable r,long delay){retry=r;return true;}")
if "public static Runnable retry" not in code:
    raise AssertionError("Handler double changed; update lifecycle harness")
handler.write_text(code, encoding="utf-8")

test = out / "com/focuspen/enhance/v2/KeyInitLifecycleTest.java"
test.write_text("""package com.focuspen.enhance.v2;
import android.content.Context; import android.os.Handler; import java.lang.reflect.*;
public class KeyInitLifecycleTest {
 static Field field(String name)throws Exception {Field f=PenKeyHost.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  Context context=new Context(); ConfigClient.failNext=true;
  Method initialize=PenKeyHost.class.getDeclaredMethod("initialize",Context.class);initialize.setAccessible(true);
  initialize.invoke(null,context);
  check(field("instance").get(null)==null,"failed host was published");
  check(!field("initializationScheduled").getBoolean(null),"failed host stayed latched");
  check(ConfigClient.closed==1&&RuntimeBus.closed==1,"failed attempt leaked resources");
  check(Handler.retry!=null,"failure did not schedule retry");
  Runnable retry=Handler.retry;Handler.retry=null;retry.run();
  check(field("instance").get(null)!=null,"retry did not publish host");
  check(field("initializationScheduled").getBoolean(null),"successful host not latched");
  check(ConfigClient.attempts==2,"unexpected construction count");
  check(Handler.retry==null,"success scheduled another retry");
  System.out.println("PenKeyHost: failed first boot then one successful retry PASS");
 }
}""", encoding="utf-8")
files = [str(path) for path in out.rglob("*.java")]
for name in ("PenKeyHost", "PenInputClassifier", "ProInput", "PinchSequence", "ProBindings",
             "ProGestureGate", "KeyTarget", "KeyArbiter", "PenAction", "DirectionAdapter",
             "NativeShortcutGate", "HyperOsCompatibility"):
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.KeyInitLifecycleTest"], check=True)
