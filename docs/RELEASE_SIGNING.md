# Android Release signing

Release signing is optional at configuration time and never falls back to the Android debug key.
Without a complete external configuration, `assembleRelease` intentionally produces an unsigned
APK. With all four fields configured, Gradle uses the external release keystore.

## Supported local configuration

Environment variables have priority. A Git-ignored file named `local-signing.properties` in the
repository root is the fallback. Both mechanisms use exactly these fields:

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Configure all four fields or none. Partial configuration fails during Gradle configuration. Use an
absolute keystore path outside the repository. Do not print passwords in build logs, shell history,
README files, Issue reports, or CI output.

## Generate the production keystore locally

Only the project owner should do this, on a trusted computer. The command deliberately omits
password arguments so the JDK tool prompts locally:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore "<absolute-path-outside-repository>\global-app-compat-assistant-release.jks" `
  -alias "<release-key-alias>" `
  -keyalg RSA -keysize 4096 -validity 10000
```

After generation, place the four values in protected environment variables or the ignored local
properties file. Never commit the keystore or properties file. Codex and other automation should
not receive, generate, or retain the passwords.

## GitHub update endpoint

Release builds read the non-secret `GITHUB_RELEASE_API_URL` environment variable (or Gradle
property) at build time. Set it to the official repository endpoint in this exact form:

```text
https://api.github.com/repos/<owner>/<repository>/releases/latest
```

If it is not configured, the app keeps all device detection features available and reports the
update check as temporarily unavailable. The runtime rejects non-HTTPS, non-`api.github.com`, or
non-`releases/latest` metadata endpoints.

## Verification

Build and verify locally with the Android SDK tools:

```powershell
.\.toolchains\gradle-8.9\bin\gradle.bat verifyReleaseSigningHygiene assembleRelease
.\.toolchains\android-sdk\build-tools\35.0.0\apksigner.bat verify --verbose --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

The repository safety task rejects tracked `.jks`, `.keystore`, signing properties, obvious
hard-coded signing passwords, and any release build configured with the debug signing key.

## Backup and continuity

Keep multiple encrypted, access-controlled backups of the production keystore and recovery
instructions. Losing the production keystore or its passwords may prevent future APK releases from
upgrading installations signed by that key. Do not rotate or replace it without a documented
Android signing migration plan.
