import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFieldNames = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
val localSigningPropertiesFile = rootProject.file("local-signing.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningValues = releaseSigningFieldNames.associateWith { fieldName ->
    providers.environmentVariable(fieldName).orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: localSigningProperties.getProperty(fieldName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
}
val configuredReleaseSigningFields = releaseSigningValues.filterValues { it != null }.keys
check(configuredReleaseSigningFields.isEmpty() ||
    configuredReleaseSigningFields.size == releaseSigningFieldNames.size
) {
    "Release signing configuration is incomplete. Configure all four RELEASE_* fields or none."
}
val releaseSigningConfigured = configuredReleaseSigningFields.size == releaseSigningFieldNames.size
val githubReleaseApiUrl = providers.environmentVariable("GITHUB_RELEASE_API_URL")
    .orElse(providers.gradleProperty("GITHUB_RELEASE_API_URL"))
    .orElse("")
    .get()
val escapedGithubReleaseApiUrl = githubReleaseApiUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val releaseStoreFile = releaseSigningValues["RELEASE_STORE_FILE"]
    ?.let(rootProject::file)
if (releaseSigningConfigured) {
    check(releaseStoreFile?.isFile == true) {
        "RELEASE_STORE_FILE must point to an existing local keystore outside version control."
    }
}

android {
    namespace = "com.example.globalcompat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.globalcompat"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-rc6"

        buildConfigField("String", "GITHUB_RELEASE_API_URL", "\"$escapedGithubReleaseApiUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("externalRelease") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("externalRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val verifyReleaseSigningHygiene by tasks.registering {
    group = "verification"
    description = "Fails if tracked signing secrets or debug release signing are detected."

    doLast {
        check(android.buildTypes.getByName("release").signingConfig?.name != "debug") {
            "Release builds must never use the debug signing key."
        }

        val git = ProcessBuilder("git", "ls-files", "-z")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = git.inputStream.readBytes().toString(Charsets.UTF_8)
        check(git.waitFor() == 0) {
            "Unable to inspect Git tracked files for release signing hygiene."
        }
        val trackedPaths = output.split('\u0000').filter(String::isNotBlank)
        val forbiddenNames = setOf(
            "keystore.properties",
            "signing.properties",
            "local-signing.properties",
        )
        val trackedKeyMaterial = trackedPaths.filter { path ->
            val normalized = path.replace('\\', '/')
            val fileName = normalized.substringAfterLast('/')
            fileName in forbiddenNames ||
                fileName.endsWith(".jks", ignoreCase = true) ||
                fileName.endsWith(".keystore", ignoreCase = true)
        }
        check(trackedKeyMaterial.isEmpty()) {
            "Release key material must not be tracked: ${trackedKeyMaterial.joinToString()}"
        }

        val textExtensions = setOf(
            "bat", "gradle", "json", "kt", "kts", "md", "properties", "ps1", "sh",
            "toml", "txt", "xml", "yaml", "yml",
        )
        val propertyPassword = Regex(
            "(?im)^\\s*(RELEASE_STORE_PASSWORD|RELEASE_KEY_PASSWORD)\\s*[=:]\\s*(\\S.+)$",
        )
        val literalGradlePassword = Regex(
            "(?i)\\b(storePassword|keyPassword)\\s*=\\s*[\\\"'][^\\\"']+[\\\"']",
        )
        val secretViolations = trackedPaths.mapNotNull { path ->
            val file = rootProject.file(path)
            if (!file.isFile || file.extension.lowercase() !in textExtensions) return@mapNotNull null
            val text = file.readText()
            val hardCodedPropertyPassword = propertyPassword.findAll(text).any { match ->
                val value = match.groupValues[2].trim().trim('"', '\'')
                !value.startsWith("\${") &&
                    !value.startsWith("\$env:", ignoreCase = true) &&
                    !value.startsWith("<")
            }
            if (hardCodedPropertyPassword || literalGradlePassword.containsMatchIn(text)) {
                path
            } else {
                null
            }
        }
        check(secretViolations.isEmpty()) {
            "Possible hard-coded release signing password in: ${secretViolations.joinToString()}"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigningHygiene)
}

dependencies {
    implementation(project(":catalog-core"))
    implementation(project(":device-validation"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
