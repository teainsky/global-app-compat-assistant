package com.example.globalcompat.catalog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

class RuntimeCatalogBoundaryTest {
    @Test
    fun `runtime source cannot read built-in catalog outside repository`() {
        val root = projectRoot()
        val allowed = setOf(
            root.resolve("catalog-core/src/main/kotlin/com/example/globalcompat/catalog/BuiltInComponentCatalog.kt"),
            root.resolve("catalog-core/src/main/kotlin/com/example/globalcompat/catalog/TrustedCatalogRepository.kt"),
        ).map(Path::normalize).toSet()
        val violations = listOf("app", "catalog-core", "catalog-audit", "device-validation")
            .flatMap { module ->
                val sourceRoot = root.resolve("$module/src/main")
                if (!Files.exists(sourceRoot)) return@flatMap emptyList()
                Files.walk(sourceRoot).use { paths ->
                    paths.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                        .filter { path ->
                            path.normalize() !in allowed &&
                                Files.newBufferedReader(path).use { reader ->
                                    reader.readText().contains("BuiltInComponentCatalog")
                                }
                        }
                        .map { root.relativize(it).toString() }
                        .toList()
                }
            }

        assertTrue("Illegal built-in catalog reads: $violations", violations.isEmpty())
    }

    private fun projectRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        while (!Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = checkNotNull(cursor.parent) { "Project root not found" }
        }
        return cursor
    }
}
