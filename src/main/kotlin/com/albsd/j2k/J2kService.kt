package com.albsd.j2k

import com.intellij.ide.GeneralSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

@Service(Service.Level.APP)
class J2kService {

    init {
        System.err.println("[j2k] J2kService init")
        System.err.println("[j2k] j2k.projectDir=${System.getProperty("j2k.projectDir")}")

        val projectDir = System.getProperty("j2k.projectDir")

        if (projectDir == null) {
            System.err.println("[j2k] j2k.projectDir not set — skipping")
        } else {
            val path = Path.of(projectDir)

            if (!path.toFile().exists()) {
                System.err.println("[j2k] ERROR: project dir not found: $projectDir")
            } else {
                System.err.println("[j2k] Scheduling project open: $projectDir")
                GeneralSettings.getInstance().confirmOpenNewProject =
                    GeneralSettings.OPEN_PROJECT_SAME_WINDOW

                ApplicationManager.getApplication().executeOnPooledThread {
                    val project = ProjectManagerEx.getInstanceEx()
                        .openProject(path, OpenProjectTask { runConfigurators = false })

                    if (project == null) {
                        System.err.println("[j2k] ERROR: openProject() returned null for $projectDir")
                    } else {
                        System.err.println("[j2k] Project opened: ${project.name}")
                    }
                }
            }
        }
    }
}
