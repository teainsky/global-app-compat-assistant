# catalog-audit

Development-only audit CLI for official microG artifacts. It reads descriptors from `catalog-core`
and records availability independently for the microG GitHub API, the official microG download
page, and Huawei AppGallery. Release-note declarations are metadata only; only an exact official
binary record can continue to download and APK inspection. Downloads stay under the root
`build/catalog-audit` directory, using Android SDK `apkanalyzer` and `apksigner`.

Run from the repository root:

```powershell
$env:ANDROID_HOME = "$PWD\.toolchains\android-sdk"
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--release-tag v0.3.16.252432"
```

A failed audit exits non-zero after writing `audit-report.json` and `audit-report.txt`. The tool
does not install APKs and cannot change compatibility validation, recommendation, or installation
eligibility state.
