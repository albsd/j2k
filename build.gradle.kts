import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.gradle.api.tasks.Input

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.albsd"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("org.jetbrains.kotlin")
//        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.albsd.j2k"
        name = "J2K"
        version = "0.0.1"
        description = """
            Java-To-Kotlin converter for CI pipelines.
            Reads Java source from j2k.sourceDir
            and writes Kotlin output to j2k.outputDir.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
}

tasks.named<RunIdeTask>("runIde") {
    val sourceDirProp = providers.gradleProperty("sourceDir")
        .orElse(rootProject.projectDir.parentFile.resolve("src/main/java").absolutePath)
    val outputDirProp = providers.gradleProperty("outputDir")
        .orElse(rootProject.projectDir.parentFile.resolve("converted-kotlin").absolutePath)
    val projectDirProp = providers.gradleProperty("projectDir")
        .orElse(rootProject.projectDir.parentFile.absolutePath)
    doFirst {
        println("---------")
        println("projectDirProp = ${projectDirProp.get()}")
        println("sourceDirProp  = ${sourceDirProp.get()}")
        println("outputDirProp  = ${outputDirProp.get()}")
        println("---------")
    }

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dj2k.sourceDir=${sourceDirProp.get()}",
            "-Dj2k.outputDir=${outputDirProp.get()}",
            "-Dj2k.projectDir=${projectDirProp.get()}",
//            "-Djava.awt.headless=true",
            "-Didea.is.internal=true",
            "-Didea.auto.reload.plugins=false",
            "-Didea.agreement.accepted=true",
            "-Didea.trust.all.projects=true",
            "-Didea.initially.ask.config=never",
            "-Didea.send.usage.stat=false",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Djb.consents.confirmation.enabled=false",
        )
    })

}
