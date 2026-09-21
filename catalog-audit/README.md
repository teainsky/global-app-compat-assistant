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

## Device validation evidence

The evidence pipeline is local and read-only. It rechecks baseline component metadata against the
trusted catalog, ignores any promotion/source fields supplied inside input JSON, and only accepts
APK-byte evidence through the host-audit import path. It never updates `DEVICE_VERIFIED` in the
catalog.

```powershell
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--validation-evidence --baseline device-baseline.json --output build/catalog-audit/device-validation-evidence.json"
```

When a future host audit with an exact device/system profile is available, add
`--artifact-audit device-artifact-audit.json`. Without that input, a successful Pura 70 Pro+
functional baseline stops at `FUNCTIONALLY_VALIDATED` and explicitly reports the missing artifact
evidence.

## Verified device record review

Record generation requires a reviewer-approved SHA-256 of the exact evidence file. The pipeline
checks that digest, reruns the promotion policy, and independently matches the exact device/system
profile and both artifacts against the trusted catalog before writing an unsigned record:

```powershell
$digest = (Get-FileHash device-validation-evidence.json -Algorithm SHA256).Hash.ToLowerInvariant()
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--publish-device-record --evidence device-validation-evidence.json --expected-evidence-sha256 $digest --output build/catalog-audit/verified-device-record.json"
```

`verified-device-record.json` remains a development-side approval artifact. This command never
modifies, signs, or publishes the online compatibility catalog.
