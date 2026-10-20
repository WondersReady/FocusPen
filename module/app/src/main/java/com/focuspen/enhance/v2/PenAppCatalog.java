package com.focuspen.enhance.v2;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PenAppCatalog {
    private static List<String> packages;
    static synchronized List<String> installed(Context context) {
        if (packages == null) {
            List<String> next = new ArrayList<>();
            Context source = context;
            try {
                if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName()))
                    source = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable ignored) { }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    source.getAssets().open("pen-apps-v1.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
                    if (!RingContent.validId(line) || next.contains(line)) throw new IllegalArgumentException("Invalid pen catalog");
                    next.add(line);
                }
                packages = List.copyOf(next);
            } catch (Exception failure) { throw new IllegalStateException("Cannot read pen application catalog", failure); }
        }
        List<String> installed = new ArrayList<>();
        for (String id : packages) {
            try { context.getPackageManager().getApplicationInfo(id, 0); installed.add(id); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { }
        }
        return installed;
    }
}
