package com.albsd.j2k

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.GeneralSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

class J2kAppListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: List<String>) {
        val projectDir = System.getProperty("j2k.projectDir") ?: return

        val projectPath = Path.of(projectDir)
        if (!projectPath.toFile().exists()) {
            System.err.println("[j2k] ERROR: project dir not found: $projectDir")
            return
        }

        println("[j2k] Opening project: $projectDir")
        GeneralSettings.getInstance().confirmOpenNewProject =
            GeneralSettings.OPEN_PROJECT_SAME_WINDOW

        ApplicationManager.getApplication().executeOnPooledThread {
            val project = ProjectManagerEx.getInstanceEx()
                .openProject(projectPath, OpenProjectTask { runConfigurators = false })
            if (project == null) {
                System.err.println("[j2k] ERROR: openProject() returned null for $projectDir")
            } else {
                println("[j2k] Project opened: ${project.name}")
            }
        }
    }
}