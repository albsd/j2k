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
    println("[eval] for loops        : ${h.totalForLoop}  |  Collection ops (forEach/map/filter/…) : ${h.totalCollectionOps}")
    println("[eval] -----------------------------------------------")
    println()

    println("[eval] Running compilation check...")

    val result = CompilationCheck.run(projectDir)

    println("[eval] -----------------------------------------------")
    println("[eval] Compilation : ${if (result.success) "SUCCESS" else "FAILED"}")
    println("[eval] Errors      : ${result.errorCount}")
    println("[eval] Warnings    : ${result.warningCount}")
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
    } else if (!result.success) {
        println("[eval] Raw compiler output:")
        println(result.rawOutput)
    }

    val QUOTED = Regex("""'[^']*'""")
    val uniqueErrors = result.errors
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
    reportFile.writeText(buildJson(projectDir.name, h, result, uniqueErrors))
    println("[eval] Report written to ${reportFile.absolutePath}")

    System.exit(0)
}

private fun buildJson(
    project: String,
    h: AggregateHeuristicResult,
    c: CompilationResult,
    uniqueErrors: List<Map.Entry<String, Int>>,
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
    "errorCount": ${c.errorCount},
    "warningCount": ${c.warningCount},
    "compilerCrash": ${if (c.compilerCrash != null) jsonStr(c.compilerCrash) else "null"},
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

private fun jsonStr(s: String): String =
    "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
