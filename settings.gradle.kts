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

// A consumer that composes these modules from source switches this on by committing an empty
// `consumer-only` marker file next to this settings file. It then gets only the library modules it
// can link, because the rest cannot be configured on every host:
//   - `mediamp-ffmpeg` resolves an MSYS2 installation at configuration time, so it fails outright
//     on a Windows host;
//   - `mediamp-mpv` and `mediamp-native-loader` are the native runtime machinery, which needs
//     MSYS2 and per-architecture native builds;
//   - the rest are aggregator/preview/demo/CI projects nobody links against.
// Publishing the fork is unaffected either way: the workflow names the modules it publishes
// explicitly.
//
// A marker file rather than a property: when this build is composed via `includeBuild`, Gradle
// gives the included build its own instance and neither `-P` nor system properties set by the
// including build arrive reliably (verified - `System.getProperty` read null there). A file is
// simply present, which also means a plain `submodules: recursive` checkout needs no extra CI
// wiring.
val consumerOnly = file("consumer-only").isFile

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
