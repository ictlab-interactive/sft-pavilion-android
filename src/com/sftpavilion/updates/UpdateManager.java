package com.sftpavilion.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native update checks and explicit, user-confirmed APK installation. No web bridge. */
public final class UpdateManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static WeakReference<Activity> screen = new WeakReference<Activity>(null);
    private static boolean dialogOpen, waitingForPermission, foreground;
    private static ReleaseInfo ready;
    private static final long SIX_HOURS = 6 * 60 * 60 * 1000L;
    private static final long CHECK_INTERVAL = 15 * 60 * 1000L;
    private static final Runnable AUTO_CHECK = new Runnable() { public void run() {
        Activity current = activity();
        if (!foreground || current == null) return;
        check(current);
        MAIN.postDelayed(this, CHECK_INTERVAL);
    }};

    private UpdateManager() {}
    private static Activity activity() {
        Activity a = screen.get();
        return a == null || a.isFinishing() || a.isDestroyed() ? null : a;
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sft_updates_v1", Context.MODE_PRIVATE); }
    private static int installedCode(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
    }
    private static void toast(String text) {
        Activity a = activity(); if (a != null) Toast.makeText(a, text, Toast.LENGTH_LONG).show();
    }

    /** Called from the original MainActivity.onResume after its existing logic. */
    public static void onResume(Activity a) {
        screen = new WeakReference<Activity>(a);
        foreground = true;
        MAIN.removeCallbacks(AUTO_CHECK);
        // Check on every opening/return, then periodically while the app stays visible.
        MAIN.postDelayed(AUTO_CHECK, 6000);
        if (waitingForPermission && ready != null) {
            waitingForPermission = false;
            if (Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls()) {
                // Re-hash the file in the worker before granting the installer access.
                installReady(ready);
            } else toast("Update installation was not enabled. Reopen the app to try again.");
            return;
        }
    }

    /** Stop polling when the app is no longer visible. */
    public static void onPause(Activity a) {
        if (screen.get() == a) {
            foreground = false;
            MAIN.removeCallbacks(AUTO_CHECK);
        }
    }

    private static void check(Activity a) {
        screen = new WeakReference<Activity>(a);
        if (dialogOpen || !BUSY.compareAndSet(false, true)) return;
        final Context app = a.getApplicationContext();
        IO.execute(new Runnable() { public void run() {
            try {
                final ReleaseInfo info = new ReleaseInfo(readMetadata());
                final boolean newer = info.shouldPrompt(installedCode(app), Build.VERSION.SDK_INT,
                    prefs(app).getInt("deferred_code", 0), prefs(app).getLong("defer_until", 0), System.currentTimeMillis());
                MAIN.post(new Runnable() { public void run() {
                    BUSY.set(false);
                    if (!foreground || activity() == null) return;
                    if (newer) prompt(info);
                }});
            } catch (final Exception failure) {
                MAIN.post(new Runnable() { public void run() {
                    BUSY.set(false);
                    // Offline/server failures stay silent; retry on return or the next interval.
                }});
            }
        }});
    }
    private static void defer(ReleaseInfo info) {
        Activity a = activity();
        if (a != null) prefs(a).edit().putInt("deferred_code", info.code)
            .putLong("defer_until", System.currentTimeMillis() + SIX_HOURS).apply();
    }
    private static void prompt(final ReleaseInfo info) {
        Activity a = activity(); if (a == null || dialogOpen) return;
        dialogOpen = true;
        new AlertDialog.Builder(a).setTitle("SFT Pavilion " + info.version + " is available")
            .setMessage(info.notes + "\n\nDownload: " + String.format(Locale.US, "%.1f MB", info.size / 1048576.0)
                + "\nAndroid will ask you to confirm installation.")
            .setNegativeButton("Later", (d, which) -> defer(info))
            .setPositiveButton("Update now", (d, which) -> download(info))
            .setOnCancelListener(d -> defer(info))
            .setOnDismissListener(d -> dialogOpen = false).show();
    }

    private static HttpURLConnection open(String address) throws IOException {
        for (int redirects = 0; redirects < 8; redirects++) {
            URL url = new URL(address);
            String host = url.getHost().toLowerCase(Locale.US);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null ||
                    !(host.equals("github.com") || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com")))
                throw new IOException("Untrusted update location");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setUseCaches(false);
            c.setInstanceFollowRedirects(false); c.setRequestProperty("User-Agent", "SFT-Pavilion-Updater/1");
            c.setRequestProperty("Cache-Control", "no-cache");
            int status = c.getResponseCode();
            if (status >= 300 && status <= 399) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("Missing update location");
                address = new URL(url, location).toString(); continue;
            }
            if (status != 200) { c.disconnect(); throw new IOException("Update server response " + status); }
            return c;
        }
        throw new IOException("Too many update redirects");
    }
    private static String readMetadata() throws IOException {
        HttpURLConnection c = open(ReleaseInfo.CHANNEL);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 65536) throw new IOException("Update metadata too large");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }

    private static void download(final ReleaseInfo info) {
        Activity a = activity(); if (a == null || !BUSY.compareAndSet(false, true)) return;
        final Context app = a.getApplicationContext();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        final ProgressDialog progress = new ProgressDialog(a);
        progress.setTitle("Downloading update"); progress.setMessage("SFT Pavilion " + info.version);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL); progress.setMax(100);
        progress.setCancelable(false);
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel", (d, which) -> cancel.set(true));
        progress.show();
        IO.execute(new Runnable() { public void run() {
            File temp = new File(app.getCacheDir(), "SFT-Pavilion-update.part.apk");
            File apk = UpdateFileProvider.apk(app);
            HttpURLConnection connection = null;
            try {
                connection = open(info.apkUrl);
                // getContentLengthLong() is unavailable on the app's Android 6 minimum.
                long declared = -1;
                String lengthHeader = connection.getHeaderField("Content-Length");
                if (lengthHeader != null) declared = Long.parseLong(lengthHeader);
                if (declared > 0 && declared != info.size) throw new IOException("Wrong download size");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0; int last = -1;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[32768]; int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancel.get()) throw new IOException("Cancelled");
                        total += n; if (total > info.size) throw new IOException("Download too large");
                        digest.update(buf, 0, n); out.write(buf, 0, n);
                        final int percent = (int)(total * 100 / info.size);
                        if (percent != last) { last = percent; MAIN.post(() -> { if (progress.isShowing()) progress.setProgress(percent); }); }
                    }
                    out.getFD().sync();
                }
                if (cancel.get()) throw new IOException("Cancelled");
                if (total != info.size || !hex(digest.digest()).equals(info.sha256)) throw new IOException("Update verification failed");
                PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(temp.getAbsolutePath(), 0);
                if (archive == null || !ReleaseInfo.PACKAGE.equals(archive.packageName) || archive.versionCode != info.code
                        || !info.isNewer(installedCode(app), Build.VERSION.SDK_INT)) throw new IOException("Wrong update package");
                if (apk.exists() && !apk.delete()) throw new IOException("Could not replace downloaded update");
                if (!temp.renameTo(apk)) throw new IOException("Could not save downloaded update");
                ready = info;
                MAIN.post(() -> { dismiss(progress); BUSY.set(false); if (!cancel.get()) requestInstall(info); });
            } catch (Exception e) {
                temp.delete();
                MAIN.post(() -> { dismiss(progress); BUSY.set(false);
                    if (!cancel.get()) toast("Update could not be downloaded or verified. Reopen the app to try again.");
                });
            } finally { if (connection != null) connection.disconnect(); }
        }});
    }
    private static void dismiss(ProgressDialog p) { try { if (p.isShowing()) p.dismiss(); } catch (RuntimeException ignored) {} }
    private static String hex(byte[] digest) { StringBuilder b = new StringBuilder(); for (byte n : digest) b.append(String.format(Locale.US, "%02x", n & 255)); return b.toString(); }

    private static void requestInstall(final ReleaseInfo info) {
        Activity a = activity(); if (a == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            dialogOpen = true;
            new AlertDialog.Builder(a).setTitle("Allow this app to install its update")
                .setMessage("Enable Allow from this source for SFT Pavilion, then return here to finish the update.")
                .setNegativeButton("Later", (d, which) -> {})
                .setPositiveButton("Open settings", (d, which) -> {
                    Activity current = activity(); if (current == null) return;
                    try {
                        waitingForPermission = true;
                        current.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + current.getPackageName())));
                    } catch (RuntimeException e) { waitingForPermission = false; toast("Open Android Settings → Special app access → Install unknown apps."); }
                }).setOnDismissListener(d -> dialogOpen = false).show();
        } else installReady(info);
    }

    private static void installReady(final ReleaseInfo info) {
        Activity a = activity(); if (a == null || !BUSY.compareAndSet(false, true)) return;
        final Context app = a.getApplicationContext();
        IO.execute(() -> {
            try {
                File apk = UpdateFileProvider.apk(app);
                if (apk.length() != info.size) throw new IOException("Update file missing");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new java.io.FileInputStream(apk)) {
                    byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n);
                }
                if (!info.sha256.equals(hex(digest.digest()))) throw new IOException("Update file changed");
                MAIN.post(() -> {
                    BUSY.set(false); Activity current = activity(); if (current == null) return;
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(UpdateFileProvider.URI, "application/vnd.android.package-archive");
                        intent.setClipData(ClipData.newRawUri("SFT Pavilion update", UpdateFileProvider.URI));
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        current.startActivity(intent);
                    } catch (RuntimeException e) { toast("Android could not open the installer. Download the APK from the Releases page."); }
                });
            } catch (Exception e) { MAIN.post(() -> { BUSY.set(false); toast("The downloaded update is missing or changed. Reopen the app to download it again."); }); }
        });
    }
}
