plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":catalog-core"))
    implementation(project(":device-validation"))
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.example.globalcompat.audit.CatalogAuditMainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("releaseInventory") {
    group = "verification"
    description = "Scans the latest 10 stable official microG GitHub releases."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args("--inventory")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("deviceAudit") {
    group = "verification"
    description = "Audits installed Huawei microG APK bytes through read-only ADB commands."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args("--device-audit")
    workingDir = rootProject.projectDir
}
