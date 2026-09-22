/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import wsola.configureWsolaAndroidBuild

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `android-mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP backend using ExoPlayer"

kotlin {
    androidLibrary {
        namespace = "org.openani.mediamp.exoplayer"
    }

    sourceSets {
        androidMain.dependencies {
            api(projects.mediampApi)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
            // SimpleDecoder / Decoder, used by the bundled FLAC software audio renderer.
            implementation(libs.androidx.media3.decoder)
        }

        androidHostTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
        }
    }
}

configureWsolaAndroidBuild()

val bundledNativeLicenses = listOf(
    "META-INF/licenses/org.openani.mediamp/mediamp-exoplayer/scaletempo2.txt",
    "META-INF/licenses/org.openani.mediamp/mediamp-exoplayer/dr_flac.txt",
)
val verifyWsolaLicensePackaging by tasks.registering {
    group = "verification"
    description = "Verifies that the Android AAR includes every bundled native license."
    dependsOn("bundleAndroidMainAar")

    val aar = layout.buildDirectory.file("outputs/aar/${project.name}.aar")
    inputs.file(aar)
    inputs.property("bundledNativeLicenses", bundledNativeLicenses)

    doLast {
        ZipFile(aar.get().asFile).use { aarFile ->
            val classesJar = aarFile.getEntry("classes.jar")
                ?: error("classes.jar is missing from ${aar.get().asFile}")
            val packagedEntries = aarFile.getInputStream(classesJar).use { input ->
                ZipInputStream(ByteArrayInputStream(input.readBytes())).use { classes ->
                    generateSequence { classes.nextEntry }.map { it.name }.toHashSet()
                }
            }
            val missing = bundledNativeLicenses.filterNot { it in packagedEntries }
            check(missing.isEmpty()) {
                "Missing licenses in classes.jar of ${aar.get().asFile}: $missing"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyWsolaLicensePackaging)
}

mavenPublishing {
    configure(
        KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")),
    )
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
