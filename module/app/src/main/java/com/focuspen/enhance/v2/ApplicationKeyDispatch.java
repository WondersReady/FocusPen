package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Cross-thread permits for module-arbitrated application keys, never a global policy bypass. */
final class ApplicationKeyDispatch {
    enum Decision { UNMANAGED, PASS, DROP }
    record Identity(int device, int source, int display, int code, int scan, int action,
                    int repeat, int meta, long downTime, long eventTime) { }
    private static final int CAPACITY = 256;
    private static final long LIFETIME_MS = 10_000;
    static final class Permit {
        final Identity identity;
        final Object target;
        final long expires;
        boolean dispatched, rejected;
        Permit(Identity identity, Object target, long expires) {
            this.identity = identity; this.target = target; this.expires = expires;
        }
    }
    private final List<Permit> permits = new ArrayList<>();

    synchronized Permit authorize(Identity identity, Object target, long now) {
        if (target == null) throw new IllegalArgumentException("Input channel token required");
        expire(now);
        if (permits.size() >= CAPACITY) throw new IllegalStateException("Application key queue full");
        for (Permit permit : permits)
            if (permit.identity.equals(identity)) throw new IllegalStateException("Duplicate application key");
        Permit permit = new Permit(identity, target, now + LIFETIME_MS);
        permits.add(permit);
        return permit;
    }

    synchronized Decision beforeDispatch(Identity identity, Object focus, boolean filtered, long now) {
        expire(now);
        if (!filtered) return Decision.UNMANAGED;
        for (Permit permit : permits) {
            if (!permit.identity.equals(identity)) continue;
            if (permit.dispatched || permit.rejected || !permit.target.equals(focus)) {
                permit.rejected = true;
                permit.dispatched = true;
                return Decision.DROP;
            }
            permit.dispatched = true;
            return Decision.PASS;
        }
        return Decision.UNMANAGED;
    }

    synchronized void reject(Permit permit) { permit.rejected = true; }
    private void expire(long now) {
        for (Iterator<Permit> it = permits.iterator(); it.hasNext();) {
            Permit permit = it.next();
            if (permit.expires > now) continue;
            // Native injection timeout does not prove that a queued event disappeared.
            // Keep an undispatched tombstone so a late delivery cannot reenter policy.
            if (permit.dispatched) it.remove();
            else permit.rejected = true;
        }
    }
}
