package com.albsd.j2k.eval

import java.io.File

data class CompilationResult(
    val success: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val errors: List<CompilationError>,
    val compilerCrash: String?,
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

        ensureSettingsFile(convertedProjectDir)
        ensureGradleCompatibility(convertedProjectDir)
        patchBuildGradle(buildGradle)

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlew = File(convertedProjectDir, if (isWindows) "gradlew.bat" else "gradlew")
        gradlew.setExecutable(true)

        println("[eval] Patched build.gradle, running build (skipping tests)...")

        val excludes = buildList {
            if (hasTask(convertedProjectDir, gradlew, "generateJava")) add("generateJava")
        }.flatMap { listOf("-x", it) }

        val process = ProcessBuilder(
            buildList {
                add(gradlew.absolutePath)
                add("compileKotlin")
                add("--no-daemon")
                addAll(excludes)
            }
        )
            .directory(convertedProjectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val errors = parseErrors(output)
        val warningCount = output.lines().count { it.startsWith("w: ") }
        val compilerCrash = detectCrash(output)

        return CompilationResult(
            success = exitCode == 0,
            errorCount = errors.size,
            warningCount = warningCount,
            errors = errors,
            compilerCrash = compilerCrash,
            rawOutput = output,
        )
    }

    // kotlin 2.1.20 requires gradle 7.6.3+. bump wrapper if older
    private fun ensureGradleCompatibility(projectDir: File) {
        val props = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!props.exists()) return
        val text = props.readText()
        val match = Regex("""distributionUrl=.*gradle-(\d+)\.(\d+)""").find(text) ?: return
        val major = match.groupValues[1].toIntOrNull() ?: return
        val minor = match.groupValues[2].toIntOrNull() ?: return
        // minimum compatible version with kotlin 2.1.20 is 7.6.3
        if (major < 7 || (major == 7 && minor < 6)) {
            println("[eval] Gradle ${major}.${minor} too old for Kotlin 2.1.20 — bumping wrapper to 8.5")
            props.writeText(
                text.replace(
                    Regex("""distributionUrl=.*\n"""),
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
                )
            )
        }
    }

    private fun ensureSettingsFile(projectDir: File) {
        val exists = listOf("settings.gradle", "settings.gradle.kts").any { File(projectDir, it).exists() }
        if (!exists) {
            File(projectDir, "settings.gradle").writeText("rootProject.name = '${projectDir.name}'\n")
        }
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

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion
}
// ---- end j2k-eval-patch ----
"""
        buildGradle.writeText(patched)
    }

    // Kotlin 2.x: "e: file:///path.kt:10:5: message"
    // Kotlin 1.x: "e: /path.kt: (10, 5): message"
    private fun hasTask(projectDir: File, gradlew: File, taskName: String): Boolean {
        val output = ProcessBuilder(gradlew.absolutePath, "tasks", "--all", "--no-daemon", "--quiet")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()
        return output.lines().any { it.startsWith(taskName) }
    }

    // Matches "e: some.Exception: message" — compiler internal crash, no file/line
    private val CRASH_PATTERN = Regex("""(?m)^e: (\w[\w.]+Exception[^\n]*)""")

    private fun detectCrash(output: String): String? =
        CRASH_PATTERN.find(output)?.groupValues?.get(1)?.trim()

    private val ERROR_PATTERN = Regex("""(?m)^e: (?:file://)?/?(.+\.kt)(?::(\d+):(\d+):?|: \((\d+), \d+\):) (.+)""")

    private fun parseErrors(output: String): List<CompilationError> {
        return ERROR_PATTERN.findAll(output).map {
            CompilationError(
                file = it.groupValues[1],
                line = (it.groupValues[2].ifEmpty { it.groupValues[4] }).toIntOrNull(),
                message = it.groupValues[5],
            )
        }.toList()
    }
}
