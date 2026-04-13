package com.albsd.j2k.eval

import java.io.File

data class FileHeuristicResult(
    val file: String,
    val forcedNonNull: Int,        // !! count
    val varCount: Int,             // var declarations
    val valCount: Int,             // val declarations
    val nullCheckIf: Int,          // if (x != null) / if (x == null)
    val stringConcat: Int,         // string + "..." or "..." + expr
    val explicitGetSet: Int,       // .get( / .set( java-style calls
    val semicolons: Int,           // remaining semicolons
    val forLoop: Int,              // for (x in ...) loops
    val forEach: Int,              // .forEach / .map / .filter etc.
)

data class AggregateHeuristicResult(
    val files: List<FileHeuristicResult>,
    val totalForcedNonNull: Int,
    val totalVar: Int,
    val totalVal: Int,
    val valRatio: Double,          // val / (val + var)
    val totalNullCheckIf: Int,
    val totalStringConcat: Int,
    val totalExplicitGetSet: Int,
    val totalSemicolons: Int,
    val totalForLoop: Int,
    val totalForEach: Int,
)

object IdiomHeuristics {

    // Matches !! not inside a string literal (best-effort line-level)
    private val FORCED_NON_NULL  = Regex("""!!""")
    private val VAR_DECL         = Regex("""\bvar\s+\w+""")
    private val VAL_DECL         = Regex("""\bval\s+\w+""")
    private val NULL_CHECK_IF    = Regex("""\bif\s*\(\s*\w[\w.]*\s*[!=]=\s*null""")
    // string literal + expr  or  expr + string literal
    private val STRING_CONCAT    = Regex(""""[^"]*"\s*\+|\+\s*"[^"]*"""")
    private val EXPLICIT_GET_SET = Regex("""\.(get|set)\s*\(""")
    private val SEMICOLON        = Regex(""";""")
    private val FOR_LOOP         = Regex("""\bfor\s*\(""")
    private val FOR_EACH_HOF     = Regex("""\.(forEach|map|filter|flatMap|any|all|none|find|count|reduce|fold|groupBy|sortedBy|sortedWith|associate|partition)\s*[({]""")

    fun run(convertedProjectDir: File): AggregateHeuristicResult {
        val ktFiles = convertedProjectDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val results = ktFiles.map { analyse(it, convertedProjectDir) }
        return aggregate(results)
    }

    private fun analyse(file: File, base: File): FileHeuristicResult {
        val lines = file.readLines()
        // Strip single-line string literals to avoid false positives inside strings
        val stripped = lines.map { stripStrings(it) }

        return FileHeuristicResult(
            file              = file.relativeTo(base).path,
            forcedNonNull     = stripped.sumOf { FORCED_NON_NULL.findAll(it).count() },
            varCount          = stripped.sumOf { VAR_DECL.findAll(it).count() },
            valCount          = stripped.sumOf { VAL_DECL.findAll(it).count() },
            nullCheckIf       = stripped.sumOf { NULL_CHECK_IF.findAll(it).count() },
            stringConcat      = stripped.sumOf { STRING_CONCAT.findAll(it).count() },
            explicitGetSet    = stripped.sumOf { EXPLICIT_GET_SET.findAll(it).count() },
            semicolons        = stripped.sumOf { SEMICOLON.findAll(it).count() },
            forLoop           = stripped.sumOf { FOR_LOOP.findAll(it).count() },
            forEach           = stripped.sumOf { FOR_EACH_HOF.findAll(it).count() },
        )
    }

    // Replace string literal contents with spaces so patterns inside strings don't fire
    private fun stripStrings(line: String): String =
        line.replace(Regex(""""([^"\\]|\\.)*""""), "\"\"")

    private fun aggregate(files: List<FileHeuristicResult>): AggregateHeuristicResult {
        val totalVar = files.sumOf { it.varCount }
        val totalVal = files.sumOf { it.valCount }
        val total = totalVar + totalVal
        return AggregateHeuristicResult(
            files               = files,
            totalForcedNonNull  = files.sumOf { it.forcedNonNull },
            totalVar            = totalVar,
            totalVal            = totalVal,
            valRatio            = if (total == 0) 1.0 else totalVal.toDouble() / total,
            totalNullCheckIf    = files.sumOf { it.nullCheckIf },
            totalStringConcat   = files.sumOf { it.stringConcat },
            totalExplicitGetSet = files.sumOf { it.explicitGetSet },
            totalSemicolons     = files.sumOf { it.semicolons },
            totalForLoop        = files.sumOf { it.forLoop },
            totalForEach        = files.sumOf { it.forEach },
        )
    }
}
