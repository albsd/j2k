package com.albsd.j2k

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlinx.coroutines.delay
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.j2k.J2kConverterExtension
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.psi.PsiFileFactory
import java.io.File

class J2kActivity : ProjectActivity {

    companion object {
        const val SOURCE_DIR_PROP = "j2k.sourceDir"
        const val OUTPUT_DIR_PROP = "j2k.outputDir"
        const val PROJECT_DIR_PROP = "j2k.projectDir"
        const val KOTLIN_ONLY_FLAG = "j2k.convertedKtOnly"
    }

    override suspend fun execute(project: Project) {
        println("[j2k] Starting plugin..")

        val sourceDirPaths = System.getProperty(SOURCE_DIR_PROP)
        val outputDirPath = System.getProperty(OUTPUT_DIR_PROP) ?: "converted-kotlin"
        val projectDir = System.getProperty(PROJECT_DIR_PROP)?.let { File(it) }
        val convertedKtOnly = System.getProperty(KOTLIN_ONLY_FLAG)?.toBoolean() ?: false

        if (sourceDirPaths == null) {
            System.err.println("[j2k] ERROR: -D$SOURCE_DIR_PROP not set — aborting.")
            exit(1)
            return
        }

        val sourceDirs = sourceDirPaths.split(",").map { File(it.trim()) }
        val outputDir = File(outputDirPath)

        for (sourceDir in sourceDirs) {
            if (!sourceDir.exists()) {
                System.err.println("[j2k] ERROR: source dir not found: ${sourceDir.absolutePath}")
                exit(1)
                return
            }
        }

        println("[j2k] Sources     : $sourceDirPaths")
        println("[j2k] Output      : $outputDirPath")
        println("[j2k] Project dir : ${projectDir?.absolutePath ?: "(none)"}")
        println("[j2k] Converted Kotlin only : $convertedKtOnly")

        // copy full project into outputDir/{project} first then read original sourceDir
        // we write kotlin files on the copy and then delete their respective java versions
        val destProjectDir: File? = if (!convertedKtOnly && projectDir != null) {
            val dest = File(outputDir, projectDir.name)
            println("[j2k] Copying project to ${dest.absolutePath}")
            projectDir.copyRecursively(dest, overwrite = true)
            println("[j2k] Copy complete")
            dest
        } else {
            null
        }

        val outputRoot = destProjectDir ?: projectDir?.let { File(outputDir, it.name) } ?: outputDir
        val relativeBase = projectDir ?: sourceDirs.first()

        val dumbService = DumbService.getInstance(project)
        while (dumbService.isDumb) {
            delay(200)
        }

        try {
            val totals = Stats()
            for (sourceDir in sourceDirs) {
                println("[j2k] --- Processing: ${sourceDir.absolutePath}")
                val stats = convert(project, sourceDir, outputRoot, relativeBase, destProjectDir)
                totals.converted += stats.converted
                totals.skipped += stats.skipped
                totals.failed += stats.failed
            }
            println("[j2k] -------------------------------------")
            println("[j2k] Converted : ${totals.converted}")
            println("[j2k] Skipped   : ${totals.skipped}")
            println("[j2k] Failed    : ${totals.failed}")
            println("[j2k] -------------------------------------")
            exit(0)
        } catch (e: Exception) {
            System.err.println("[j2k] FATAL: ${e.message}")
            e.printStackTrace()
            exit(1)
        }
    }

    private data class Stats(
        var converted: Int = 0,
        var skipped: Int = 0,
        var failed: Int = 0
    )

    private fun convert(
        project: Project,
        sourceDir: File,
        outputRoot: File,
        relativeBase: File,
        copyRoot: File?,
    ): Stats {
        outputRoot.mkdirs()

        val javaFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()

        println("[j2k] Found ${javaFiles.size} Java file(s) under ${sourceDir.absolutePath}")

        val psiManager = PsiManager.getInstance(project)

        LocalFileSystem.getInstance().refresh(true)

        J2kConverterExtension.EP_NAME.extensionList.forEachIndexed { i, ext ->
            println("[j2k] Available extension[$i]: ${ext::class.qualifiedName}")
        }

        val extension = J2kConverterExtension.EP_NAME.extensionList.first()
        println("[j2k] Using extension : ${extension::class.qualifiedName}")

        val converterSettings = ConverterSettings(
            forceNotNullTypes = false,
            specifyLocalVariableTypeByDefault = true,
            specifyFieldTypeByDefault = true,
            openByDefault = true,
            publicByDefault = true
        )

        val converter = extension.createJavaToKotlinConverter(
            project = project,
            targetModule = null,
            settings = converterSettings
        )
        println("[j2k] Using converter  : ${converter::class.qualifiedName}")

        val fileFactory = PsiFileFactory.getInstance(project)
        val stats = Stats()
        for (javaFile in javaFiles) {
            val virtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(javaFile.absolutePath)

            if (virtualFile == null) {
                System.err.println("[j2k] SKIP (vfs not found): ${javaFile.name}")
                stats.skipped++
                continue
            }

            val psiFile = DumbService.getInstance(project).runReadActionInSmartMode<PsiJavaFile?> {
                psiManager.findFile(virtualFile) as? PsiJavaFile
            }

            if (psiFile == null) {
                System.err.println("[j2k] SKIP (not a PsiJavaFile): ${javaFile.name}")
                stats.skipped++
                continue
            }

            // plugins/kotlin/j2k/k1.new.post-processing/src/org/jetbrains/kotlin/idea/j2k/post/processing/NewJ2kConverterExtension.kt
            // im actually using the old plugin because i couldnt manage to get the new one working
            val kotlinSource = try {
                DumbService.getInstance(project).runReadActionInSmartMode<String?> {
                    converter.elementsToKotlin(listOf(psiFile))
                        .results
                        .firstOrNull()
                        ?.text
                }
            } catch (e: Exception) {
                System.err.println("[j2k] ERROR converting ${javaFile.name}: ${e.message}")
                null
            }

            if (kotlinSource == null) {
                stats.failed++
                continue
            }

            val formattedText = WriteCommandAction.runWriteCommandAction<String>(project) {
                val ktFile = fileFactory.createFileFromText(
                    "temp.kt",
                    KotlinLanguage.INSTANCE,
                    kotlinSource
                ) as KtFile


                CodeStyleManager.getInstance(project).reformat(ktFile)
                ktFile.text
            }

            val relativePath = javaFile.relativeTo(relativeBase).invariantSeparatorsPath
                .removeSuffix(".java") + ".kt"
            val outFile = File(outputRoot, relativePath)

            outFile.parentFile.mkdirs()
            outFile.writeText(formattedText)

            if (copyRoot != null) {
                File(copyRoot, javaFile.relativeTo(relativeBase).path).delete()
            }

            println("[j2k] OK  ${javaFile.name} -> ${outFile.relativeTo(outputRoot)}")
            stats.converted++
        }

        return stats
    }

    private fun exit(code: Int) {
        ProcessHandle.current().descendants().forEach { it.destroyForcibly() }
        ApplicationManager.getApplication().exit(true, true, false)
        Runtime.getRuntime().halt(code)
    }
}