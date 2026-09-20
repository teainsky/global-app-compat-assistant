plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":catalog-core"))
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.example.globalcompat.audit.CatalogAuditMainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
