# catalog-audit

Development-only audit CLI for official microG artifacts. It reads descriptors from `catalog-core`
and treats GitHub Release API asset metadata as the binary fact source for GitHub releases.
Release-note filenames are auxiliary evidence only; disagreements produce
`RELEASE_NOTES_ASSET_MISMATCH` without hiding a unique valid API asset. Downloads use the exact
cataloged asset ID, the system `curl`, at most three attempts, and `.part` files under the root
`build/catalog-audit` directory. APK binaries are removed after Android SDK `apkanalyzer` and
`apksigner` finish.

Run from the repository root:

```powershell
$env:ANDROID_HOME = "$PWD\.toolchains\android-sdk"
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--release-tag v0.3.16.252432"
```

A successful audit writes `audit-report.json`, `audit-report.txt`, and `audited-manifest.json`.
A failed audit exits non-zero without leaving a stale audited manifest. The tool does not install
APKs and cannot change compatibility validation, recommendation, or installation eligibility
state.
