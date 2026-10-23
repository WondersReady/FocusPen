package com.focuspen.enhance.v2;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One major-version policy for all hosts; maintenance builds do not gate hooks. */
final class HyperOsCompatibility {
    private static final Pattern OS = Pattern.compile("(?:^|/)OS([0-9]+)(?:\\.|$)");
    private static Boolean allowed;
    private HyperOsCompatibility() { }

    static synchronized boolean supported() {
        if (allowed == null) allowed = supports(property("ro.mi.os.version.code"),
                property("ro.mi.os.version.name"), property("ro.mi.os.version.incremental"), fingerprint());
        return allowed;
    }
    static void requireSupported() {
        if (!supported()) throw new IllegalStateException("FocusPen requires HyperOS 3.x or 4.x; HyperOS 5+ needs adaptation");
    }
    static boolean supports(String code, String name, String incremental, String fingerprint) {
        int found = -1;
        for (String value : new String[]{name, incremental, fingerprint}) {
            Matcher match = OS.matcher(value == null ? "" : value.trim());
            if (match.find()) {
                int major;
                try { major = Integer.parseInt(match.group(1)); } catch (NumberFormatException e) { return false; }
                if (major != 3 && major != 4 || found != -1 && found != major) return false;
                found = major;
            }
        }
        if (code != null && !code.isBlank()) {
            int major;
            try { major = Integer.parseInt(code.trim()); } catch (NumberFormatException e) { return false; }
            if (major != 3 && major != 4 || found != -1 && found != major) return false;
            found = major;
        }
        return found == 3 || found == 4;
    }
    private static String property(String name) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name); }
        catch (ReflectiveOperationException | RuntimeException e) { return ""; }
    }
    private static String fingerprint() {
        try { return (String) Class.forName("android.os.Build").getField("FINGERPRINT").get(null); }
        catch (ReflectiveOperationException | RuntimeException e) { return ""; }
    }
}
