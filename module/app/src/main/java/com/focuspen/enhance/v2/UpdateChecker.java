package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Checks the public GitHub release feed without downloading or installing an APK. */
final class UpdateChecker {
    private static final String PREFS = "focuspen_updates";
    private static final String API = "https://api.github.com/repos/WondersReady/FocusPen/releases?per_page=100";
    private static final String RELEASE_ROOT = "/WondersReady/FocusPen/releases/";
    private static final Pattern TAG = Pattern.compile("^([1-9][0-9]*)-(.+)$");
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Object CHECK_LOCK = new Object();

    enum State { NEVER_CHECKED, AVAILABLE, CURRENT, ERROR, RATE_LIMITED }
    record Release(long versionCode, String versionName, String tag, String pageUrl,
                   String apkUrl, boolean prerelease) { }
    record Result(State state, Release info, String error, long checkedAt,
                  long retryAt, boolean fromCache) { }
    private record Tag(long code, String version) { }

    static boolean isEnabled(Context context) { return preferences(context).getBoolean("enabled", true); }

    static void setEnabled(Context context, boolean enabled) {
        if (!preferences(context).edit().putBoolean("enabled", enabled).commit())
            throw new IllegalStateException("Cannot save update preference");
        UpdateCheckJob.sync(context);
    }

    static Result last(Context context) {
        SharedPreferences p = preferences(context);
        long checkedAt = p.getLong("checkedAt", 0);
        Release info = storedRelease(p);
        String state = p.getString("state", "");
        if (state.isEmpty()) return new Result(State.NEVER_CHECKED, null, null, 0, 0, true);
        if ("success".equals(state)) {
            if (info == null) return new Result(State.ERROR, null, "Cached release is invalid", checkedAt, 0, true);
            return new Result(info.versionCode() > BuildConfig.VERSION_CODE ? State.AVAILABLE : State.CURRENT,
                    info, null, checkedAt, 0, true);
        }
        if ("rateLimited".equals(state))
            return new Result(State.RATE_LIMITED, info, p.getString("error", "GitHub rate limit"),
                    checkedAt, p.getLong("retryAt", 0), true);
        return new Result(State.ERROR, info, p.getString("error", "Update check failed"), checkedAt, 0, true);
    }

    /** Blocking; call only from an IO worker. force bypasses the daily interval, not server limits. */
    static Result check(Context context, boolean force) { return check(context, force, null); }

    static Result check(Context context, boolean force, Network network) {
        synchronized (CHECK_LOCK) {
            SharedPreferences p = preferences(context);
            Result previous = last(context);
            long now = System.currentTimeMillis();
            if (!force && !p.getBoolean("enabled", true)) return previous;
            long retryAt = p.getLong("retryAt", 0);
            if (retryAt > now)
                return new Result(State.RATE_LIMITED, previous.info(), "GitHub rate limit",
                        previous.checkedAt(), retryAt, true);
            if (!force && withinDailyWindow(p.getLong("checkedAt", 0), now)) return previous;

            HttpURLConnection connection = null;
            try {
                URL url = new URL(API);
                connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
                connection.setRequestProperty("User-Agent", "FocusPen-UpdateChecker");
                if (previous.info() != null) {
                    String etag = p.getString("etag", "");
                    if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
                }
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED && previous.info() != null)
                    return saveSuccess(p, previous.info(), now, p.getString("etag", ""));
                if (status == HttpURLConnection.HTTP_OK) {
                    Release info;
                    try (InputStream stream = connection.getInputStream()) {
                        info = parseReleases(readLimited(stream));
                    }
                    String etag = connection.getHeaderField("ETag");
                    return saveSuccess(p, info, now, etag != null && etag.length() <= 256 ? etag : "");
                }
                long blockedUntil = status == 429 || status == 403
                        ? retryAt(connection.getHeaderField("Retry-After"),
                                connection.getHeaderField("X-RateLimit-Reset"), now) : 0;
                if (status == 429 && blockedUntil <= now) blockedUntil = now + 60_000;
                if (blockedUntil > now)
                    return saveFailure(p, State.RATE_LIMITED, "GitHub HTTP " + status, now, blockedUntil);
                return saveFailure(p, State.ERROR, "GitHub HTTP " + status, now, 0);
            } catch (Exception failure) {
                String detail = failure.getMessage();
                if (detail == null || detail.isEmpty()) detail = failure.getClass().getSimpleName();
                if (detail.length() > 120) detail = detail.substring(0, 120);
                return saveFailure(p, State.ERROR, detail, now, 0);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    static boolean withinDailyWindow(long checkedAt, long now) {
        return checkedAt > 0 && now >= checkedAt && now - checkedAt < DAY_MS;
    }

    static long retryAt(String retryAfter, String rateReset, long now) {
        long until = 0;
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) until = Math.addExact(now, Math.multiplyExact(seconds, 1000));
            } catch (NumberFormatException | ArithmeticException ignored) {
                try {
                    until = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli();
                } catch (RuntimeException ignoredDate) { }
            }
        }
        if (rateReset != null) {
            try { until = Math.max(until, Math.multiplyExact(Long.parseLong(rateReset.trim()), 1000)); }
            catch (NumberFormatException | ArithmeticException ignored) { }
        }
        return until;
    }

    static Release parseReleases(String source) throws JSONException {
        JSONArray releases = new JSONArray(source);
        Release best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject entry = releases.optJSONObject(i);
            if (entry == null || entry.optBoolean("draft", true)) continue;
            String tag = entry.optString("tag_name", "");
            Tag parsed = parseTag(tag);
            String page = entry.optString("html_url", "");
            if (parsed == null || !trustedReleaseUrl(page, tag)) continue;
            JSONArray assets = entry.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                String apk = asset.optString("browser_download_url", "");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")
                        || !trustedAssetUrl(apk, tag, name)) continue;
                if (best == null || parsed.code() > best.versionCode())
                    best = new Release(parsed.code(), parsed.version(), tag, page, apk,
                            entry.optBoolean("prerelease", false));
                break;
            }
        }
        if (best == null) throw new JSONException("No valid APK release in GitHub response");
        return best;
    }

    private static Tag parseTag(String tag) {
        Matcher match = TAG.matcher(tag);
        if (!match.matches() || match.group(2).trim().isEmpty()) return null;
        try { return new Tag(Long.parseLong(match.group(1)), match.group(2)); }
        catch (NumberFormatException ignored) { return null; }
    }

    static boolean trustedReleaseUrl(String value, String tag) {
        URI uri = trustedUri(value);
        return uri != null && (RELEASE_ROOT + "tag/" + tag).equals(uri.getPath());
    }

    static boolean trustedAssetUrl(String value, String tag, String name) {
        URI uri = trustedUri(value);
        return uri != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && (RELEASE_ROOT + "download/" + tag + "/" + name).equals(uri.getPath());
    }

    private static URI trustedUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !uri.normalize().getRawPath().equals(uri.getRawPath())) return null;
            for (String segment : uri.getPath().split("/"))
                if (segment.equals(".") || segment.equals("..")) return null;
            return uri;
        } catch (RuntimeException ignored) { return null; }
    }

    private static String readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IOException("GitHub response too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Result saveSuccess(SharedPreferences p, Release info, long now, String etag) {
        p.edit().putString("state", "success").putLong("checkedAt", now).putLong("retryAt", 0)
                .putString("error", null).putString("etag", etag)
                .putString("tag", info.tag()).putString("page", info.pageUrl())
                .putString("apk", info.apkUrl()).putBoolean("prerelease", info.prerelease()).apply();
        return new Result(info.versionCode() > BuildConfig.VERSION_CODE ? State.AVAILABLE : State.CURRENT,
                info, null, now, 0, false);
    }

    private static Result saveFailure(SharedPreferences p, State state, String error, long now, long retryAt) {
        p.edit().putString("state", state == State.RATE_LIMITED ? "rateLimited" : "error")
                .putString("error", error).putLong("checkedAt", now).putLong("retryAt", retryAt).apply();
        return new Result(state, storedRelease(p), error, now, retryAt, false);
    }

    private static Release storedRelease(SharedPreferences p) {
        String tag = p.getString("tag", "");
        Tag parsed = parseTag(tag);
        String page = p.getString("page", "");
        String apk = p.getString("apk", "");
        if (parsed == null || !trustedReleaseUrl(page, tag)) return null;
        String prefix = RELEASE_ROOT + "download/" + tag + "/";
        URI uri = trustedUri(apk);
        if (uri == null || !uri.getPath().startsWith(prefix)) return null;
        String name = uri.getPath().substring(prefix.length());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk") || !trustedAssetUrl(apk, tag, name)) return null;
        return new Release(parsed.code(), parsed.version(), tag, page, apk,
                p.getBoolean("prerelease", false));
    }

    private static SharedPreferences preferences(Context context) {
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName()))
            throw new SecurityException("Module process required");
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private UpdateChecker() { }
}
