plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs app, catalog, audit, and device validation unit tests."
    dependsOn(
        ":app:testDebugUnitTest",
        ":catalog-core:test",
        ":catalog-audit:test",
        ":device-validation:test",
    )
}
