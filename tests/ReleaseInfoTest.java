import com.sftpavilion.updates.ReleaseInfo;
import org.json.JSONObject;

public final class ReleaseInfoTest {
    private static int checks;
    private static JSONObject valid() throws Exception {
        return new JSONObject().put("packageName", ReleaseInfo.PACKAGE).put("versionCode", 11)
            .put("versionName", "1.2.8").put("minSdk", 23).put("size", 512000)
            .put("sha256", "a".repeat(64)).put("releaseNotes", "Fixed reminders.");
    }
    private static void require(boolean good, String message) {
        checks++; if (!good) throw new AssertionError(message);
    }
    private static void reject(String field, Object value) throws Exception {
        boolean rejected = false;
        try { new ReleaseInfo(valid().put(field, value).toString()); }
        catch (Exception expected) { rejected = true; }
        require(rejected, "Must reject " + field + ": " + value);
    }
    public static void main(String[] args) throws Exception {
        ReleaseInfo r = new ReleaseInfo(valid().toString());
        require(r.isNewer(10, 23), "New code is available on supported Android");
        require(!r.isNewer(11, 35), "Same release must not prompt again");
        require(!r.isNewer(12, 35), "Older release must not downgrade");
        require(!r.isNewer(10, 22), "Unsupported Android must not prompt");
        require(r.shouldPrompt(10, 35, 0, 0, 1000), "Automatic popup for a new release");
        require(!r.shouldPrompt(11, 35, 0, 0, 1000), "No popup when already current");
        require(!r.shouldPrompt(10, 35, 11, 2000, 1000), "Later snoozes this release");
        require(r.shouldPrompt(10, 35, 11, 2000, 2000), "Popup returns after snooze expires");
        require(r.shouldPrompt(10, 35, 10, 2000, 1000), "A different newer release bypasses snooze");
        require(r.apkUrl.equals(ReleaseInfo.ROOT + "download/v1.2.8/SFT-Pavilion.apk"), "Exact APK URL");
        require(new ReleaseInfo(valid().put("apkUrl", "https://evil.example/a.apk").toString()).apkUrl.equals(r.apkUrl), "Ignore injected URL");
        reject("packageName", "another.app");
        reject("versionCode", 0); reject("versionCode", -1); reject("versionCode", "12");
        reject("versionCode", 12.5); reject("versionCode", 2147483648L);
        reject("versionName", "../../evil"); reject("versionName", "1.2.8?x=1"); reject("versionName", "v1.2.8");
        reject("sha256", "invalid"); reject("sha256", "a".repeat(63));
        reject("size", 0); reject("size", ReleaseInfo.MAX_SIZE + 1);
        reject("minSdk", 22); reject("minSdk", 101);
        reject("releaseNotes", "x".repeat(6001));
        require(new ReleaseInfo(valid().put("sha256", "A".repeat(64)).toString()).sha256.equals("a".repeat(64)), "Normalize digest case");
        System.out.println("PASS: " + checks + " update policy checks");
    }
}
