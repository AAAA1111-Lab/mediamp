/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

rootProject.name = "mediamp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") // Compose Multiplatform pre-release versions
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":mediamp-internal-utils")
include(":mediamp-api")

// A consumer that composes these modules from source (see the `consumer` note in the README of
// this fork) switches this on. It then gets only the library modules it can link, because the rest
// cannot be configured on every host:
//   - `mediamp-ffmpeg` resolves an MSYS2 installation at configuration time, so it fails outright
//     on a Windows host;
//   - `mediamp-mpv` and `mediamp-native-loader` are the native runtime machinery, which needs
//     MSYS2 and per-architecture native builds;
//   - the rest are aggregator/preview/demo/CI projects nobody links against.
// Publishing the fork is unaffected either way: the workflow names the modules it publishes
// explicitly.
//
// The switch is read from the command line property and from a system property, because a Gradle
// property set on the including build is not reliably visible to an included build, while a system
// property set before `includeBuild` is.
val consumerOnly = providers.gradleProperty("mediamp.consumer").orNull?.toBoolean() == true ||
    System.getProperty("mediamp.consumer")?.toBoolean() == true

if (!consumerOnly) {
    //include(":mediamp-vlc") // deprecated
    include(":mediamp-vlc-loader")
    include(":mediamp-mpv")
    include(":mediamp-avkit")

    include(":mediamp-ffmpeg")
    include(":mediamp-native-loader")
    include(":mediamp-all")

    include(":mediamp-web-preview")

    //include(":mediamp-preview")
    include(":mediamp-mpv-demo") // macOS-only prototype: mpv hwdec + Compose overlay

    include(":ci-helper")
    include(":catalog")
}

include(":mediamp-exoplayer")
include(":mediamp-test")
include(":mediamp-source-ktxio")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
