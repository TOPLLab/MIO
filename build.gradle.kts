import java.util.*

plugins {
    kotlin("jvm") version "2.0.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://packages.atlassian.com/mvn/maven-atlassian-external/")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.apache.commons:commons-math3:3.6.1")
    implementation("com.fazecast:jSerialComm:[2.0.0,3.0.0)")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.formdev:flatlaf:3.6")
    implementation("com.formdev:flatlaf-extras:3.6")
    implementation("com.fifesoft:rsyntaxtextarea:3.5.2")

    // Needed for AssemblyScript source mapping:
    //implementation("com.atlassian.sourcemap:sourcemap:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(22)
}

application {
    mainClass.set("be.ugent.topl.mio.MainKt")
}


tasks.register<Jar>("fatJar") {
    archiveFileName.set("mio.jar")

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "be.ugent.topl.mio.MainKt"
        attributes["Implementation-Version"] = project.version
    }
}

val wdcliPath = projectDir.absolutePath + "/WARDuino/build-emu/wdcli"

tasks.register<Exec>("cmakeWARDuino") {
    val buildDir = File(File(wdcliPath).parent)
    buildDir.mkdirs()
    workingDir(buildDir)
    commandLine("sh", "-c", "cmake .. -DBUILD_EMULATOR=ON")
}

tasks.register<Exec>("makeWARDuino") {
    val buildDir = File(File(wdcliPath).parent)
    buildDir.mkdirs()
    workingDir(buildDir)
    commandLine("sh", "-c", "make")
}

tasks.register<Copy>("setup") {
    dependsOn("fatJar")
    dependsOn("cmakeWARDuino")
    dependsOn("makeWARDuino")

    // Setup configuration file.
    val file = File("${System.getenv("HOME")}/.mio/debugger.properties")
    if (!file.exists()) {
        println("Generating a default configuration file ${file.absolutePath}")
        val properties = Properties()
        properties.setProperty("wdcli", wdcliPath)
        properties.store(file.writer(), null)
    }
}
