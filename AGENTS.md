# Viewshade (Viewshed Calculator) — agent guide

Native Android app for line-of-sight viewshed analysis on Google Maps.

## Project identity

| | |
|---|---|
| **Root** | `F:\Viewshading-app` |
| **applicationId** | `com.viewshed.app` |
| **Module** | `:app` |
| **UI** | Views + ViewBinding + Material 3 + Maps / Elevation |
| **Debug APK** | `app\build\outputs\apk\debug\app-debug.apk` |
| **GitHub** | https://github.com/Strobingn/Viewshading-app |
| **version** | `1.5.0` (versionCode 8) — field tools phases 1–3 |
| **Field tools** | Offline elevation packs · field notes · voice memos · measure mode (see `ROADMAP.md`) |

## Android CLI (required)

Ensure `android` is on PATH (`%USERPROFILE%\.android\bin`) and SDK is:

`C:\Users\Austin\AppData\Local\Android\Sdk`

```powershell
android info
android describe --project_dir=.
```

### Build

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

### Deploy with Android CLI (after build)

```powershell
android run --apks=app\build\outputs\apk\debug\app-debug.apk
```

### Device / UI inspection

```powershell
adb devices -l
android layout --pretty
android screen capture --output=captures\viewshade.png
```

### Docs / skills

```powershell
android docs search "google maps android elevation api"
android skills list
# Views are XML today; Compose migration skill is available if needed:
# android skills add migrate-xml-views-to-jetpack-compose
```

Helper scripts:

- `scripts\android-cli-build.ps1` — assemble debug
- `scripts\android-cli-run.ps1` — build + `android run`
- `scripts\android-cli-describe.ps1` — refresh project metadata

## Maps / Elevation keys

- Put keys in **gitignored** `secrets.properties`:
  - `MAPS_API_KEY=` or `GOOGLE_MAPS_API_KEY=`
- Cloud Console package: `com.viewshed.app`
- Debug SHA-1 (shared with FieldOps debug keystore):  
  `23:A1:3A:DD:C1:18:8E:10:BE:92:8B:0A:87:D8:33:99:19:31:1B:44`
- See `docs/MAPS_SETUP.md` if present.

`local.properties` must point at the real SDK (not a stale `D:\Android\Sdk` path).

## Related app

**Wildlife FieldOps** lives at `D:\wildlife-fieldops` (`com.strobingn.wildlifefieldops`). Share Maps console practices / debug keystore; keep packages separate.

## Conventions for agents

1. Prefer Android CLI + `gradlew` over hard-coded SDK tool paths.
2. Never commit `secrets.properties`, `local.properties`, or keystores.
3. Keep demo/offline terrain path working when Maps key is missing.
4. Windows: `android emulator` CLI is disabled by Google; use a physical device or Studio AVD.
