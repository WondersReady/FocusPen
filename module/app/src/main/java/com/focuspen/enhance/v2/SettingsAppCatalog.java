package com.focuspen.enhance.v2;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-wide, asynchronously populated catalog for settings pages. */
final class SettingsAppCatalog {
    private static final String TAG = "FocusPenApps";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 250;
    private static final String[] OFFICIAL_PACKAGES = {
            "com.miui.notes", "com.miui.creation", "com.miui.voiceassist"
    };
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile Snapshot current = Snapshot.EMPTY;
    private static Context application;
    private static Handler worker;
    private static Runnable pendingRetry;
    private static long generation;
    private static Configuration configuration;

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(false, List.of(), Map.of());
        private final boolean ready;
        private final List<String> packages;
        private final Set<String> launchable;
        private final Map<String, Entry> entries;

        private Snapshot(boolean ready, List<String> packages, Map<String, Entry> entries) {
            this.ready = ready;
            this.packages = List.copyOf(packages);
            this.launchable = Set.copyOf(packages);
            this.entries = Map.copyOf(entries);
        }

        boolean ready() { return ready; }

        /** Launchable applications only; official action packages are available by lookup. */
        List<String> packages() { return packages; }

        boolean contains(String pkg) { return launchable.contains(pkg); }

        String label(String pkg) {
            Entry entry = entries.get(pkg);
            return entry == null ? pkg : entry.label;
        }

        Drawable icon(Context context, String pkg) {
            Entry entry = entries.get(pkg);
            Drawable.ConstantState state = entry == null ? null : entry.icon;
            if (state != null) {
                try { return state.newDrawable(context.getResources(), context.getTheme()).mutate(); }
                catch (RuntimeException failure) { Log.w(TAG, "Cached icon unavailable: " + pkg, failure); }
            }
            // This framework resource is cheap and does not query the package manager.
            return context.getDrawable(android.R.drawable.sym_def_app_icon).mutate();
        }
    }

    private record Entry(String label, Drawable.ConstantState icon) { }

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) throw new IllegalArgumentException("Application context required");
        synchronized (LOCK) {
            Configuration next = new Configuration(context.getResources().getConfiguration());
            if (application != null) {
                if (!next.equals(configuration)) {
                    configuration = next;
                    refresh();
                }
                return;
            }
            application = app;
            configuration = next;
            HandlerThread thread = new HandlerThread("FocusPenSettingsApps");
            thread.start();
            worker = new Handler(thread.getLooper());
        }

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { refresh(); }
            };
            if (Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(receiver, packages, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(receiver, packages);
        } catch (RuntimeException failure) { Log.w(TAG, "Package updates unavailable", failure); }
        try {
            app.registerComponentCallbacks(new ComponentCallbacks() {
                @Override public void onConfigurationChanged(Configuration next) {
                    synchronized (LOCK) { configuration = new Configuration(next); }
                    refresh();
                }
                @Override public void onLowMemory() { }
            });
        } catch (RuntimeException failure) { Log.w(TAG, "Configuration updates unavailable", failure); }
        refresh();
    }

    static Snapshot snapshot() { return current; }

    /** Call after an in-app theme change that does not dispatch Configuration. */
    static void invalidate() { refresh(); }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
        MAIN.post(() -> { if (LISTENERS.contains(listener)) listener.run(); });
    }

    static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    private static void refresh() {
        final long request;
        final Context app;
        final Handler background;
        synchronized (LOCK) {
            if (application == null) return;
            request = ++generation;
            app = application;
            background = worker;
            if (pendingRetry != null) {
                background.removeCallbacks(pendingRetry);
                pendingRetry = null;
            }
        }
        background.post(() -> load(app, request, 0));
    }

    private static void load(Context app, long request, int attempt) {
        synchronized (LOCK) { if (request != generation) return; }
        final Snapshot next;
        try {
            PackageManager pm = app.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            Set<String> launchable = new HashSet<>();
            for (ResolveInfo info : resolved) {
                if (info.activityInfo != null && info.activityInfo.packageName != null)
                    launchable.add(info.activityInfo.packageName);
            }
            Map<String, Entry> entries = new HashMap<>();
            for (String pkg : launchable) {
                Entry entry = resolve(pm, pkg);
                if (entry != null) entries.put(pkg, entry);
            }
            for (String pkg : OFFICIAL_PACKAGES) {
                if (!entries.containsKey(pkg)) {
                    Entry entry = resolve(pm, pkg);
                    if (entry != null) entries.put(pkg, entry);
                }
            }
            List<String> ordered = new ArrayList<>(launchable);
            Collator labels = Collator.getInstance(Locale.getDefault());
            ordered.sort((left, right) -> {
                int order = labels.compare(entries.containsKey(left) ? entries.get(left).label : left,
                        entries.containsKey(right) ? entries.get(right).label : right);
                return order == 0 ? left.compareTo(right) : order;
            });
            next = new Snapshot(true, ordered, entries);
        } catch (Throwable failure) {
            Log.w(TAG, "Keeping previous application catalog", failure);
            if (attempt < MAX_RETRIES) {
                synchronized (LOCK) {
                    if (request == generation) {
                        Runnable retry = () -> {
                            synchronized (LOCK) {
                                if (request != generation) return;
                                pendingRetry = null;
                            }
                            load(app, request, attempt + 1);
                        };
                        pendingRetry = retry;
                        worker.postDelayed(retry, RETRY_DELAY_MS << attempt);
                    }
                }
            }
            return;
        }
        synchronized (LOCK) {
            if (request != generation) return;
            current = next;
        }
        MAIN.post(() -> {
            if (current != next) return;
            for (Runnable listener : LISTENERS)
                if (LISTENERS.contains(listener)) {
                    try { listener.run(); }
                    catch (Throwable failure) { Log.w(TAG, "Application listener failed", failure); }
                }
        });
    }

    private static Entry resolve(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(info);
            Drawable icon;
            try { icon = pm.getApplicationIcon(info); }
            catch (Throwable failure) { Log.w(TAG, "Application icon unavailable: " + pkg, failure); icon = null; }
            return new Entry(name == null || name.length() == 0 ? pkg : name.toString(),
                    icon == null ? null : icon.getConstantState());
        } catch (PackageManager.NameNotFoundException absent) { return null; }
    }

    private SettingsAppCatalog() { }
}
