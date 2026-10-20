package com.focuspen.enhance.v2;

import android.content.*;
import android.os.*;
import de.robv.android.xposed.XposedBridge;

public final class UnlockGateTest {
    static final class Host extends Context {
        final UserManager user;
        final Host root;
        Object outer = this, registrationOwner;
        BroadcastReceiver receiver;
        int registrations, removals, starts;
        boolean unlockWhileRegistering, rejectRegistration, rejectRemoval;
        Host() { root = this; user = new UserManager(); }
        Host(Host root) { this.root = root; user = root.user; }
        @Override public Context createDeviceProtectedStorageContext() { return new Host(root); }
        @Override public <T> T getSystemService(Class<T> type) { return type.cast(user); }
        @Override public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String p, Handler h, int flags) {
            if (root.rejectRegistration) throw new IllegalStateException("Registration unavailable");
            root.receiver = r; root.registrations++; root.registrationOwner = outer;
            if (root.unlockWhileRegistering) user.unlocked = true;
            return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver r) {
            if (root.receiver != r || root.registrationOwner != outer)
                throw new AssertionError("Unregister used a different outer context");
            root.removals++;
            if (root.rejectRemoval) throw new IllegalArgumentException("Receiver not registered");
        }
        void broadcast() {
            // Delivery context may be another wrapper; unregister must still use registration context.
            receiver.onReceive(new Host(), new Intent(Intent.ACTION_USER_UNLOCKED));
        }
        void start() { starts++; }
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    public static void main(String[] args) {
        Host already = new Host(); already.user.unlocked = true;
        UnlockGate.run(already, already::start);
        check(already.starts == 1 && already.registrations == 0);

        Host cold = new Host(); UnlockGate.run(cold, cold::start);
        cold.outer = new Object(); // LoadedApk assigns Application after attach returns.
        cold.broadcast(); check(cold.starts == 0);
        cold.user.unlocked = true;
        cold.receiver.onReceive(cold, new Intent("unrelated")); check(cold.starts == 0);
        cold.broadcast(); cold.broadcast();
        check(cold.starts == 1 && cold.removals == 1 && XposedBridge.failures == 0);

        Host race = new Host(); race.unlockWhileRegistering = true;
        UnlockGate.run(race, race::start); race.broadcast();
        check(race.starts == 1 && race.removals == 1);

        Host cleanup = new Host(); cleanup.rejectRemoval = true;
        UnlockGate.run(cleanup, cleanup::start); cleanup.user.unlocked = true;
        cleanup.broadcast(); cleanup.broadcast();
        check(cleanup.starts == 1 && cleanup.removals == 1 && XposedBridge.failures == 1);

        Host failed = new Host();
        UnlockGate.run(failed, () -> { failed.start(); throw new IllegalStateException("Host failed"); });
        failed.user.unlocked = true; failed.broadcast(); failed.broadcast();
        check(failed.starts == 1 && failed.removals == 1 && XposedBridge.failures == 2);

        Host denied = new Host(); denied.rejectRegistration = true;
        UnlockGate.run(denied, denied::start);
        check(denied.starts == 0 && XposedBridge.failures == 3);

        Host worker = new Host(); worker.user.unlocked = true; Looper.current = null;
        UnlockGate.run(worker, worker::start); check(worker.starts == 0);
        Handler.drain(); check(worker.starts == 1);
        System.out.println("UnlockGate: cold/warm start, registration race, duplicate delivery, context ownership, failures and main dispatch passed");
    }
}
