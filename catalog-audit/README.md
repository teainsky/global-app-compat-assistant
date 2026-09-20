# catalog-audit

Development-only audit CLI for official microG GitHub release assets. It reads the built-in
catalog from `catalog-core`, matches each asset by its exact recorded filename, downloads only to
the root `build/catalog-audit` directory, and uses Android SDK `apkanalyzer` and `apksigner`.

Run from the repository root:

```powershell
$env:ANDROID_HOME = "$PWD\.toolchains\android-sdk"
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--release-tag v0.3.16.252432"
```

A failed audit exits non-zero after writing `audit-report.json` and `audit-report.txt`. The tool
does not install APKs and cannot change compatibility validation, recommendation, or installation
eligibility state.
