package com.focuspen.enhance.v2;

public final class ApplicationKeyDispatchTest {
    private static ApplicationKeyDispatch.Identity event(long time, int action) {
        return new ApplicationKeyDispatch.Identity(18, 257, 0, 93, 4, action, 0, 0, 100, time);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        ApplicationKeyDispatch gate = new ApplicationKeyDispatch();
        Object window = new Object(), other = new Object();
        var down = event(100, 0);
        gate.authorize(down, window, 100);
        check(gate.beforeDispatch(down, window, false, 101) == ApplicationKeyDispatch.Decision.UNMANAGED,
                "physical event cannot use an injection permit");
        Thread dispatchThread = new Thread(() -> check(
                gate.beforeDispatch(down, window, true, 102) == ApplicationKeyDispatch.Decision.PASS,
                "permit must survive asynchronous dispatch"));
        final Throwable[] failure = new Throwable[1];
        dispatchThread.setUncaughtExceptionHandler((thread, error) -> failure[0] = error);
        dispatchThread.start(); dispatchThread.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
        check(gate.beforeDispatch(down, window, true, 103) == ApplicationKeyDispatch.Decision.DROP,
                "duplicate replay cannot run policy or reach application twice");
        var up = event(200, 1);
        gate.authorize(up, window, 200);
        check(gate.beforeDispatch(up, other, true, 201) == ApplicationKeyDispatch.Decision.DROP,
                "same UID or package must not permit another window");
        check(gate.beforeDispatch(up, window, true, 202) == ApplicationKeyDispatch.Decision.DROP,
                "focus returning cannot revive a rejected release");
        var rejected = event(300, 0);
        gate.reject(gate.authorize(rejected, window, 300));
        check(gate.beforeDispatch(rejected, window, true, 301) == ApplicationKeyDispatch.Decision.DROP,
                "failed injection cannot be revived");
        var delayed = event(400, 1);
        gate.authorize(delayed, window, 400);
        check(gate.beforeDispatch(delayed, window, true, 20_000) == ApplicationKeyDispatch.Decision.DROP,
                "late native callback must not enter official shortcut policy");
        check(gate.beforeDispatch(event(500, 0), window, true, 20_001) == ApplicationKeyDispatch.Decision.UNMANAGED,
                "unrelated injection must retain its normal policy");
        for (int i = 0; i < 256; i++) gate.authorize(event(1000+i, 0), window, 30_000);
        try { gate.authorize(event(2000, 0), window, 60_000); throw new AssertionError("unbounded permits"); }
        catch (IllegalStateException expected) { }
        System.out.println("Application key dispatch: cross-thread, window binding, duplicate, timeout and capacity PASS");
    }
}
