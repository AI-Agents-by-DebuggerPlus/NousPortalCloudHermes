plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

val ahccDesktopVersion = "1.4.26"
val ahccDesktopVersionCode = 32

kotlin {
    jvmToolchain(21)
    sourceSets.getByName("main").kotlin.srcDir(layout.buildDirectory.dir("generated/ahccVersion"))
}

val generateAhccVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/ahccVersion")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/nous/ahcc/desktop")
        dir.mkdirs()
        dir.resolve("AppVersion.kt").writeText(
            """
            package com.nous.ahcc.desktop

            object AppVersion {
                const val NAME = "$ahccDesktopVersion"
                const val CODE = $ahccDesktopVersionCode
                const val LABEL = "$ahccDesktopVersion ($ahccDesktopVersionCode)"
            }
            """.trimIndent() + "\n"
        )
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateAhccVersion)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Telegram user session (same channel as AHCC / TDLib)
    implementation(platform("it.tdlight:tdlight-java-bom:3.5.4+td.1.8.66"))
    implementation("it.tdlight:tdlight-java")
    implementation("it.tdlight:tdlight-natives") {
        artifact {
            classifier = "windows_amd64"
            extension = "jar"
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nous.ahcc.desktop.MainKt"
        val repoLogs = rootProject.projectDir.parentFile.resolve("Logs").absolutePath
        jvmArgs += listOf(
            "-Dskiko.renderApi=OPENGL",
            "-Dahcc.logs.dir=$repoLogs",
        )
        nativeDistributions {
            packageName = "AHCC"
            packageVersion = ahccDesktopVersion
            description = "Android Hermes Cloud Chat — Desktop"
            vendor = "Nous AHCC"
        }
    }
}
