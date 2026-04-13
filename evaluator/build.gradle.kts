plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "com.albsd"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("com.albsd.j2k.eval.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
