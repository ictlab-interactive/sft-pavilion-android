package com.sftpavilion.updates;

import org.json.JSONObject;
import java.io.IOException;

/** Strict metadata for the fixed, public SFT Pavilion release channel. */
public final class ReleaseInfo {
    public static final String PACKAGE = "com.sftpavilion.reminderfix";
    public static final String ROOT = "https://github.com/ictlab-interactive/sft-pavilion-android/releases/";
    public static final String CHANNEL = ROOT + "latest/download/update.json";
    public static final long MAX_SIZE = 150L * 1024 * 1024;
    public final int code, minSdk;
    public final long size;
    public final String version, sha256, notes, apkUrl;

    public ReleaseInfo(String json) throws Exception {
        if (json.length() > 65536) throw new IOException("Update details are too large");
        JSONObject o = new JSONObject(json);
        if (!PACKAGE.equals(o.getString("packageName"))) throw new IOException("Wrong app");
        Object rawCode = o.get("versionCode");
        if (!(rawCode instanceof Integer) && !(rawCode instanceof Long)) throw new IOException("Version code must be an integer");
        long longCode = ((Number) rawCode).longValue();
        if (longCode > Integer.MAX_VALUE || longCode < 1) throw new IOException("Invalid version code");
        code = (int) longCode;
        version = o.getString("versionName");
        minSdk = o.optInt("minSdk", 23);
        size = o.getLong("size");
        sha256 = o.getString("sha256").toLowerCase(java.util.Locale.US);
        notes = o.optString("releaseNotes", "Improvements and fixes.");
        if (code < 1 || !version.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))
            throw new IOException("Invalid update version");
        if (minSdk < 23 || minSdk > 100 || size < 1 || size > MAX_SIZE || !sha256.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid update details");
        if (notes.length() > 6000) throw new IOException("Release notes are too long");
        // Never accept an arbitrary download URL supplied in metadata.
        apkUrl = ROOT + "download/v" + version + "/SFT-Pavilion.apk";
    }

    public boolean isNewer(int installedCode, int deviceSdk) {
        return code > installedCode && deviceSdk >= minSdk;
    }

    public boolean shouldPrompt(int installedCode, int deviceSdk, int deferredCode, long deferUntil, long now) {
        return isNewer(installedCode, deviceSdk) && (code != deferredCode || now >= deferUntil);
    }
}
