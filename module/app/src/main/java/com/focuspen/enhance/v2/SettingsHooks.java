package com.focuspen.enhance.v2;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

/** Native preferences for the verified SecurityCoreAdd 4.4.4.3 build. */
final class SettingsHooks {
    private static final String PREFIX = "focuspen.v2.", PAGE = PREFIX + "page";
    private static final String SUBPAGE = "com.miui.miinput.stylus.StylusPinchSettingsActivity";
    private static final Map<Object, Binding> bindings = new WeakHashMap<>();
    private static final Map<Object, String> childPages = new WeakHashMap<>();
    private static final ArrayList<XC_MethodHook.Unhook> registrations = new ArrayList<>();
    private static ClassLoader loader;
    private static ConfigClient config;
    private static boolean installed;
    private static PageRefresh refreshing;
    // Persisted field indices remain unchanged; presentation follows each key's gestures.
    private static final int[] PROFILE_FIELDS={7,0,1,8,2,3,4,5,6,9,13,14,15,16,17,18,19,20,21,22,23,24,10,11,12};

    static void install(Context context, ClassLoader cl, ConfigClient client) throws Exception {
        if (installed) return;
        if (context.getPackageManager().getPackageInfo("com.miui.securitycore", 0).getLongVersionCode() != 40004443L) {
            Log.w("FocusPenSettings", "Unsupported host version; native settings retained"); return;
        }
        loader = cl; config = client;
        Class<?> main = cl.loadClass("t2.A"), child = cl.loadClass("t2.Y");
        // Fail before installing if this build's primitive signatures do not match.
        Class<?> pref = cl.loadClass("androidx.preference.Preference");
        cl.loadClass("androidx.preference.PreferenceGroup").getDeclaredMethod("g", pref);
        cl.loadClass("androidx.preference.PreferenceGroup").getDeclaredMethod("k", pref);
        child.getDeclaredMethod("onCreate", Bundle.class);
        main.getDeclaredMethod("onCreate", Bundle.class);
        MethodHandle[] lifecycle = new MethodHandle[3];
        String[] names = {"onCreate", "onResume", "onDestroy"};
        for (int i = 0; i < names.length; i++) {
            Class<?>[] args = i == 0 ? new Class<?>[]{Bundle.class} : new Class<?>[0];
            lifecycle[i] = MethodHandles.privateLookupIn(child, MethodHandles.lookup())
                    .unreflectSpecial(superclassMethod(child, names[i], args), child);
        }
        try {
        hookMain(main, "onCreate", Bundle.class);
        hookMain(main, "onResume");
        register(main,"b",boolean.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                Binding b=bindings.get(p.thisObject);
                if(b!=null)b.changed(config.get());
            }
        });
        register(main, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { unbind(p.thisObject); }
        });
        hookChild(child, "onCreate", lifecycle[0], Bundle.class);
        hookChild(child, "onResume", lifecycle[1]);
        hookChild(child, "onDestroy", lifecycle[2]);
        register(superclassMethod(child,"onViewCreated",android.view.View.class,Bundle.class),new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!childPages.containsKey(p.thisObject))return;
                android.view.View list=(android.view.View)call(p.thisObject,"getListView");
                if(list==null)return;
                Runnable fit=()->{
                    boolean scroll=list.canScrollVertically(1)||list.canScrollVertically(-1);
                    list.setNestedScrollingEnabled(scroll);
                    list.setOverScrollMode(scroll?android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS:android.view.View.OVER_SCROLL_NEVER);
                    Object parent=list.getParent();
                    if(parent!=null&&parent.getClass().getName().equals("miuix.springback.view.SpringBackLayout"))
                        call(parent,"setSpringBackEnable",scroll);
                };
                list.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->list.post(fit));list.post(fit);
            }
        });
        hookNavigation(cl.loadClass(SUBPAGE));
        hookOfficialGuide(cl);
        hookWelcomeGuide(cl);
        hookGuideViews(cl);
        context.getContentResolver().registerContentObserver(ConfigProvider.UI_URI,false,
                new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        // Model changes are UI-only; rebuilding the native host also restores
                        // official removed preferences when returning to automatic detection.
                        java.util.Set<Activity> activities=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                        for(Object fragment:new ArrayList<>(bindings.keySet())) {
                            Activity a=activity(fragment);if(a!=null&&!a.isFinishing())activities.add(a);
                        }
                        for(Activity a:activities)a.recreate();
                    }
                });
        new RuntimeBus(context, (event, payload) -> { }, client::refresh);
        installed = true;
        } catch (Throwable e) {
            for (XC_MethodHook.Unhook hook : registrations) hook.unhook();
            registrations.clear();
            throw new IllegalStateException("Settings hooks rolled back", e);
        }
    }

    private static void hookGuideViews(ClassLoader cl) throws Exception {
        Class<?> holder = cl.loadClass("androidx.preference.G");
        register(cl.loadClass("androidx.preference.Preference"), "onBindViewHolder", holder, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String key = (String) call(p.thisObject, "getKey");
                if (key != null && key.startsWith(PREFIX + "guide.card."))
                    GuideNative.card(p.thisObject, p.args[0], Integer.parseInt(key.substring(key.lastIndexOf('.') + 1)));
                if ((PREFIX+"content.preview").equals(key)) {
                    android.view.ViewGroup root=(android.view.ViewGroup)XposedHelpers.getObjectField(p.args[0],"itemView");
                    if(root.getChildCount()==1&&root.getChildAt(0) instanceof RingEditorView editor&&editor.contentValue.equals(currentContent().encode()))return;
                    Context c=root.getContext();root.removeAllViews();root.setPadding(0,0,0,0);root.setMinimumHeight(0);
                    root.setContentDescription(null);root.setClickable(false);root.setFocusable(false);
                    root.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    root.addView(new RingEditorView(c,currentContent(),page->openPage(c,page),(id,position)->edit(c,ConfigSpec.CONTENT,currentContent().position(id,position).encode())),new android.view.ViewGroup.LayoutParams(-1,-2));
                }
            }
        });
        register(cl.loadClass("com.miui.widget.SpeedSeekBarPreference"), "onBindViewHolder", holder, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String key = (String) call(p.thisObject, "getKey");
                if (key != null && key.startsWith(PREFIX)) GuideNative.slider(p.thisObject, p.args[0]);
            }
        });
        register(cl.loadClass("miuix.preference.CheckBoxPreference"),"onBindViewHolder",holder,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                String key=(String)call(p.thisObject,"getKey");if(key==null||!key.startsWith(PREFIX+"pick."))return;
                android.view.View root=(android.view.View)XposedHelpers.getObjectField(p.args[0],"itemView");
                MenuPickerViews.bind(root,key.substring((PREFIX+"pick.").length()),currentContent(),(id,position)->edit(root.getContext(),ConfigSpec.CONTENT,currentContent().position(id,position).encode()));
            }
        });
    }

    private static void hookOfficialGuide(ClassLoader cl) throws Exception {
        // This verified host's fifth laser-guide page demonstrates the two direction keys.
        // Preserve its animation and all focus-key lessons, but explain profile-dependent routing.
        Class<?> guide = cl.loadClass("com.miui.miinput.stylus.VirtualLaserGuideActivity");
        if (guide.getDeclaredMethod("b", int.class).getReturnType() != void.class)
            throw new NoSuchMethodException("Unexpected official guide page signature");
        register(guide, "b", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || (Integer) p.args[0] != 4) return;
                ConfigSnapshot snapshot = config.get();
                if (!snapshot.enabled() || !snapshot.flag(ConfigSpec.KEYS)) return;
                try {
                    Activity activity = (Activity) p.thisObject;
                    int id = activity.getResources().getIdentifier("tip_tv", "id", "com.miui.securitycore");
                    android.widget.TextView tip = activity.findViewById(id);
                    if (tip != null) tip.setText("下图为应用上／下动作的翻页示例。请先在按键设置中绑定这两个动作；短按默认为不执行操作，实际响应取决于当前应用配置。");
                } catch (Throwable e) { Log.w("FocusPenSettings", "Official guide caption unavailable", e); }
            }
        });
    }

    private static void hookWelcomeGuide(ClassLoader cl) throws Exception {
        Class<?> listener = cl.loadClass("t2.o"), welcome = cl.loadClass("t2.q");
        if (listener.getDeclaredField("b").getType() != welcome
                || !android.os.Handler.class.isAssignableFrom(welcome.getDeclaredField("a").getType()))
            throw new NoSuchFieldException("Unexpected native welcome-guide signature");
        register(listener, "onClick", android.view.View.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                ConfigSnapshot snapshot = config.get();
                if (!snapshot.enabled() || !snapshot.flag(ConfigSpec.KEYS)) return;
                android.view.View clicked = (android.view.View) p.args[0];
                if (clicked == null) return;
                Context c = clicked.getContext();
                try {
                    android.content.res.Resources resources = c.getResources();
                    int learn = resources.getIdentifier("btn_learnToUse", "id", "com.miui.securitycore");
                    int standardVideo = resources.getIdentifier("video_view_notes", "id", "com.miui.securitycore");
                    // The Pro welcome uses another layout. Its walkthrough is outside this release.
                    if (learn == 0 || standardVideo == 0 || clicked.getId() != learn
                            || clicked.getRootView().findViewById(standardVideo) == null) return;
                    Object owner = XposedHelpers.getObjectField(p.thisObject, "b");
                    android.os.Handler handler = (android.os.Handler) XposedHelpers.getObjectField(owner, "a");
                    c.startActivity(new Intent().setClassName("com.miui.securitycore", SUBPAGE)
                            .putExtra(PAGE, "guide").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    // Use the host's own dismissal: it also unregisters the receiver and resets its singleton.
                    handler.sendEmptyMessage(2);
                    p.setResult(null);
                } catch (Throwable e) {
                    Log.w("FocusPenSettings", "Welcome guide redirect unavailable", e);
                }
            }
        });
    }

    private static void hookMain(Class<?> type, String method, Object... parameters) {
        Object[] args = new Object[parameters.length + 1]; System.arraycopy(parameters, 0, args, 0, parameters.length);
        args[parameters.length] = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try { injectMain(p.thisObject); config.refresh(); }
                catch (Throwable e) { Log.e("FocusPenSettings", "Main settings insertion failed", e); }
            }
        };
        register(type, method, args);
    }

    private static void hookChild(Class<?> type, String name, MethodHandle superCall, Class<?>... parameterTypes) throws Exception {
        Object[] args = new Object[parameterTypes.length + 1]; System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
        args[parameterTypes.length] = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = activity(p.thisObject);
                String page = "onCreate".equals(name)
                        ? activity == null ? null : activity.getIntent().getStringExtra(PAGE)
                        : childPages.get(p.thisObject);
                if (!validPage(page)) return;
                childPages.put(p.thisObject, page);
                try {
                    // Only our tagged Activity instance uses the base lifecycle and module screen.
                    // The original pinch fragment's listeners and system-setting writes are absent.
                    superCall.bindTo(p.thisObject).invokeWithArguments(p.args);
                    if ("onCreate".equals(name)) createPage(p.thisObject, page);
                    else if ("onResume".equals(name)) { setTitle(p.thisObject, title(page)); config.refresh(); }
                    else { unbind(p.thisObject); childPages.remove(p.thisObject); }
                } catch (Throwable e) {
                    Log.e("FocusPenSettings", "Module subpage failed: " + page, e);
                    if (activity != null && !"onDestroy".equals(name)) {
                        Toast.makeText(activity, "此设置页暂不可用", Toast.LENGTH_SHORT).show();
                        activity.getIntent().removeExtra(PAGE); activity.finish();
                    }
                }
                p.setResult(null);
            }
        };
        register(type, name, args);
    }

    private static void hookNavigation(Class<?> activityType) throws Exception {
        // The official Activity is singleTop: nested pages arrive through onNewIntent.
        register(superclassMethod(activityType, "onNewIntent", Intent.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!activityType.isInstance(p.thisObject)) return;
                Activity a = (Activity) p.thisObject;
                Intent next = (Intent) p.args[0];
                boolean wasModule = validPage(a.getIntent().getStringExtra(PAGE));
                boolean nowModule = validPage(next.getStringExtra(PAGE));
                if (!wasModule && !nowModule) return;
                try {
                    if (wasModule && nowModule) showPage(a, next.getStringExtra(PAGE));
                    else { a.setIntent(next); a.recreate(); }
                    p.setResult(null);
                }
                catch (Throwable e) { Log.e("FocusPenSettings", "Page navigation failed", e); }
            }
        });
        register(superclassMethod(activityType, "finish"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!activityType.isInstance(p.thisObject)) return;
                Activity a = (Activity) p.thisObject;
                String page = a.getIntent().getStringExtra(PAGE);
                String parent = parent(page);
                if (parent == null) return;
                try { showPage(a, parent); p.setResult(null); }
                catch (Throwable e) { Log.e("FocusPenSettings", "Page return failed", e); }
            }
        });
    }

    private static void showPage(Activity a, String page) throws Exception {
        if (!page.equals(a.getIntent().getStringExtra(PAGE))) {
            // MIUIX coordinates the list with the overlay toolbar during view creation.
            // Recreate the native page so a previous list's scroll/toolbar state cannot leak.
            a.setIntent(new Intent(a.getIntent()).putExtra(PAGE, page));
            a.recreate();
            return;
        }
        Object manager = call(a, "getSupportFragmentManager");
        Object f = call(manager, "v", "miui_stylus_pinch_fragment");
        if (f == null) throw new IllegalStateException("Missing settings fragment");
        unbind(f);
        childPages.put(f, page);
        a.setIntent(new Intent(a.getIntent()).putExtra(PAGE, page));
        createPage(f, page);
    }

    private static Method superclassMethod(Class<?> type, String name, Class<?>... args) throws Exception {
        for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private static void register(Class<?> type, String name, Object... args) {
        registrations.add(XposedHelpers.findAndHookMethod(type, name, args));
    }
    private static void register(Method method, XC_MethodHook callback) {
        registrations.add(XposedBridge.hookMethod(method, callback));
    }

    private static void injectMain(Object fragment) throws Exception {
        if (bindings.containsKey(fragment)) { bindings.get(fragment).changed(config.get()); return; }
        Object screen = call(fragment, "getPreferenceScreen");
        Object guide = call(fragment, "findPreference", "guide");
        Object effects = call(fragment, "findPreference", "stylus_effects_settings");
        if (screen == null || guide == null || effects == null || call(fragment, "findPreference", PREFIX + "category") != null) return;
        Context c = (Context) call(fragment, "requireContext");
        Binding binding = new Binding(c);
        binding.updates.add(s->applyMainPreview(fragment,c));
        Object group = preference(c, "miuix.preference.PreferenceCategory", "category", "功能设置", null);
        call(group, "onAttachedToHierarchy", call(fragment, "getPreferenceManager"));
        Object standardKeys=navigation(c, group, "keys", "全局按键设置", "标准版焦点笔");
        Object proKeys=navigation(c, group, "pro", "手势设置", "焦点笔 Pro");
        binding.updates.add(s->{call(standardKeys,"setVisible",!previewPro(c));call(proKeys,"setVisible",previewPro(c));});
        navigation(c, group, "rules", "应用配置文件", "为不同应用设置专属操作");
        Object ring = navigation(c, group, "ring", "弧形菜单", "添加常用功能与应用");
        navigation(c, group, "official", "息屏灵感速记", "息屏落笔，随时记录");
        toggle(binding, group, ConfigSpec.PREVIEW, "笔尖预览", "悬停时显示落笔位置");
        toggle(binding, group, ConfigSpec.AUDIO, "书写声音反馈", "书写时通过平板扬声器播放铅笔声");
        slider(binding, group, ConfigSpec.AUDIO_VOLUME, "书写声音音量", 0, 100, 1);
        navigation(c, group, "guide", "使用指南", "了解触控笔的功能与操作");
        for (String key : new String[]{"guide", "function", "stylus_quick_note_screen_off", "touch_film_stylus_quick_operations",
                "stylus_quick_write", "stylus_screen_shot", "stylus_function_settings", "stylus_effects_settings"}) {
            Object item = call(fragment, "findPreference", key);
            if (item != null) {
                boolean visible = (Boolean) call(item, "isVisible");
                binding.updates.add(s -> call(item, "setVisible", s.enabled() ? false : visible));
            }
        }
        binding.updates.add(s -> call(ring, "h", s.available ? "添加常用功能与应用" : "配置暂不可用"));
        int order = (Integer) call(guide, "getOrder");
        Map<Object, Integer> shifted = new LinkedHashMap<>();
        for (String key : new String[]{"guide", "function"}) {
            Object item = call(fragment, "findPreference", key);
            if (item != null) shifted.put(item, (Integer) call(item, "getOrder"));
        }
        try {
            for (Map.Entry<Object, Integer> e : shifted.entrySet()) call(e.getKey(), "setOrder", e.getValue() + 1);
            call(group, "setOrder", order); add(screen, group);
            bind(fragment, binding);
            Log.i("FocusPenSettings", "Inserted one native group before guide");
        } catch (Throwable e) {
            call(screen, "k", group);
            for (Map.Entry<Object, Integer> old : shifted.entrySet()) call(old.getKey(), "setOrder", old.getValue());
            throw e;
        }
    }

    private static void applyMainPreview(Object fragment,Context c) {
        String model=UiPreferences.model(c);if("auto".equals(model))return;
        boolean pro="pro".equals(model);
        Object standard=XposedHelpers.getObjectField(fragment,"t");
        Object creation=XposedHelpers.getObjectField(fragment,"v");
        Object charging=XposedHelpers.getObjectField(fragment,"u");
        Object diagram=XposedHelpers.getObjectField(fragment,"q");
        if(standard!=null){
            if(call(standard,"getParent")==null)add(diagram,standard);
            call(standard,"setVisible",!pro);
            call(standard,"setIcon",GuideNative.resource(c,"drawable","stylus_with_laser_diagram"));
        }
        if(creation!=null)call(creation,"setVisible",pro);
        if(charging!=null)call(charging,"setVisible",false);
    }

    private static void createPage(Object fragment, String page) throws Exception {
        Context c = (Context) call(fragment, "requireContext");
        Object screen;
        if(refreshing!=null)screen=call(fragment,"getPreferenceScreen");
        else {
            screen=loader.loadClass("androidx.preference.PreferenceScreen").getConstructor(Context.class,AttributeSet.class).newInstance(c,null);
            call(screen,"setPersistent",false);call(screen,"setTitle",title(page));
            call(screen,"onAttachedToHierarchy",call(fragment,"getPreferenceManager"));call(fragment,"setPreferenceScreen",screen);
        }
        setTitle(fragment, title(page));
        Binding b = new Binding(c);
        if (page.equals("content") || page.startsWith("item:") || page.startsWith("replace:") || page.startsWith("position:") || page.equals("selectContent") || page.equals("addContent") || page.equals("addProfile")
                || page.startsWith("profile:") || page.startsWith("field:") || page.startsWith("profileMode:")
                || page.startsWith("copyProfile:") || page.startsWith("copyTarget:")
                || page.equals("templates") || page.equals("addTemplate") || page.startsWith("template:")
                || page.startsWith("templateField:")) {
            managementPage(fragment, page, c, screen, b);
        } else if(java.util.Arrays.asList(ProBindings.KEYS).contains(page)) {
            int slot=java.util.Arrays.asList(ProBindings.KEYS).indexOf(page);java.util.List<String> values=ProBindings.options(slot);
            choices(b,screen,page,title(page),values.toArray(),values.stream().map(ProBindings::label).toArray(String[]::new),null);
            if(slot==4||slot==5){Object apps=category(c,screen,"pro.launch","打开应用");
                java.util.TreeMap<String,String> installed=new java.util.TreeMap<>();
                for(android.content.pm.ResolveInfo info:c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))installed.put(MenuEntry.label(c,info.activityInfo.packageName)+info.activityInfo.packageName,info.activityInfo.packageName);
                for(String pkg:installed.values())command(c,apps,"launch."+pkg,MenuEntry.label(c,pkg),null,()->edit(c,page,"app:"+pkg));
            }
        } else if (page.startsWith("button") || java.util.Arrays.asList(ConfigSpec.PRO_GESTURES).contains(page)) {
            ArrayList<PenAction> actionList = new ArrayList<>();
            for (PenAction action : PenAction.values()) if (action != PenAction.NATIVE) actionList.add(action);
            Object[] values = new Object[actionList.size()];
            String[] labels = new String[actionList.size()];
            for (int i = 0; i < actionList.size(); i++) { values[i] = actionList.get(i).id; labels[i] = actionList.get(i).label; }
            choices(b, screen, page, page.startsWith("pro")?"焦点笔 Pro":"标准版焦点笔", values, labels, null);
        } else {
            Object group = category(c, screen, page + ".settings", title(page));
            switch (page) {
                case "ring": {
                    toggle(b, group, ConfigSpec.RING, "启用弧形菜单", "弹出时取笔尖位置与朝向；点击图标执行，点击浮窗外取消");
                    information(c, group, "ring.material", "跟随系统材质", "使用应用侧边栏的系统材质设置，自动适配明暗与高级材质状态");
                    navigation(c, group, "content", "菜单内容", "点击圆环上的图标编辑，最多 12 项");
                    Object preview = preference(c, "miuix.preference.TextPreference", "ring.try", "试用弧形菜单", "点选项目可执行对应功能");
                    listener(preview, "setOnPreferenceClickListener", args -> {
                        Bundle reply = c.getContentResolver().call(ConfigProvider.URI, "previewRing", null, null);
                        if (reply == null || !reply.getBoolean("sent")) Toast.makeText(c, "快捷环尚未就绪，请确认 SystemUI 已加载模块", Toast.LENGTH_SHORT).show();
                        return true;
                    });
                    add(group, preview);
                    break;
                }
                case "preview":
                    toggle(b, group, ConfigSpec.PREVIEW, "蓝色笔尖预览", "悬空时显示，正常落笔时收起");
                    information(c, group, "preview.position", "固定圆环样式", "圆环内部透明，小范围向外泛光；离开感应范围后自动收起");
                    break;
                case "keys": {
                    toggle(b, group, ConfigSpec.KEYS, "启用按键增强", "标准版按键与 Pro 手势使用自定义设置");
                    slider(b, group, ConfigSpec.LONG_PRESS, "长按时长", 150, 800, 10);
                    slider(b, group, ConfigSpec.DOUBLE_PRESS, "快速双击间隔", 180, 450, 10);
                    for (int button = 1; button <= 3; button++) {
                        if (button == 3) {
                            choices(b, screen, ConfigSpec.LASER_ACTIVATION, "焦点键 · 激光笔模式启用方式",
                                    new Object[]{"hold","toggle"},new String[]{"按住开启","按住切换"},
                                    new String[]{"按住显示激光，松开隐藏","长按一次开启，再长按一次关闭"});
                            Object buttonGroup=category(c,screen,"focus.help","演示操作");
                            information(c,buttonGroup,"focus.color","演示中切换颜色","激光或圈注时，短按速记键切换上一色，短按截图键切换下一色。已有笔迹保持原色。");
                            continue;
                        }
                        Object buttonGroup = category(c, screen, "keys.button" + button, buttonName(button));
                        for (String suffix : new String[]{"Short", "Long", "Double"}) {
                            String key = "button" + button + suffix;
                            Object actionRow = navigation(c, buttonGroup, key, suffix.equals("Short")?"短按功能":suffix.equals("Long") ? "长按功能" : "快速双击功能", null);
                            b.updates.add(s -> call(actionRow, "h", PenAction.parse(s.text(key)).label));
                        }
                    }
                    information(c, group, "keys.timing", "短按响应", "为区分长按和快速双击，部分短按会稍后响应。识别为长按或双击后，不再触发短按。");
                    break;
                }
                case "pro":
                    Object single=navigation(c,group,"proPinch","轻捏一次",null);b.updates.add(s->call(single,"h",PenAction.parse(s.text("proPinch")).label));
                    slider(b,group,ProBindings.INTERVAL,"连续轻捏间隔",220,600,10);
                    slider(b,group,ProBindings.HOLD,"轻捏保持时长",300,1500,10);
                    Object proGroup=group;
                    for(int i=0;i<ProBindings.KEYS.length;i++) {
                        if(i==0||i==4||i==6)proGroup=category(c,screen,"pro.group."+i,i==0?"普通模式":i==4?"全局组合":"鼠标模式");
                        String key=ProBindings.KEYS[i];Object row=navigation(c,proGroup,key,ProBindings.TITLES[i],null);
                        b.updates.add(s->call(row,"h",ProBindings.label(s.text(key))));
                    }
                    navigation(c,group,"rules","应用配置文件","继承全局默认，或使用应用专属配置与模板");
                    information(c,group,"pro.double","双击笔身","双击笔身退出激光或鼠标模式，与连续轻捏两次分别设置。");
                    information(c,group,"pro.laser","演示中切换颜色","激光或圈注时，上滑切换上一色，下滑切换下一色。已有笔迹保持原色，退出演示后恢复滑动配置。");
                    Object hardware=category(c,screen,"pro.hardware","系统手势设置");
                    command(c,hardware,"pro.pinch.hardware","轻捏开关与力度","保留系统的轻捏开关和压力调节；开启后执行上方所选动作",()->openOfficial(c,SUBPAGE));
                    command(c,hardware,"pro.double.hardware","双击笔身开关","保留系统双击笔身退出演示的功能",()->openOfficial(c,"com.miui.miinput.stylus.StylusDoubleClickSettingsActivity"));
                    Object slide=preference(c,"miuix.preference.CheckBoxPreference","pro.slide.hardware","启用笔身滑动","系统滑动开关控制 Pro 是否上报滑动手势");
                    call(slide,"setChecked",android.provider.Settings.System.getInt(c.getContentResolver(),"stylus_slide_option",1)!=0);
                    listener(slide,"setOnPreferenceChangeListener",args->android.provider.Settings.System.putInt(c.getContentResolver(),"stylus_slide_option",Boolean.TRUE.equals(args[1])?1:0));add(hardware,slide);
                    information(c,hardware,"pro.testing","Pro 兼容预览","已接入当前系统的 Pro 手势链路，仍需焦点笔 Pro 实笔验证。笔尖预览、弧形菜单和书写声音共用现有设置。");
                    break;
                case "rules":
                    toggle(b, group, ConfigSpec.RULES, "启用应用配置文件", "随当前操作的窗口切换，未设置的项目沿用全局设置");
                    Object appProfiles=category(c,screen,"rules.apps","应用配置");
                    navigation(c, appProfiles, "addProfile", "添加应用配置", null);
                    ArrayList<AppProfiles.Profile> orderedProfiles=new ArrayList<>(AppProfiles.parse(config.get().text(ConfigSpec.PROFILES)).profiles);
                    java.text.Collator profileNames=java.text.Collator.getInstance(java.util.Locale.CHINA);
                    orderedProfiles.sort((a,z)->{int order=profileNames.compare(MenuEntry.label(c,a.pkg()),MenuEntry.label(c,z.pkg()));return order==0?a.pkg().compareTo(z.pkg()):order;});
                    for (AppProfiles.Profile p : orderedProfiles)
                        navigation(c, appProfiles, "profile:"+p.pkg(), MenuEntry.label(c,p.pkg()), p.enabled()?"已启用":"已停用");
                    Object reuse=category(c,screen,"rules.templates","复用设置");
                    navigation(c, reuse, "templates", "配置模板", "将同一套操作用于多个应用");
                    rebuildOnChange(fragment,page,b);
                    break;
                case "official":
                    Object off = preference(c, "miuix.preference.CheckBoxPreference", ConfigSpec.SCREEN_OFF, "息屏灵感速记", "息屏时用笔点击屏幕，直接开始记录");
                    listener(off, "setOnPreferenceChangeListener", args -> { edit(c,ConfigSpec.SCREEN_OFF,args[1]); return false; });
                    b.updates.add(s -> { call(off,"setEnabled",s.available); call(off,"setChecked",s.flag(ConfigSpec.SCREEN_OFF)); });
                    add(group,off);
                    choices(b, screen, ConfigSpec.SCREEN_OFF_TARGET, "打开方式", new Object[]{"note", "creation"},
                            new String[]{"笔记", "小米创作"}, new String[]{"快速记录文字与手写笔记", "打开画布，记录创作灵感"});
                    break;
                case "guide":
                    call(group, "setTitle", "了解你的焦点笔");
                    for (int topic : GuideContent.ORDER) {
                        final int lesson = topic;
                        Object card = preference(c, "androidx.preference.Preference", "guide.card." + topic,
                                GuideDemo.TITLES[topic], GuideDemo.SUMMARIES[topic]);
                        call(card, "setLayoutResource", GuideNative.resource(c,"layout","stylus_function_guide_preference_layout"));
                        listener(card, "setOnPreferenceClickListener", args -> {
                            if (b.demo != null) b.demo.dismiss();
                            b.demo = GuideDemo.show(c, config, lesson, previewPro(c)); return true;
                        });
                        add(group, card);
                    }
                    Object help = category(c, screen, "guide.help", "使用说明");
                    information(c, help, "guide.disable", "停用与恢复", "在 LSPosed 中禁用本模块并重启平板，可恢复系统原有行为。");
                    break;
                case "debug":
                    if(!BuildConfig.DEBUG)break;
                    information(c,group,"debug.scope","只切换设置与教程展示","不伪造设备连接，不模拟真实手势，也不修改系统的手写笔型号。返回主页面即可查看切换结果，无需重启。");
                    String selected=c.getSharedPreferences("focuspen_ui_preview",Context.MODE_PRIVATE).getString("model","auto");
                    String[] modes={"auto","standard","pro"},labels={"跟随已连接的笔","标准版焦点笔","焦点笔 Pro"};
                    for(int i=0;i<modes.length;i++) {
                        final String mode=modes[i];
                        command(c,group,"debug."+mode,labels[i],selected.equals(mode)?"当前选择":null,()->{
                            c.getSharedPreferences("focuspen_ui_preview",Context.MODE_PRIVATE).edit().putString("model",mode).apply();
                            try{unbind(fragment);createPage(fragment,"debug");}catch(Exception e){Log.w("FocusPenSettings","Preview refresh failed",e);}
                        });
                    }
                    navigation(c,group,previewPro(c)?"pro":"keys","查看所选机型设置",null);
                    navigation(c,group,"guide","查看所选机型使用指南",null);
                    break;
            }
        }
        bind(fragment, b);
    }

    private static void managementPage(Object fragment, String page, Context c, Object screen, Binding b) throws Exception {
        ConfigSnapshot s = config.get();
        RingContent content = RingContent.parse(s.text(ConfigSpec.CONTENT));
        AppProfiles profiles = AppProfiles.parse(s.text(ConfigSpec.PROFILES));
        Object group = category(c,screen,"management",title(page));
        if (page.equals("content")) {
            call(group,"setTitle","排列菜单项目");
            Object preview=preference(c,"androidx.preference.Preference","content.preview",null,null);
            call(preview,"setLayoutResource",android.R.layout.simple_list_item_2);call(preview,"setSelectable",false);add(group,preview);if(refreshing!=null)call(preview,"notifyChanged");
            information(c,group,"content.hint",content.items.size()==12?"已添加 12 项":"点击 ＋ 添加项目",
                    "拖动图标调整顺序，轻点更换内容或移除。\n此处从顶部按顺时针排列，实际菜单按项目数量展开。");
        } else if(page.equals("addContent")||page.equals("selectContent")) {
            menuPicker(fragment,c,screen,group,b,content);
        } else if(page.startsWith("position:")) {
            String id=page.substring(9);RingContent.Item item=content.find(id);
            if(item!=null)for(int i=0;i<content.items.size();i++){
                final int position=i;String name=MenuEntry.label(c,content.items.get(i).id());
                command(c,group,"position."+i,"第 "+(i+1)+" 项",content.items.get(i).id().equals(id)?"当前位置":name,()->{
                    b.nextPage="content";edit(c,ConfigSpec.CONTENT,currentContent().position(id,position).encode());
                });
            }
        } else if (page.startsWith("item:")) {
            String id=page.substring(5); RingContent.Item item=content.find(id);
            if (item==null) { navigation(c,group,"content","返回菜单内容",null); }
            else {
                navigation(c,group,"replace:"+id,"项目内容",MenuEntry.label(c,id));
                navigation(c,group,"position:"+id,"排列位置","第 "+(content.items.indexOf(item)+1)+" 项");
                if(!item.enabled())command(c,group,"restore","恢复到菜单","此项目在旧版中已停用",()->{b.nextPage="content";edit(c,ConfigSpec.CONTENT,currentContent().enable(id,true).encode());});
                command(c,group,"remove","移除项目",null,()->{b.nextPage="content";edit(c,ConfigSpec.CONTENT,currentContent().remove(id).encode());});
            }
        } else if (page.startsWith("replace:") || page.equals("addProfile")) {
            boolean menu=!page.equals("addProfile");String replacing=page.startsWith("replace:")?page.substring(8):null;
            Consumer<String> select=id->{b.nextPage="content";RingContent current=currentContent();edit(c,ConfigSpec.CONTENT,(replacing==null?current.add(id):current.replace(replacing,id)).encode());};
            if (menu && replacing==null && content.items.size()>=RingContent.MAX_ITEMS) information(c,group,"full","菜单已满","最多 12 项，请先删除一个项目。");
            else {
                Object officialGroup = menu ? category(c, screen, "official.actions", "官方动作") : group;
                Object appGroup = menu ? category(c, screen, "application.actions", "应用") : group;
                if (menu) for (String id : new String[]{RingContent.NOTES,RingContent.CREATION,RingContent.SCREENSHOT,RingContent.RECOGNITION}) {
                    if (content.find(id)==null) iconCommand(c,officialGroup,"add."+id,id,MenuEntry.available(c,id)?null:"目标不可用，安装后生效",()->select.accept(id));
                }
                java.text.Collator appOrder=java.text.Collator.getInstance(java.util.Locale.CHINA);
                java.util.TreeMap<String,String> apps=new java.util.TreeMap<>((a,z)->{int r=appOrder.compare(a,z);return r==0?a.compareTo(z):r;});
                for (android.content.pm.ResolveInfo info : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
                    String pkg=info.activityInfo.packageName;
                    if ((menu && content.find(pkg)!=null) || (!menu && profiles.find(pkg)!=null)) continue;
                    apps.put(MenuEntry.label(c,pkg)+"\u0000"+pkg,pkg);
                }
                for (String pkg:apps.values()) {
                    iconCommand(c,appGroup,"add."+pkg,pkg,pkg,()-> {
                    if (menu) select.accept(pkg);
                    else {
                        b.nextPage="profile:"+pkg;
                        edit(c,ConfigSpec.PROFILES,currentProfiles().set(pkg,-1,AppProfiles.INHERIT).encode());
                    }
                    });
                }
            }
        } else if (page.equals("templates") || page.equals("addTemplate") || page.startsWith("template:")) {
            if (page.equals("templates")) {
                information(c,group,"template.count","已创建 " + profiles.templates.size() + " 个模板","模板可套用到多个应用配置；应用仍可逐项覆盖。");
                navigation(c,group,"addTemplate","创建模板",null);
                java.util.List<AppProfiles.Template> orderedTemplates=orderedTemplates(profiles);
                for (AppProfiles.Template t : orderedTemplates) navigation(c,group,"template:"+t.id(),t.name(),"编辑模板内容");
            } else if (page.equals("addTemplate")) {
                information(c,group,"template.help","配置模板","输入名称后创建一个继承全局的模板，再进入模板页设置各项覆盖。");
                Object name=preference(c,"androidx.preference.EditTextPreference","template.name","模板名称","例如：阅读应用");
                listener(name,"setOnPreferenceChangeListener",args->{
                    String value=String.valueOf(args[1]).trim();
                    AppProfiles.validName(value);
                    AppProfiles current=currentProfiles();
                    String id=uniqueTemplateId(current,value);
                    b.nextPage="template:"+id;
                    edit(c,ConfigSpec.PROFILES,current.saveTemplate(id,value,AppProfiles.defaults()).encode());
                    return true;
                });
                add(group,name);
            } else {
                String id=page.substring(9); AppProfiles.Template t=profiles.template(id);
                if(t==null) { navigation(c,group,"templates","返回配置模板",null); }
                else {
                    Object name=preference(c,"androidx.preference.EditTextPreference","template.rename","模板名称",t.name());
                    try { call(name,"setText",t.name()); } catch(Throwable ignored) { }
                    listener(name,"setOnPreferenceChangeListener",args->{ edit(c,ConfigSpec.PROFILES,currentProfiles().renameTemplate(id,String.valueOf(args[1]).trim()).encode()); return true; }); add(group,name);
                    for(int field:PROFILE_FIELDS) if(fieldVisible(c,field))navigation(c,group,"templateField:"+id+":"+field,fieldTitle(field),fieldLabel(field,t.values().get(field)));
                    command(c,group,"template.delete","删除模板","使用此模板的应用恢复全局默认，已保存的自定义设置仍保留",()->{b.nextPage="templates";edit(c,ConfigSpec.PROFILES,currentProfiles().removeTemplate(id).encode());});
                }
            }
        } else if (page.startsWith("templateField:")) {
            String[] parts=page.split(":"); String id=parts[1]; int field=Integer.parseInt(parts[2]); AppProfiles.Template t=profiles.template(id);
            if(t!=null) {
                directionHelp(c,group,field);
                for(String value:AppProfiles.options(field)) command(c,group,"template.value."+value,fieldLabel(field,value),value.equals(t.values().get(field))?"当前选择":null,
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().setTemplate(id,field,value).encode()));
            }
        } else if (page.startsWith("profile:")) {
            String pkg=page.substring(8); AppProfiles.Profile profile=profiles.find(pkg);
            if (profile==null) navigation(c,group,"rules","返回应用配置文件",null);
            else {
                Object enabled=preference(c,"miuix.preference.CheckBoxPreference","profile.enabled","启用此配置",MenuEntry.label(c,pkg));
                call(enabled,"setChecked",profile.enabled());
                listener(enabled,"setOnPreferenceChangeListener",args->{edit(c,ConfigSpec.PROFILES,currentProfiles().set(pkg,-2,String.valueOf(args[1])).encode()); return false;}); add(group,enabled);
                String mode=profile.mode().equals("template")?"模板："+profiles.template(profile.template()).name():profile.mode().equals("default")?"全局默认":"自定义";
                navigation(c,group,"profileMode:"+pkg,"配置方式",mode);
                if(profile.mode().equals("template")) information(c,group,"profile.template.help","跟随模板","模板更新会自动应用。修改下方任意项会复制当前模板值并转为自定义。");
                java.util.List<String> effective=profiles.effective(profile);
                for(int field:PROFILE_FIELDS) if(fieldVisible(c,field))navigation(c,group,"field:"+pkg+":"+field,fieldTitle(field),fieldLabel(field,effective.get(field)));
                navigation(c,group,"copyProfile:"+pkg,"复制到其他应用","复制当前覆盖项，目标使用独立的自定义配置");
                command(c,group,"profile.defaults","恢复全局默认","全部字段跟随全局，保留已保存的自定义设置和启用状态",
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().select(pkg,"default","").encode()));
                command(c,group,"profile.delete","删除配置","删除后使用全局设置",()->{b.nextPage="rules";edit(c,ConfigSpec.PROFILES,currentProfiles().remove(pkg).encode());});
            }
        } else if(page.startsWith("copyProfile:")) {
            String source=page.substring(12);
            if(profiles.find(source)==null) navigation(c,group,"rules","返回应用配置文件",null);
            else {
                information(c,group,"copy.source","来源："+MenuEntry.label(c,source),"选择目标后可核对复制内容。继承项继续跟随全局；复制后不再跟随来源模板更新。");
                java.text.Collator appOrder=java.text.Collator.getInstance(java.util.Locale.CHINA);
                java.util.TreeMap<String,String> apps=new java.util.TreeMap<>((a,z)->{int r=appOrder.compare(a,z);return r==0?a.compareTo(z):r;});
                for(android.content.pm.ResolveInfo info:c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
                    String pkg=info.activityInfo.packageName;
                    if(!pkg.equals(source)) apps.put(MenuEntry.label(c,pkg)+"\u0000"+pkg,pkg);
                }
                for(String pkg:apps.values()) navigation(c,group,"copyTarget:"+source+":"+pkg,MenuEntry.label(c,pkg),
                        profiles.find(pkg)==null?"新建配置":"已有配置，确认后覆盖");
            }
        } else if(page.startsWith("copyTarget:")) {
            String[] parts=page.split(":"); String source=parts[1],target=parts[2];
            AppProfiles.Profile from=profiles.find(source),to=profiles.find(target);
            if(from==null) navigation(c,group,"rules","返回应用配置文件",null);
            else {
                information(c,group,"copy.source","来源："+MenuEntry.label(c,source),source);
                information(c,group,"copy.target","目标："+MenuEntry.label(c,target),target);
                information(c,group,"copy.help","复制为自定义配置","继承项继续跟随全局；不跟随来源模板更新。"
                        +(to==null?"新配置沿用来源的启用状态。":"覆盖目标保存的设置，保留目标的启用状态。"));
                java.util.List<String> values=profiles.effective(from);
                for(int field:PROFILE_FIELDS) if(fieldVisible(c,field))information(c,group,"copy.field."+field,fieldTitle(field),fieldLabel(field,values.get(field)));
                boolean full=to==null && profiles.profiles.size()>=100;
                if(full) information(c,group,"copy.full","应用配置已满","最多 100 个；请先删除一个配置，或选择已有配置。");
                else command(c,group,"copy.confirm",to==null?"确认创建配置":"确认覆盖配置",MenuEntry.label(c,target),()->{
                    if(config.get().revision!=s.revision) {
                        Toast.makeText(c,"配置已更新，请核对页面后重试",Toast.LENGTH_SHORT).show(); return;
                    }
                    b.nextPage="profile:"+target;
                    edit(c,ConfigSpec.PROFILES,currentProfiles().copy(source,target).encode());
                });
            }
        } else if(page.startsWith("profileMode:")) {
            String pkg=page.substring(12); AppProfiles.Profile profile=profiles.find(pkg);
            if(profile!=null) {
                command(c,group,"mode.default","全局默认",profile.mode().equals("default")?"当前选择":"全部字段跟随全局，保留已保存的自定义设置",
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().select(pkg,"default","").encode()));
                command(c,group,"mode.custom","自定义",profile.mode().equals("custom")?"当前选择":"使用此应用保存的设置",
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().select(pkg,"custom","").encode()));
                for(AppProfiles.Template t:orderedTemplates(profiles)) command(c,group,"mode.template."+t.id(),t.name(),
                        profile.mode().equals("template")&&profile.template().equals(t.id())?"当前选择":"跟随模板的设置",
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().select(pkg,"template",t.id()).encode()));
                navigation(c,group,"templates","管理配置模板",null);
            }
        } else if (page.startsWith("field:")) {
            String[] parts=page.split(":"); String pkg=parts[1]; int field=Integer.parseInt(parts[2]);
            AppProfiles.Profile profile=profiles.find(pkg);
            if(profile!=null) {
                directionHelp(c,group,field);
                for(String value:AppProfiles.options(field)) command(c,group,"value."+value,fieldLabel(field,value),value.equals(profiles.effective(profile).get(field))?"当前选择":null,
                        ()->edit(c,ConfigSpec.PROFILES,currentProfiles().set(pkg,field,value).encode()));
            }
        }
        if(!page.equals("addContent")&&!page.equals("selectContent"))rebuildOnChange(fragment,page,b);
    }
    private static RingContent currentContent() { return RingContent.parse(config.get().text(ConfigSpec.CONTENT)); }
    private static void menuPicker(Object fragment,Context c,Object screen,Object selected,Binding b,RingContent initial)throws Exception {
        call(selected,"setTitle","已选项目 · "+initial.items.size()+" / "+RingContent.MAX_ITEMS);
        Object official=category(c,screen,"picker.official","官方动作");
        Object apps=category(c,screen,"picker.apps","应用 · 按名称排序");
        java.text.Collator order=java.text.Collator.getInstance(java.util.Locale.CHINA);
        Map<String,String> labels=new java.util.HashMap<>();
        java.util.TreeSet<String> ids=new java.util.TreeSet<>((a,z)->{int r=order.compare(labels.computeIfAbsent(a,id->MenuEntry.label(c,id)),labels.computeIfAbsent(z,id->MenuEntry.label(c,id)));return r==0?a.compareTo(z):r;});
        ids.addAll(java.util.List.of(RingContent.NOTES,RingContent.CREATION,RingContent.SCREENSHOT,RingContent.RECOGNITION));
        for(RingContent.Item item:initial.items)ids.add(item.id());
        for(android.content.pm.ResolveInfo info:c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))ids.add(info.activityInfo.packageName);
        Map<String,Object> rows=new java.util.TreeMap<>(ids.comparator());
        for(String id:ids){boolean chosen=initial.find(id)!=null;
            Object row=menuChoice(c,chosen?selected:RingContent.isAction(id)?official:apps,id,chosen);
            call(row,"setOrder",chosen?initial.items.indexOf(initial.find(id)):rows.size());rows.put(id,row);
        }
        final String[] previous={initial.encode()};
        b.updates.add(snapshot->{
            String value=snapshot.text(ConfigSpec.CONTENT);if(value.equals(previous[0]))return;
            RingContent before=RingContent.parse(previous[0]);
            RingContent next=RingContent.parse(value);call(selected,"setTitle","已选项目 · "+next.items.size()+" / "+RingContent.MAX_ITEMS);
            // An imported backup may refer to an unavailable app that was not in
            // the initial launcher query. Keep it visible and removable in place.
            try{
                for(RingContent.Item item:next.items)if(!rows.containsKey(item.id()))
                    rows.put(item.id(),menuChoice(c,selected,item.id(),true));
            }catch(Exception failure){throw new IllegalStateException("Cannot bind imported menu item",failure);}
            int alphabetical=0;
            for(Map.Entry<String,Object> entry:rows.entrySet()){
                String id=entry.getKey();Object row=entry.getValue();RingContent.Item item=next.find(id);boolean chosen=item!=null;
                Object destination=chosen?selected:RingContent.isAction(id)?official:apps;
                Object parent=call(row,"getParent");
                if(parent!=destination){long stableId=((Number)call(row,"getId")).longValue();call(parent,"k",row);call(row,"setOrder",chosen?next.items.indexOf(item):alphabetical);add(destination,row);XposedHelpers.setLongField(row,"mId",stableId);}
                else call(row,"setOrder",chosen?next.items.indexOf(item):alphabetical);
                call(row,"setChecked",chosen);
                // Checked changes already invalidate the native row. Rebind selected rows
                // only when their drag position or accessibility move bounds have changed.
                RingContent.Item oldItem=before.find(id);
                if(chosen&&(oldItem==null||before.items.size()!=next.items.size()
                        ||before.items.indexOf(oldItem)!=next.items.indexOf(item)))call(row,"notifyChanged");
                alphabetical++;
            }
            previous[0]=value;
            refreshList(fragment);
        });
    }
    private static Object menuChoice(Context c,Object group,String id,boolean selected)throws Exception{
        Object choice=preference(c,"miuix.preference.CheckBoxPreference","pick."+id,MenuEntry.label(c,id),MenuEntry.available(c,id)?null:"目标暂不可用");
        call(choice,"setIcon",MenuIcons.icon(c,id));call(choice,"setChecked",selected);
        listener(choice,"setOnPreferenceChangeListener",args->{
            RingContent current=currentContent();boolean checked=(Boolean)args[1];
            if(checked&&current.items.size()>=RingContent.MAX_ITEMS){Toast.makeText(c,"最多添加 12 项，请先取消一项",Toast.LENGTH_SHORT).show();return false;}
            edit(c,ConfigSpec.CONTENT,(checked?current.add(id):current.remove(id)).encode());return false;
        });add(group,choice);return choice;
    }
    private static AppProfiles currentProfiles() { return AppProfiles.parse(config.get().text(ConfigSpec.PROFILES)); }
    private static java.util.List<AppProfiles.Template> orderedTemplates(AppProfiles profiles){
        ArrayList<AppProfiles.Template> ordered=new ArrayList<>(profiles.templates);
        java.text.Collator names=java.text.Collator.getInstance(java.util.Locale.CHINA);
        ordered.sort((a,z)->{int result=names.compare(a.name(),z.name());return result==0?a.id().compareTo(z.id()):result;});
        return ordered;
    }
    private static String fieldTitle(int f) { if(f>=13)return "Pro · "+(f>=19?"鼠标 · ":f>=17?"全局 · ":"普通 · ")+ProBindings.TITLES[f-13];return new String[]{"速记键 · 长按","速记键 · 快速双击","截图键 · 长按","截图键 · 快速双击","应用上动作","应用下动作","笔尖预览","速记键 · 短按","截图键 · 短按","Pro · 轻捏","Pro · 双击笔身","Pro · 向上滑动","Pro · 向下滑动"}[f]; }
    private static boolean fieldVisible(Context c,int f){return f!=10&&f!=11&&f!=12&&(f<9||previewPro(c));}
    private static String proTitle(int slot) { return new String[]{"轻捏功能","双击笔身功能","向上滑动","向下滑动"}[slot]; }
    static boolean previewPro(Context c) { return UiPreferences.pro(c); }
    private static String previewLabel(Context c) {
        String mode=c.getSharedPreferences("focuspen_ui_preview",Context.MODE_PRIVATE).getString("model","auto");
        return mode.equals("auto")?"跟随已连接的笔":mode.equals("pro")?"焦点笔 Pro · 仅界面预览":"标准版焦点笔 · 仅界面预览";
    }
    private static void openOfficial(Context c,String name) {
        try { c.startActivity(new Intent().setClassName("com.miui.securitycore",name)); }
        catch(Exception e){Toast.makeText(c,"此系统功能暂不可用",Toast.LENGTH_SHORT).show();Log.w("FocusPenSettings","Official page unavailable",e);}
    }
    private static void openOfficialLesson(Context c,int topic) {
        if(topic==4) {
            try { c.getPackageManager().getPackageInfo("com.miui.handwriting",0);
                c.startActivity(new Intent().setClassName("com.miui.handwriting","com.miui.handwriting.GuideActivity"));return;
            }catch(Exception ignored){}
        }
        openOfficial(c,"com.miui.miinput.stylus."+switch(topic){case 4->"MiuiHandWritingGuideActivity";case 5->"VirtualLaserGuideActivity";case 6->"QuickOperationsGuideActivity";default->"TouchFilmLaserGuideActivity";});
    }
    private static void directionHelp(Context c,Object group,int field) throws Exception {
        if(field==4 || field==5) information(c,group,"direction.help","按应用选择适配方式",
                "滚轮需要应用支持鼠标滚动；滑动在焦点窗口内部执行，上动作查看前面的内容，下动作查看后面的内容。“反向”只反转当前这项的执行方向；如需交换两键效果，请将上、下两项都选为反向。窗口移动、切换或真实触摸会取消滑动。不同应用需分别试用。");
    }
    private static String fieldLabel(int f,String value) {
        if(AppProfiles.INHERIT.equals(value)) return "继承全局默认";
        if(f>=13)return ProBindings.label(value); if(f<4 || f>AppProfiles.PREVIEW_FIELD) return PenAction.parse(value).label;
        if (f == AppProfiles.PREVIEW_FIELD) return "true".equals(value) ? "开启" : "关闭";
        return DirectionAdapter.parse(value).label;
    }
    private static void iconCommand(Context c,Object group,String key,String id,String summary,Runnable action) throws Exception {
        Object p=preference(c,"androidx.preference.Preference",key,MenuEntry.label(c,id),summary);
        call(p,"setIcon",MenuIcons.icon(c,id));
        listener(p,"setOnPreferenceClickListener",args->{action.run();return true;});add(group,p);
    }
    private static void command(Context c,Object group,String key,String title,String summary,Runnable action) throws Exception {
        Object pref=preference(c,"miuix.preference.TextPreference",key,title,summary);
        listener(pref,"setOnPreferenceClickListener",args->{action.run(); return true;}); add(group,pref);
    }
    private static void rebuildOnChange(Object fragment,String page,Binding b) {
        final long initial=config.get().revision;
        b.updates.add(s->{
            if(s.revision==initial || b.rebuildPosted) return;
            b.rebuildPosted=true;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                if(bindings.get(fragment)!=b) return;
                Activity a=activity(fragment); if(a==null || a.isFinishing()) return;
                try { if(b.nextPage!=null&&!b.nextPage.equals(page))showPage(a,b.nextPage);else refreshPage(fragment,page); } catch(Exception e) { Log.w("FocusPenSettings","Refresh failed",e); }
            });
        });
    }

    private static void toggle(Binding b, Object group, String key, String title, String summary) throws Exception {
        Object pref = preference(b.context, "miuix.preference.CheckBoxPreference", key, title, summary);
        listener(pref, "setOnPreferenceChangeListener", args -> { edit(b.context, key, args[1]); return false; });
        b.updates.add(s -> { call(pref, "setEnabled", s.available); call(pref, "setChecked", s.flag(key)); });
        add(group, pref);
    }
    private static void choices(Binding b, Object screen, String key, String title, Object[] values, String[] labels, String[] summaries) throws Exception {
        Object group = preference(b.context, "miuix.preference.SingleChoicePreferenceCategory", key + ".choices", title, null);
        if (ConfigSpec.LASER_ACTIVATION.equals(key))
            call(group, "setLayoutResource", GuideNative.resource(b.context, "layout", "miuix_preference_category_layout"));
        add(screen, group);
        ArrayList<Object> prefs = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            final Object value = values[i];
            Object item = preference(b.context, "miuix.preference.SingleChoicePreference", key + "." + i, labels[i], summaries == null ? null : summaries[i]);
            XposedHelpers.setObjectField(item, "o", String.valueOf(value));
            listener(item, "setOnPreferenceClickListener", args -> { edit(b.context, key, value); return true; });
            add(group, item); prefs.add(item);
        }
        b.updates.add(s -> {
            Object current = ConfigSpec.LONG_PRESS.equals(key) ? s.longPressMs() : ConfigSpec.DOUBLE_PRESS.equals(key) ? s.doublePressMs() : s.text(key);
            call(group, "setEnabled", s.available);
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) call(group, "n", prefs.get(i));
        });
    }
    private static void slider(Binding b, Object group, String key, String title, int min, int max, int step) throws Exception {
        Object sliderPreference = preference(b.context, "com.miui.widget.SpeedSeekBarPreference", key, title, null);
        call(sliderPreference, "setLayoutResource", GuideNative.resource(b.context,"layout","miui_seekbar_preference_layout"));
        XposedHelpers.setBooleanField(sliderPreference, "h", false);
        XposedHelpers.setIntField(sliderPreference, "c", min);
        call(sliderPreference, "g", max);
        listener(sliderPreference, "setOnPreferenceChangeListener", args -> {
            // SeekBar's key increment does not quantize touch drags.
            int raw = ((Number) args[1]).intValue();
            int value = Math.max(min, Math.min(max, min + Math.round((raw - min) / (float) step) * step));
            edit(b.context, key, value);
            return false;
        });
        b.updates.add(s -> {
            int value = s.number(key);
            call(sliderPreference, "setEnabled", s.available
                    && (!ConfigSpec.AUDIO_VOLUME.equals(key) || s.flag(ConfigSpec.AUDIO)));
            call(sliderPreference, "h", value, true);

        });
        add(group, sliderPreference);
    }
    private static void edit(Context c, String key, Object value) {
        try {
            Bundle b = new Bundle(); ConfigStore.put(b, "value", value);
            Bundle reply = c.getContentResolver().call(ConfigProvider.URI, ConfigProvider.METHOD_PREPARE_EDIT, key, b);
            PendingIntent command = reply == null ? null : reply.getParcelable("command");
            if (command == null) throw new IllegalStateException("No edit command");
            command.send();
        } catch (Throwable e) { Log.w("FocusPenSettings", "Edit failed", e); Toast.makeText(c, "设置未保存，请重试", Toast.LENGTH_SHORT).show(); }
    }

    private static Object preference(Context c, String cls, String key, String title, String summary) throws Exception {
        Class<?> type = loader.loadClass(cls);
        Object p=refreshing==null?null:refreshing.old.get(PREFIX+key);
        if(p==null||p.getClass()!=type){
            try { p = type.getConstructor(Context.class).newInstance(c); }
            catch (NoSuchMethodException e) { p = type.getConstructor(Context.class, AttributeSet.class).newInstance(c, null); }
        }
        if(refreshing!=null)refreshing.used.add(p);
        call(p, "setPersistent", false); call(p, "setKey", PREFIX + key); call(p, "setTitle", title);
        call(p, "setSummary", summary);
        return p;
    }
    private static Object category(Context c, Object screen, String key, String title) throws Exception {
        Object p = preference(c, "miuix.preference.PreferenceCategory", key, title, null); add(screen, p); return p;
    }
    private static Object navigation(Context c, Object group, String page, String title, String value) throws Exception {
        Object p = preference(c, "miuix.preference.TextPreference", "nav." + page, title, null);
        if (value != null) call(p, "h", value);
        listener(p, "setOnPreferenceClickListener", args -> {
            openPage(c,page); return true;
        });
        add(group, p); return p;
    }
    private static void openPage(Context c,String page){c.startActivity(new Intent().setClassName("com.miui.securitycore", SUBPAGE).putExtra(PAGE,page));}
    private static void information(Context c, Object group, String key, String title, String summary) throws Exception {
        Object p = preference(c, "miuix.preference.TextPreference", key, title, summary); call(p, "setSelectable", false); add(group, p);
    }
    private interface Callback { Object invoke(Object[] args); }
    private static void listener(Object preference, String setter, Callback callback) throws Exception {
        Method target = null;
        for (Method m : preference.getClass().getMethods()) if (setter.equals(m.getName()) && m.getParameterTypes().length == 1) { target = m; break; }
        if (target == null) throw new NoSuchMethodException(setter);
        Class<?> iface = target.getParameterTypes()[0];
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{iface}, (p, m, a) -> {
            if ("equals".equals(m.getName())) return p == a[0];
            if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
            if ("toString".equals(m.getName())) return "FocusPenPreferenceListener";
            try { return callback.invoke(a); }
            catch (Throwable e) { Log.w("FocusPenSettings", "Preference action failed", e); return false; }
        });
        target.invoke(preference, proxy);
    }
    private static Object call(Object object, String method, Object... args) { return XposedHelpers.callMethod(object, method, args); }
    private static void add(Object group,Object pref) {
        if(refreshing!=null){
            int order=refreshing.orders.getOrDefault(group,0);refreshing.orders.put(group,order+1);call(pref,"setOrder",order);
            Object parent=call(pref,"getParent");if(parent!=null&&parent!=group){long stableId=((Number)call(pref,"getId")).longValue();call(parent,"k",pref);call(group,"g",pref);XposedHelpers.setLongField(pref,"mId",stableId);return;}
        }
        call(group,"g",pref);
    }
    private static final class PageRefresh {
        final Map<String,Object> old=new LinkedHashMap<>();
        final java.util.Set<Object> used=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final Map<Object,Integer> orders=new java.util.IdentityHashMap<>();
        void collect(Object group)throws Exception{
            java.lang.reflect.Field children=loader.loadClass("androidx.preference.PreferenceGroup").getDeclaredField("c");children.setAccessible(true);
            for(Object item:new ArrayList<>((java.util.List<?>)children.get(group))){
                String key=(String)call(item,"getKey");if(key!=null)old.put(key,item);
                try{if(loader.loadClass("androidx.preference.PreferenceGroup").isInstance(item))collect(item);}catch(ClassNotFoundException e){throw new IllegalStateException(e);}
            }
        }
    }
    private static void refreshList(Object fragment){
        android.view.View list=(android.view.View)call(fragment,"getListView");if(list==null)return;
        list.post(()->{list.requestLayout();list.invalidate();list.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);});
    }
    private static void refreshPage(Object fragment,String page)throws Exception {
        PageRefresh state=new PageRefresh();state.collect(call(fragment,"getPreferenceScreen"));
        unbind(fragment);refreshing=state;
        try{createPage(fragment,page);
            for(Object old:state.old.values())if(!state.used.contains(old)){Object parent=call(old,"getParent");if(parent!=null)call(parent,"k",old);}
        }finally{refreshing=null;}
        refreshList(fragment);
    }
    private static Activity activity(Object f) { return (Activity) call(f, "getActivity"); }
    private static void setTitle(Object f, String text) {
        Activity a = activity(f);
        if (a == null) return;
        a.setTitle(text);
        // The borrowed Activity's manifest label is "轻捏". Its toolbar title
        // does not update Recents, which needs a separate task description.
        a.setTaskDescription(new android.app.ActivityManager.TaskDescription("手写笔"));
    }
    private static boolean validPage(String p) {
        if(p==null) return false;
        if(java.util.Arrays.asList(ProBindings.KEYS).contains(p))return true;
        if(java.util.List.of("ring","preview","keys","pro","rules","guide","content","addContent","selectContent","addProfile","official","templates","addTemplate").contains(p) || p.matches("button[12](Short|Long|Double)") || java.util.Arrays.asList(ConfigSpec.PRO_GESTURES).contains(p)) return true;
        if(p.startsWith("replace:"))return RingContent.validId(p.substring(8));
        if(p.startsWith("position:"))return RingContent.validId(p.substring(9));
        if(p.startsWith("item:")) return RingContent.validId(p.substring(5));
        if(p.startsWith("profile:")) return RingContent.validId(p.substring(8)) && !RingContent.isAction(p.substring(8));
        if(p.startsWith("profileMode:")) return RingContent.validId(p.substring(12)) && !RingContent.isAction(p.substring(12));
        if(p.startsWith("copyProfile:")) return RingContent.validId(p.substring(12)) && !RingContent.isAction(p.substring(12));
        if(p.startsWith("copyTarget:")) { String[] parts=p.split(":"); return parts.length==3 && !parts[1].equals(parts[2])
                && RingContent.validId(parts[1]) && !RingContent.isAction(parts[1]) && RingContent.validId(parts[2]) && !RingContent.isAction(parts[2]); }
        if(p.startsWith("field:")) { String[] parts=p.split(":"); return parts.length==3 && RingContent.validId(parts[1]) && parts[2].matches("[0-9]|1[0-9]|2[0-4]"); }
        if(p.startsWith("template:")) return AppProfiles.templateId(p.substring(9));
        if(p.startsWith("templateField:")) { String[] parts=p.split(":"); return parts.length==3 && AppProfiles.templateId(parts[1]) && parts[2].matches("[0-9]|1[0-9]|2[0-4]"); }
        return false;
    }
    private static String parent(String p) {
        if(p==null) return null;
        if(p.equals("content")) return "ring";
        if(p.equals("addContent") || p.startsWith("item:")) return "content";
        if(p.equals("selectContent"))return "content";
        if(p.startsWith("replace:"))return "item:"+p.substring(8);
        if(p.startsWith("position:"))return "item:"+p.substring(9);
        if(p.equals("addProfile") || p.startsWith("profile:")) return "rules";
        if(p.startsWith("field:")) return "profile:"+p.split(":")[1];
        if(p.startsWith("profileMode:")) return "profile:"+p.substring(12);
        if(p.startsWith("copyProfile:")) return "profile:"+p.substring(12);
        if(p.startsWith("copyTarget:")) return "copyProfile:"+p.split(":")[1];
        if(p.equals("templates") || p.equals("addTemplate")) return "rules";
        if(p.startsWith("templateField:")) return "template:"+p.split(":")[1];
        if(p.startsWith("template:")) return "templates";
        if(p.startsWith("button")) return "keys";
        if(java.util.Arrays.asList(ConfigSpec.PRO_GESTURES).contains(p)||java.util.Arrays.asList(ProBindings.KEYS).contains(p))return "pro";
        return null;
    }
    private static String title(String p) { if(java.util.Arrays.asList(ProBindings.KEYS).contains(p))return ProBindings.TITLES[java.util.Arrays.asList(ProBindings.KEYS).indexOf(p)];
        if (p.startsWith("button")) return buttonName(p.charAt(6) - '0') + " · " + (p.endsWith("Short")?"短按":p.endsWith("Long") ? "长按" : "快速双击");
        if(java.util.Arrays.asList(ConfigSpec.PRO_GESTURES).contains(p))return "Pro · "+proTitle(java.util.Arrays.asList(ConfigSpec.PRO_GESTURES).indexOf(p));
        if (p.startsWith("item:")) return "菜单项目";
        if(p.startsWith("replace:")||p.equals("selectContent"))return "选择项目内容";
        if(p.startsWith("position:"))return "排列位置";
        if (p.startsWith("profile:")) return "应用配置";
        if (p.startsWith("profileMode:")) return "配置方式";
        if (p.startsWith("copyProfile:")) return "复制到其他应用";
        if (p.startsWith("copyTarget:")) return "确认复制配置";
        if (p.startsWith("templateField:")) return fieldTitle(Integer.parseInt(p.split(":")[2]));
        if (p.startsWith("template:")) return "编辑模板";
        if (p.startsWith("field:")) return fieldTitle(Integer.parseInt(p.split(":")[2]));
        switch (p) {
            case "ring": return "弧形菜单"; case "preview": return "笔尖预览"; case "keys": return "全局按键设置";case "pro":return "焦点笔 Pro";
            case "rules": return "应用配置文件"; case "guide": return "使用指南";
            case "content": return "菜单内容"; case "addContent": return "选择项目内容";
            case "addProfile": return "添加应用配置"; case "official": return "灵感速记";
            case "templates": return "配置模板"; case "addTemplate": return "创建模板";
            case "debug":return "界面预览机型";
            default: return "焦点笔增强";
        }
    }
    private static String uniqueTemplateId(AppProfiles profiles,String name) {
        String base="template_"+Integer.toHexString(name.hashCode());
        if(!AppProfiles.templateId(base)) base="template_1";
        String id=base; int n=2; while(profiles.template(id)!=null) id=base+"_"+(n++); return id;
    }
    private static String buttonName(int button) {
        return switch (button) { case 1 -> "速记键"; case 2 -> "截图键"; case 3 -> "焦点键";
            default -> throw new IllegalArgumentException("Unknown pen button"); };
    }
    private static void bind(Object f, Binding b) { bindings.put(f, b); config.add(b); }
    private static void unbind(Object f) {
        Binding b = bindings.remove(f);
        if (b != null) {
            config.remove(b);
            if (b.demo != null) { b.demo.dismiss(); b.demo = null; }
        }
    }
    private static final class Binding implements ConfigClient.Listener {
        final Context context;
        final ArrayList<Consumer<ConfigSnapshot>> updates = new ArrayList<>();
        boolean rebuildPosted;
        String nextPage;
        GuideDemo demo;
        Binding(Context c) { context = c; }
        @Override public void changed(ConfigSnapshot snapshot) { for (Consumer<ConfigSnapshot> update : updates) update.accept(snapshot); }
    }
    private SettingsHooks() { }
}
