package com.focuspen.enhance.v2;

public final class HyperOsCompatibilityTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(HyperOsCompatibility.supports("4", "OS4.0", "OS4.0.4.0.XPYCNXM", ""));
        check(HyperOsCompatibility.supports("3", "OS3.2", "OS3.2.99.0.ABC", ""));
        check(HyperOsCompatibility.supports("", "OS4.99", "", ""));
        check(HyperOsCompatibility.supports("", "", "", "Xiaomi/piano/piano:17/CP2A.260605.016/OS4.0.2.0.XPYCNXM:user/release-keys"));
        check(!HyperOsCompatibility.supports("5", "OS5.0", "OS5.0.1.0", ""));
        check(!HyperOsCompatibility.supports("4", "OS5.0", "", ""));
        check(!HyperOsCompatibility.supports("", "OS15.0", "", ""));
        check(!HyperOsCompatibility.supports("2", "OS2.0", "", ""));
        check(!HyperOsCompatibility.supports("", "V14.0.4.0", "", ""));
        check(!HyperOsCompatibility.supports("", "", "", ""));
        check(!HyperOsCompatibility.supports(null, null, null, null));
        check(!HyperOsCompatibility.supports("4x", "OS4.0", "", ""));
        check(!HyperOsCompatibility.supports("", "OS999999999999999999999.0", "", ""));
        System.out.println("HyperOS major policy: 13 cases PASS");
    }
}
