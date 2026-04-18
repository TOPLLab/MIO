import java.util.*

plugins {
    kotlin("jvm") version "2.2.0"
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
    implementation("com.formdev:flatlaf:3.7.1")
    implementation("com.formdev:flatlaf-extras:3.7.1")
    implementation("com.fifesoft:rsyntaxtextarea:3.6.2")

    // Needed for AssemblyScript source mapping:
    //implementation("com.atlassian.sourcemap:sourcemap:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
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

fun cmakeBuildTask(dir: String): String {
    val buildDir = File(File(dir).parent)
    buildDir.mkdirs()

    val name = buildDir.parentFile.name

    tasks.register<Exec>("cmake-$name") {
        workingDir(buildDir)
        commandLine("sh", "-c", "cmake .. -DBUILD_EMULATOR=ON -DCMAKE_BUILD_TYPE=Release")
    }

    tasks.register<Exec>("make-$name") {
        dependsOn("cmake-$name")
        workingDir(buildDir)
        commandLine("sh", "-c", "make")
    }

    return "make-$name"
}

fun setDefaultZephyrWasmBinary() {
    val zephyrBuildDir = File(projectDir.absolutePath + "/WARDuino/platforms/Zephyr")
    File(projectDir.absolutePath + "/examples/binary-counter/upload.wasm").copyTo(File(zephyrBuildDir.absolutePath + "/upload.wasm"), overwrite = true)
    File(projectDir.absolutePath + "/examples/binary-counter/upload.wasm.map").copyTo(File(zephyrBuildDir.absolutePath + "/upload.wasm.map"), overwrite = true)
}

val wdcliPath = projectDir.absolutePath + "/WARDuino/build-emu/wdcli"
val wdcliSymbolicPath = projectDir.absolutePath + "/WARDuino-symbolic/build-emu/wdcli"
private val compileWdcliTask = cmakeBuildTask(wdcliPath)
private val compileWdcliSymbolicTask = cmakeBuildTask(wdcliSymbolicPath)

tasks.register<Copy>("setup") {
    dependsOn("fatJar")
    dependsOn(compileWdcliTask)
    dependsOn(compileWdcliSymbolicTask)
    setDefaultZephyrWasmBinary()

    // Setup configuration file.
    val file = File("${System.getenv("HOME")}/.mio/debugger.properties")
    if (!file.exists()) {
        writeDefaultConfig(file)
    } else {
        val padding = 2
        val s = "Config already exists, do you want to overwrite it? type y/n"
        println("┌" + "─".repeat(s.length + padding * 2) + "┐")
        println("│\u001b[34;1m${" ".repeat(padding)}$s${" ".repeat(padding)}\u001b[0m│")
        println("└" + "─".repeat(s.length + padding * 2) + "┘")
        var answer = readln()
        while (answer != "y" && answer != "n") {
            answer = readln()
            println("Please enter y or n")
        }
        if (answer == "y") {
            writeDefaultConfig(file)
        }
        else {
            println("Keeping existing config $file")
        }
    }
}

fun writeDefaultConfig(file: File) {
    file.parentFile.mkdirs()
    println("Generating a default configuration file ${file.absolutePath}")
    val properties = Properties()
    properties.setProperty("wdcli", wdcliPath)
    properties.setProperty("wdcli-symbolic", wdcliSymbolicPath)
    properties.setProperty("concolic", "true")
    properties.setProperty("useEmulator", "true")
    properties.store(file.writer(), null)
}