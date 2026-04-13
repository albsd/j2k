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

    // --- Idiom heuristics (no build required) ---
    println("[eval] Running idiom heuristics...")
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
    println("[eval] for loops        : ${h.totalForLoop}  |  HOF (forEach/map/…) : ${h.totalForEach}")
    println("[eval] -----------------------------------------------")
    println()

    // --- Compilation check ---
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

        val QUOTED = Regex("""'[^']*'""")
        val uniqueErrors = result.errors
            .groupingBy { QUOTED.replace(it.message, "''") }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
        println("[eval] Unique error types (${uniqueErrors.size}):")
        uniqueErrors.forEach { (msg, count) ->
            println("  x$count  $msg")
        }
        println()
    } else if (!result.success) {
        // No structured errors parsed — fall back to raw output so nothing is silently swallowed
        println("[eval] Raw compiler output:")
        println(result.rawOutput)
    }

    // Evaluator always exits 0 — findings are informational, not a pipeline gate
    System.exit(0)
}
