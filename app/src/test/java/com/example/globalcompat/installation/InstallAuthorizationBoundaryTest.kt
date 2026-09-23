package com.example.globalcompat.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

class InstallAuthorizationBoundaryTest {
    @Test
    fun `runtime issuer is only constructed by installation gate`() {
        val root = projectRoot()
        val definition = root.resolve(
            "app/src/main/java/com/example/globalcompat/installation/InstallAuthorization.kt",
        ).normalize()
        val gate = root.resolve(
            "app/src/main/java/com/example/globalcompat/installation/InstallationExecutionGate.kt",
        ).normalize()
        val issuerReferences = Files.walk(root.resolve("app/src/main")).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .filter { path ->
                    Files.newBufferedReader(path).use { reader ->
                        reader.readText().contains("InstallAuthorizationIssuer(")
                    }
                }
                .map(Path::normalize)
                .toList()
                .toSet()
        }

        assertEquals(setOf(definition, gate), issuerReferences)
        val mainActivity = Files.newBufferedReader(
            root.resolve("app/src/main/java/com/example/globalcompat/MainActivity.kt"),
        ).use { it.readText() }
        assertFalse(mainActivity.contains("InstallAuthorization"))
    }

    private fun projectRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        while (!Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = checkNotNull(cursor.parent) { "Project root not found" }
        }
        return cursor
    }
}
