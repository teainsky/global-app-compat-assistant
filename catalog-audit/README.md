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
trusted catalog and ignores any promotion/source fields supplied inside input JSON. APK-byte
evidence is accepted through the host-audit import path or strict development-side review of
schema v3 embedded artifact evidence. The schema v3 importer independently requires both installed
and official SHA-256 values to match the trusted catalog and requires the exact reviewer-approved
baseline digest before assigning reviewed provenance. It never updates `DEVICE_VERIFIED` in the
catalog.

```powershell
$baselineDigest = (Get-FileHash device-baseline.json -Algorithm SHA256).Hash.ToLowerInvariant()
.\.toolchains\gradle-8.9\bin\gradle.bat :catalog-audit:run --args="--validation-evidence --baseline device-baseline.json --expected-baseline-sha256 $baselineDigest --output build/catalog-audit/device-validation-evidence.json"
```

For schema v2 baselines, add `--artifact-audit device-artifact-audit.json` to supply an exact host
audit. A schema v3 baseline can carry on-device artifact evidence, but only this development tool
can revalidate it against the trusted catalog and assign publication-eligible reviewed provenance.

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
