package com.albsd.j2k.eval

import java.io.File
import java.nio.file.Files

data class CompilationResult(
    val success: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val errors: List<CompilationError>,
    val compilerCrash: String?,
    val perFileResults: List<FileCompilationResult>?,  // non-null only when full compilation crashed
    val rawOutput: String,
)

data class CompilationError(
    val file: String,
    val line: Int?,
    val message: String,
)

data class FileCompilationResult(
    val file: String,
    val success: Boolean,
    val crash: String?,
    val errors: List<CompilationError>,
    val rawOutput: String,
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

        val perFileResults = if (compilerCrash != null) {
            println("[eval] Compiler crashed - falling back to per-file compilation...")
            compilePerFile(convertedProjectDir, gradlew)
        } else null

        return CompilationResult(
            success = exitCode == 0,
            errorCount = errors.size,
            warningCount = warningCount,
            errors = errors,
            compilerCrash = compilerCrash,
            perFileResults = perFileResults,
            rawOutput = output,
        )
    }

    private fun compilePerFile(projectDir: File, gradlew: File): List<FileCompilationResult> {
        val ktFiles = projectDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .toList()

        println("[eval] Compiling ${ktFiles.size} file(s) individually...")
        return ktFiles.map { file ->
            val result = compileSingleFile(projectDir, gradlew, file)
            val status = when {
                result.crash != null -> "CRASH"
                result.success -> "OK"
                else -> "FAIL (${result.errors.size} error(s))"
            }
            println("[eval]   ${file.name} - $status")
            result
        }
    }

    private fun compileSingleFile(projectDir: File, gradlew: File, ktFile: File): FileCompilationResult {
        val tempDir = Files.createTempDirectory("j2k-eval-").toFile()
        try {
            // Copy gradle wrapper (script + small jar - distribution itself stays cached)
            val wrapperDir = File(tempDir, "gradle/wrapper").also { it.mkdirs() }
            File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
                .copyTo(File(wrapperDir, "gradle-wrapper.jar"), overwrite = true)
            File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
                .copyTo(File(wrapperDir, "gradle-wrapper.properties"), overwrite = true)
            gradlew.copyTo(File(tempDir, gradlew.name), overwrite = true)
                .setExecutable(true)

            // Copy source file into src/
            val srcDir = File(tempDir, "src").also { it.mkdirs() }
            ktFile.copyTo(File(srcDir, ktFile.name), overwrite = true)

            // Minimal settings + build
            File(tempDir, "settings.gradle").writeText("rootProject.name = 'j2k-file-check'\n")
            File(tempDir, "build.gradle").writeText("""
plugins {
  id 'java'
  id 'org.jetbrains.kotlin.jvm' version '2.1.20'
}
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
sourceSets {
    main { kotlin { srcDirs = ['src'] } }
}
dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-stdlib'
}
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion
}
""".trimIndent())

            val process = ProcessBuilder(
                File(tempDir, gradlew.name).absolutePath,
                "compileKotlin", "--no-daemon"
            )
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            return FileCompilationResult(
                file = ktFile.name,
                success = exitCode == 0,
                crash = detectCrash(output),
                errors = parseErrors(output),
                rawOutput = output,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // kotlin 2.1.20 requires gradle 7.6.3+. bump wrapper if older
    private fun ensureGradleCompatibility(projectDir: File) {
        val props = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!props.exists()) return
        val text = props.readText()
        val match = Regex("""distributionUrl=.*gradle-(\d+)\.(\d+)""").find(text) ?: return
        val major = match.groupValues[1].toIntOrNull() ?: return
        val minor = match.groupValues[2].toIntOrNull() ?: return
        if (major < 7 || (major == 7 && minor < 6)) {
            println("[eval] Gradle ${major}.${minor} too old for Kotlin 2.1.20 - bumping wrapper to 7.6.3")
            props.writeText(
                text.replace(
                    Regex("""distributionUrl=.*\n"""),
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.3-bin.zip\n"
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

    private fun hasTask(projectDir: File, gradlew: File, taskName: String): Boolean {
        val output = ProcessBuilder(gradlew.absolutePath, "tasks", "--all", "--no-daemon", "--quiet")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()
        return output.lines().any { it.startsWith(taskName) }
    }

    // Matches "e: some.Exception: message" - compiler internal crash, no file/line
    private val CRASH_PATTERN = Regex("""(?m)^e: (\w[\w.]+Exception[^\n]*)""")

    private fun detectCrash(output: String): String? =
        CRASH_PATTERN.find(output)?.groupValues?.get(1)?.trim()

    // Kotlin 2.x: "e: file:///path.kt:10:5: message"
    // Kotlin 1.x: "e: /path.kt: (10, 5): message"
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
