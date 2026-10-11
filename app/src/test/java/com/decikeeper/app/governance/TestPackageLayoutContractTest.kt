package com.decikeeper.app.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestPackageLayoutContractTest {

    private val allowedTopLevelPackages = setOf(
        "manager",
        "ui",
        "protection",
        "data",
        "service",
        "questionbank",
        "oem",
        "governance",
        "testutil",
        "statistics",
        "logging"
    )

    private fun resolveTestRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, "app/src/test/java/com/decikeeper/app"),
            File(cwd, "src/test/java/com/decikeeper/app"),
            File(cwd.parentFile ?: cwd, "app/src/test/java/com/decikeeper/app")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate test root from ${cwd.absolutePath}")
    }

    @Test
    fun noFlatTestsInTestRoot() {
        val root = resolveTestRoot()
        val flatTestFiles = root.listFiles { file ->
            file.isFile && file.name.endsWith("Test.kt")
        }?.map { it.name } ?: emptyList()

        assertTrue(
            "app/src/test/java/com/decikeeper/app/ root must not contain flat *Test.kt files, found: $flatTestFiles",
            flatTestFiles.isEmpty()
        )
    }

    @Test
    fun onlyAllowedTopLevelDirectoriesExist() {
        val root = resolveTestRoot()
        val directSubDirs = root.listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.toSet() ?: emptySet()

        val unknownDirs = directSubDirs - allowedTopLevelPackages
        assertTrue(
            "Found unknown test directories: $unknownDirs. Allowed set is: $allowedTopLevelPackages",
            unknownDirs.isEmpty()
        )
    }
}
