package com.albsd.j2k.eval

import java.io.File

data class CompilationResult(
    val success: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val errors: List<CompilationError>,
    val rawOutput: String,
)

data class CompilationError(
    val file: String,
    val line: Int?,
    val message: String,
)

object CompilationCheck {

    fun run(convertedProjectDir: File): CompilationResult {
        val buildGradle = File(convertedProjectDir, "build.gradle")
        require(buildGradle.exists()) {
            "build.gradle not found in ${convertedProjectDir.absolutePath}"
        }

        patchBuildGradle(buildGradle)

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlew = File(convertedProjectDir, if (isWindows) "gradlew.bat" else "gradlew")
        gradlew.setExecutable(true)

        println("[eval] Patched build.gradle, running build (skipping tests)...")

        val process = ProcessBuilder(
            gradlew.absolutePath,
            "compileKotlin",
            "--no-daemon",
        )
            .directory(convertedProjectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val errors = parseErrors(output)
        val warningCount = output.lines().count { ": warning:" in it }

        return CompilationResult(
            success = exitCode == 0,
            errorCount = errors.size,
            warningCount = warningCount,
            errors = errors,
            rawOutput = output,
        )
    }

    // inject kotlin jvm plugin
    private fun patchBuildGradle(buildGradle: File) {
        val original = buildGradle.readText()
        if ("j2k-eval-patch" in original) return

        val patched = original.replace(
            "id 'java'",
            "id 'java'\n  id 'org.jetbrains.kotlin.jvm' version '2.1.20'",
        ) + """

// ---- j2k-eval-patch ----
sourceSets {
    main {
        kotlin { srcDirs = ['src/main/java'] }
    }
    test {
        kotlin { srcDirs = ['src/test/java'] }
    }
}

dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-stdlib'
}
// ---- end j2k-eval-patch ----
"""
        buildGradle.writeText(patched)
    }

    private val ERROR_PATTERN = Regex("""(?m)^e: file:///(.+\.kt):(\d+):(\d+) (.+)""")

    private fun parseErrors(output: String): List<CompilationError> {
        return ERROR_PATTERN.findAll(output).map {
            CompilationError(
                file = it.groupValues[1],
                line = it.groupValues[2].toIntOrNull(),
                message = it.groupValues[4],
            )
        }.toList()
    }
}
