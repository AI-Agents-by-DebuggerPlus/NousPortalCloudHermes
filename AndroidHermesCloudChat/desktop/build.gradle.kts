plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

val ahccDesktopVersion = "1.2.0"
val ahccDesktopVersionCode = 3

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
}

compose.desktop {
    application {
        mainClass = "com.nous.ahcc.desktop.MainKt"
        jvmArgs += listOf("-Dskiko.renderApi=OPENGL")
        nativeDistributions {
            packageName = "AHCC"
            packageVersion = ahccDesktopVersion
            description = "Android Hermes Cloud Chat — Desktop"
            vendor = "Nous AHCC"
        }
    }
}
