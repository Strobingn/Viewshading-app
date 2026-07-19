# Fix blank Google Map (Authorization failure)

Device logcat shows:

```text
E Google Android Maps SDK: Authorization failure.
Android Application (<cert_fingerprint>;<package_name>):
23:A1:3A:DD:C1:18:8E:10:BE:92:8B:0A:87:D8:33:99:19:31:1B:44;com.viewshed.app
```

The API key **is** in the APK. Google rejects it because the key’s **Android app restrictions** do not include this package (FieldOps uses `com.strobingn.wildlifefieldops`).

## Fix (2 minutes)

1. Open [Google Cloud Credentials](https://console.cloud.google.com/apis/credentials)
2. Edit the key used in `secrets.properties` (`MAPS_API_KEY`)
3. **Application restrictions** → **Android apps** → **Add an item**:
   - **Package name:** `com.viewshed.app`
   - **SHA-1:** `23:A1:3A:DD:C1:18:8E:10:BE:92:8B:0A:87:D8:33:99:19:31:1B:44`
4. Enable APIs:
   - **Maps SDK for Android**
   - **Elevation API** (real terrain mode)
   - **Geocoding API** (optional, place search)
5. Save → wait 1–5 minutes → force-stop Viewshed → reopen

Debug SHA-1 matches the usual Android debug keystore (same as FieldOps debug builds). You only need the **new package name** line if FieldOps is already listed with this SHA-1.

## Verify

```powershell
cd F:\Viewshading-app
.\gradlew.bat :app:installDebug
adb logcat | findstr /i "Authorization Maps"
```

You should **not** see `Authorization failure` after the console change.
