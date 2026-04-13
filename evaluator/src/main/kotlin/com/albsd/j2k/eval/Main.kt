package com.albsd.j2k.eval

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: evaluator <converted-project-dir>")
        System.exit(1)
    }

    val projectDir = File(args[0])
    if (!projectDir.exists()) {
        System.err.println("ERROR: directory not found: ${projectDir.absolutePath}")
        System.exit(1)
    }

    println("[eval] Converted project : ${projectDir.absolutePath}")
    println()

    println("[eval] Running structural heuristics...")
    val heuristics = IdiomHeuristics.run(projectDir)
    val h = heuristics
    println("[eval] -----------------------------------------------")
    println("[eval] Files scanned    : ${h.files.size}")
    println("[eval] !! (forced !!)   : ${h.totalForcedNonNull}")
    println("[eval] var / val        : ${h.totalVar} / ${h.totalVal}  (val ratio: ${"%.0f".format(h.valRatio * 100)}%)")
    println("[eval] null-check if    : ${h.totalNullCheckIf}")
    println("[eval] string concat +  : ${h.totalStringConcat}")
    println("[eval] explicit get/set : ${h.totalExplicitGetSet}")
    println("[eval] semicolons left  : ${h.totalSemicolons}")
    println("[eval] for loops        : ${h.totalForLoop}  |  Collection ops (forEach/map/filter/...) : ${h.totalCollectionOps}")
    println("[eval] -----------------------------------------------")
    println()

    println("[eval] Running compilation check...")

    val result = CompilationCheck.run(projectDir)

    val displayErrors = result.perFileResults?.sumOf { it.errors.size } ?: result.errorCount
    val displayWarnings = result.perFileResults?.sumOf { r ->
        r.rawOutput.lines().count { it.startsWith("w:") }
    } ?: result.warningCount

    println("[eval] -----------------------------------------------")
    println("[eval] Compilation : ${if (result.success) "SUCCESS" else "FAILED"}")
    println("[eval] Errors      : $displayErrors${if (result.perFileResults != null) " (per-file)" else ""}")
    println("[eval] Warnings    : $displayWarnings${if (result.perFileResults != null) " (per-file)" else ""}")
    println("[eval] -----------------------------------------------")

    if (result.errors.isNotEmpty()) {
        println("[eval] Errors:")
        result.errors.forEach { err ->
            println("  ${err.file}:${err.line ?: "?"} : ${err.message}")
        }
        println()

    } else if (result.compilerCrash != null) {
        println("[eval] COMPILER CRASH: ${result.compilerCrash}")
        println()
        result.perFileResults?.let { perFile ->
            val passed = perFile.count { it.success }
            println("[eval] Per-file results ($passed/${perFile.size} passed):")
            perFile.forEach { r ->
                val status = when {
                    r.crash != null -> "CRASH"
                    r.success -> "OK"
                    else -> "FAIL"
                }
                println("[eval]   ${r.file.padEnd(40)} $status")
                if (!r.success) {
                    val compilerLines = r.rawOutput.lines()
                        .filter { it.startsWith("e:") || it.startsWith("w:") }
                    if (compilerLines.isNotEmpty()) {
                        compilerLines.forEach { println("[eval]     $it") }
                    } else {
                        // show tail of gradle output for config errors
                        r.rawOutput.lines()
                            .filter { it.isNotBlank() }
                            .takeLast(8)
                            .forEach { println("[eval]     $it") }
                    }
                }
            }
            println()
        }
    } else if (!result.success) {
        println("[eval] Raw compiler output:")
        println(result.rawOutput)
    }

    val QUOTED = Regex("""'[^']*'""")
    val allErrors = result.perFileResults?.flatMap { it.errors } ?: result.errors
    val uniqueErrors = allErrors
        .groupingBy { QUOTED.replace(it.message, "''") }
        .eachCount()
        .entries
        .sortedByDescending { it.value }

    if (uniqueErrors.isNotEmpty()) {
        println("[eval] Unique error types (${uniqueErrors.size}):")
        uniqueErrors.forEach { (msg, count) -> println("  x$count  $msg") }
        println()
    }

    val reportsDir = File("evaluations").also { it.mkdirs() }
    val reportFile = File(reportsDir, "evaluation-report-${projectDir.name}.json")
    reportFile.writeText(buildJson(projectDir.name, h, result, uniqueErrors, displayErrors, displayWarnings))
    println("[eval] Report written to ${reportFile.absolutePath}")

    System.exit(0)
}

private fun buildJson(
    project: String,
    h: AggregateHeuristicResult,
    c: CompilationResult,
    uniqueErrors: List<Map.Entry<String, Int>>,
    errorCount: Int,
    warningCount: Int,
): String {
    val errorsJson = c.errors.joinToString(",\n      ") { err ->
        """{"file":${jsonStr(err.file)},"line":${err.line ?: "null"},"message":${jsonStr(err.message)}}"""
    }
    val uniqueErrorsJson = uniqueErrors.joinToString(",\n      ") { (msg, count) ->
        """{"message":${jsonStr(msg)},"count":$count}"""
    }
    return """
{
  "project": ${jsonStr(project)},
  "heuristics": {
    "filesScanned": ${h.files.size},
    "forcedNonNull": ${h.totalForcedNonNull},
    "var": ${h.totalVar},
    "val": ${h.totalVal},
    "valRatioPct": ${"%.1f".format(h.valRatio * 100)},
    "nullCheckIf": ${h.totalNullCheckIf},
    "stringConcat": ${h.totalStringConcat},
    "explicitGetSet": ${h.totalExplicitGetSet},
    "semicolons": ${h.totalSemicolons},
    "forLoops": ${h.totalForLoop},
    "collectionOps": ${h.totalCollectionOps}
  },
  "compilation": {
    "success": ${c.success},
    "errorCount": $errorCount,
    "warningCount": $warningCount,
    "compilerCrash": ${if (c.compilerCrash != null) jsonStr(c.compilerCrash) else "null"},
    "perFileResults": ${if (c.perFileResults != null) perFileJson(c.perFileResults) else "null"},
    "uniqueErrors": [
      $uniqueErrorsJson
    ],
    "errors": [
      $errorsJson
    ]
  }
}
""".trimIndent()
}

private fun perFileJson(results: List<FileCompilationResult>): String {
    val entries = results.joinToString(",\n    ") { r ->
        """{"file":${jsonStr(r.file)},"success":${r.success},"crash":${if (r.crash != null) jsonStr(r.crash) else "null"},"errorCount":${r.errors.size}}"""
    }
    return "[\n    $entries\n  ]"
}

private fun jsonStr(s: String): String =
    "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
