# Build from source

## Windows desktop

Use Windows x64, Node.js 24 LTS with npm, PowerShell, and Git. No globally installed Codex binary is replaced.

From the repository root:

```powershell
.\scripts\run.ps1 install
.\scripts\setup-codex.ps1
.\scripts\setup-speech.ps1
.\scripts\package-desktop.ps1
.\scripts\install-desktop.ps1
```

If Node 24 is not on PATH, set `CODEX_VOICE_NODE_PATH` to its `node.exe`. The packaging script prints the desktop EXE path; the installer copies it to the current user's local Programs folder and creates a desktop shortcut. Quit Codex Voice before replacing an installed build.

The package includes pinned Codex 0.160.0, Node, whisper.cpp, the English model, production dependencies and dependency notices. Package outputs, models and caches are local ignored files. It does not include credentials or task history. Third-party downloads require network access and their own license terms.

For development, run `.\scripts\run.ps1 build` and start Electron with `node_modules\electron\dist\electron.exe desktop\main.cjs`. A Node 24 runtime must be available. The terminal bridge remains available through `.\scripts\run.ps1 start --mock` (explicitly simulated) or `--real` (signed-in Codex).

## Android

Install Android Studio with JDK 17+ and SDK 35. Open `android/` and let the project resolve its pinned dependencies. Keep `android/local.properties` local; it must not be committed.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\android\gradlew.bat -p android :app:assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Every build is generic and does not embed `google-services.json`, even if a local ignored copy exists. Import your own Firebase client in Android Settings after installation if you want push alerts. Do not publish signing keys, service accounts, personal app-data backups or test APKs.

To update an authorized USB-connected phone without clearing its app data:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Debug packages are for local installation. A production release requires your own protected signing key and distribution process; no signing material is included here.

## Validation commands

These commands are available to contributors; a successful build alone does not verify runtime behavior.

```powershell
.\scripts\run.ps1 test
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:lintDebug
.\android\gradlew.bat -p android :app:assembleDebugAndroidTest
```

Instrumentation suites require deliberately configured devices/fixtures. Report bridge regression, Android unit tests, instrumentation and actual call/device checks separately. Never claim a simulated demo verifies remote calling. Do not run real inference or device fixtures against someone else's project or credential store.
