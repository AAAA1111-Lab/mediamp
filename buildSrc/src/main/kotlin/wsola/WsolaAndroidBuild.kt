/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package wsola

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import Os
import getOs
import getPropertyOrNull
import nativebuild.DEFAULT_ANDROID_ABIS
import nativebuild.androidNdkHostTag
import nativebuild.resolveAndroidAbis
import nativebuild.resolveNdkDir
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File


/**
 * Builds `libmediamp_wsola.so` (WSOLA time-stretch for the ExoPlayer backend) with the
 * NDK LLVM clang++, one task per Android ABI, and injects the result into the module's
 * jniLibs via `variant.sources.jniLibs` (same mechanism as the mpv packaging).
 *
 * Properties:
 *  - `-Pmediamp.exoplayer.wsola.skip=true` disables everything (Kotlin falls back to Sonic).
 *  - `-Pmediamp.exoplayer.wsola.androidabis=arm64-v8a,x86_64` restricts the ABI set.
 */

abstract class CompileWsolaLibraryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:Input
    abstract val windowsHost: Property<Boolean>

    /**
     * The `.cpp` files to compile, in a stable order.
     *
     * A file collection rather than a directory: the caller decides exactly what goes into one
     * shared library, so a source directory that also holds other entry points (the FLAC probe
     * has its own `main`) cannot accidentally contribute sources to a different binary.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        val sourceFiles = sources.files
            .filter { it.extension == "cpp" }
            .sortedBy { it.name }
        require(sourceFiles.isNotEmpty()) { "No .cpp sources configured for ${outputFile.get().asFile}" }
        val launcher = if (windowsHost.get()) listOf("cmd.exe", "/d", "/c") else emptyList()
        val command = launcher +
            compiler.get().asFile.absolutePath +
            compilerArgs.get() +
            sourceFiles.map { it.absolutePath } +
            listOf("-o", out.absolutePath)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Native compile failed (exit $exitCode): ${command.joinToString(" ")}\n$output",
            )
        }
        logger.info(output)
    }
}

abstract class PrepareWsolaAndroidJniLibsTask : DefaultTask() {
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        project.sync {
            inputFiles.files.sortedBy { it.parentFile.name }.forEach { library ->
                from(library) {
                    into(library.parentFile.name)
                }
            }
            into(outputDir)
        }
    }
}

/**
 * The native libraries the ExoPlayer backend needs on Android.
 *
 * WSOLA is the time-stretch processor. FLAC is the bundled software FLAC decoder: MediaCodec has
 * no extension point that could register a decoder, so owning the decode is the only way to play
 * FLAC on a device whose platform decoder cannot handle 24-bit streams.
 *
 * Each entry is compiled from its own `src/cpp` subdirectory as one shared library, because the
 * compile task passes every `.cpp` in that directory to a single clang++ invocation.
 */
private data class MediampNativeLibrary(
    /** Source files under `src/cpp`, relative to the module directory. */
    val sources: List<String>,
    /** `lib<name>.so`. */
    val libraryName: String,
    val taskNameInfix: String,
    val purpose: String,
    /** Extra include directories, relative to the module directory. */
    val includeDirs: List<String> = emptyList(),
    /** Third-party include directories, passed with `-isystem` so their warnings stay out. */
    val thirdPartyIncludeDirs: List<String> = emptyList(),
)

private val MEDIAMP_NATIVE_LIBRARIES = listOf(
    MediampNativeLibrary(
        sources = listOf("cpp/scaletempo2.cpp", "cpp/wsola_jni.cpp"),
        libraryName = "mediamp_wsola",
        taskNameInfix = "Wsola",
        purpose = "WSOLA time-stretch",
    ),
    MediampNativeLibrary(
        sources = listOf("cpp/flac/flac_decode.cpp", "cpp/flac/flac_jni.cpp", "cpp/flac/dr_flac_impl.cpp"),
        libraryName = "mediamp_flac",
        taskNameInfix = "Flac",
        purpose = "FLAC software decoder",
        includeDirs = listOf("cpp/flac"),
        thirdPartyIncludeDirs = listOf("cpp/flac/thirdparty"),
    ),
)

fun Project.configureWsolaAndroidBuild() {
    if (getPropertyOrNull("mediamp.exoplayer.wsola.skip")?.toBoolean() == true) {
        logger.lifecycle("Skipping mediamp native builds: mediamp.exoplayer.wsola.skip=true")
        return
    }
    val ndkDir = runCatching { resolveNdkDir() }.getOrElse {
        logger.warn("Android NDK not found - skipping mediamp native builds. Set ndk.dir or ANDROID_NDK_HOME to enable.")
        return
    }
    val hostOs = getOs()
    val hostTag = androidNdkHostTag(hostOs)
    val windowsHost = hostOs == Os.Windows
    val llvmBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    require(llvmBinDir.isDirectory) { "NDK LLVM toolchain not found at '$llvmBinDir'." }
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")

    val abis = resolveAndroidAbis(
        propertyNames = listOf("mediamp.exoplayer.wsola.androidabis"),
        availableAbis = DEFAULT_ANDROID_ABIS,
    )

    val compileTasks = mutableListOf<TaskProvider<CompileWsolaLibraryTask>>()
    MEDIAMP_NATIVE_LIBRARIES.forEach { library ->
        abis.forEach { abi ->
            val taskName = "compile${library.taskNameInfix}${abi.abi.replace("-", "")}"
            compileTasks += tasks.register<CompileWsolaLibraryTask>(taskName) {
                group = "mediamp"
                description = "Compile lib${library.libraryName}.so (${library.purpose}) for Android ${abi.abi}"
                val compilerSuffix = if (windowsHost) ".cmd" else ""
                compiler.set(
                    llvmBinDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++$compilerSuffix"),
                )
                this.windowsHost.set(windowsHost)
                val includeArgs =
                    library.includeDirs.flatMap {
                        listOf("-I", layout.projectDirectory.dir("src/$it").asFile.absolutePath)
                    } +
                        library.thirdPartyIncludeDirs.flatMap {
                            listOf("-isystem", layout.projectDirectory.dir("src/$it").asFile.absolutePath)
                        }
                compilerArgs.set(
                    listOf(
                        "--sysroot=${sysroot.absolutePath}",
                        "-std=c++17",
                        "-O2",
                        "-DNDEBUG",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        "-fPIC",
                        "-shared",
                        "-Wl,-z,max-page-size=16384",
                        "-llog",
                    ) + includeArgs,
                )
                sources.from(library.sources.map { layout.projectDirectory.file("src/$it") })
                outputFile.set(
                    layout.buildDirectory.file(
                        "generated/mediamp-jniLibs/${abi.abi}/lib${library.libraryName}.so",
                    ),
                )
            }
        }
    }

    val prepareTask = tasks.register<PrepareWsolaAndroidJniLibsTask>("prepareWsolaAndroidJniLibs") {
        group = "mediamp"
        description = "Prepare merged mediamp jniLibs directory for Android variants"
        dependsOn(compileTasks)
        inputFiles.from(compileTasks.map { it.flatMap(CompileWsolaLibraryTask::outputFile) })
        outputDir.set(layout.buildDirectory.dir("generated/mediamp-jniLibs-merged"))
    }

    val androidComponents =
        extensions.findByName("androidComponents") as? KotlinMultiplatformAndroidComponentsExtension
    androidComponents?.onVariants(androidComponents.selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTask,
            PrepareWsolaAndroidJniLibsTask::outputDir,
        )
    }

    registerFlacProbeExecutable(ndkDir, hostTag, windowsHost, sysroot)
}

/**
 * Builds `flac_probe`, a small Android executable that drives the FLAC decoder over a file on
 * disk and reports which step fails.
 *
 * It exists because playback failures can only be attributed on a real device, while iterating
 * through the app needs a rebuild, an install and UI driving. The probe links the same
 * `mediamp_flac::decodeFrame` the renderer uses, so its verdict transfers directly.
 *
 * Not part of any shipped artifact; push it next to a FLAC file and run it over adb:
 *
 *   ./gradlew :mediamp-exoplayer:buildFlacProbe
 *   adb push <build>/generated/flac-probe/flac_probe /data/local/tmp/
 *   adb push sample.flac /data/local/tmp/
 *   adb shell /data/local/tmp/flac_probe /data/local/tmp/sample.flac
 */
private fun Project.registerFlacProbeExecutable(
    ndkDir: File,
    hostTag: String,
    windowsHost: Boolean,
    sysroot: File,
) {
    val llvmBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    val abi = DEFAULT_ANDROID_ABIS.first { it.abi == "arm64-v8a" }
    tasks.register<CompileWsolaLibraryTask>("buildFlacProbe") {
        group = "mediamp"
        description = "Build the FLAC decoder probe executable for Android ${abi.abi}"
        val compilerSuffix = if (windowsHost) ".cmd" else ""
        compiler.set(llvmBinDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++$compilerSuffix"))
        this.windowsHost.set(windowsHost)
        compilerArgs.set(
            listOf(
                "--sysroot=${sysroot.absolutePath}",
                "-std=c++17",
                "-O1",
                "-Wall",
                "-Wextra",
                "-Werror",
                // dr_flac is third-party; include it as a system header so its warnings stay out.
                "-isystem",
                layout.projectDirectory.dir("src/cpp/flac/thirdparty").asFile.absolutePath,
                "-I",
                layout.projectDirectory.dir("src/cpp/flac").asFile.absolutePath,
            ),
        )
        sources.from(
            layout.projectDirectory.file("src/cpp/flac/flac_decode.cpp"),
            layout.projectDirectory.file("src/cpp/flac/dr_flac_impl.cpp"),
            layout.projectDirectory.file("src/cpp/flac_probe/flac_probe.cpp"),
        )
        outputFile.set(layout.buildDirectory.file("generated/flac-probe/flac_probe"))
    }
}
