# Third-party license boundary

The source code and documentation authored in this repository are made available under the
[Apache License 2.0](LICENSE).

## microG artifacts

This repository does not commit, embed, or redistribute the microG APK binaries. For the one
verified Huawei workflow, the app can prepare separately distributed artifacts only after the
signed catalog, exact device profile, official source, hash, package metadata, version, and signer
checks pass. Those artifacts are downloaded from the official `microg/GmsCore` GitHub release and
remain third-party works.

- microG Services / GmsCore source: Apache-2.0
- microG Companion (`vending-app`, formerly FakeStore) code: Apache-2.0
- microG documentation and artwork may carry separate licenses and are not included in this app

Project licensing does not grant rights to microG, Google, Huawei, Android, or other third-party
names and trademarks beyond accurate descriptive use. See the official
[GmsCore license](https://github.com/microg/GmsCore/blob/master/LICENSE) and
[GmsCore repository](https://github.com/microg/GmsCore).

## Direct build dependencies

The release APK uses AndroidX/Jetpack Compose (Apache-2.0), Kotlin coroutines (Apache-2.0), and
Gson (Apache-2.0). Their upstream license and attribution terms remain applicable. JUnit 4
(EPL-1.0) is used only by development tests and is not an app runtime component. Gradle and Android
build tools are development tooling and are not bundled as application code.

Before distributing a release artifact, dependency versions and their upstream notices should be
rechecked from the resolved release dependency graph.
