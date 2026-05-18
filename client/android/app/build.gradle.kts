import java.io.File
import java.util.Properties

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}
val appVersionCode = 7
val appVersionName = "1.1.0-dev.7"

fun releaseSigningValue(envName: String, propertyName: String): String? {
    return System.getenv(envName) ?: releaseKeystoreProperties.getProperty(propertyName)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nxiwnetwork.client"
    compileSdk = 37
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.nxiwnetwork.client"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseSigningValue("NXIW_RELEASE_STORE_FILE", "storeFile")
                ?: throw GradleException("Missing NXIW_RELEASE_STORE_FILE or storeFile in ${releaseKeystorePropertiesFile.absolutePath}")
            val resolvedStoreFile = File(storeFilePath)
            storeFile = if (resolvedStoreFile.isAbsolute) resolvedStoreFile else rootProject.file(storeFilePath)
            storePassword = releaseSigningValue("NXIW_RELEASE_STORE_PASSWORD", "storePassword")
                ?: throw GradleException("Missing NXIW_RELEASE_STORE_PASSWORD or storePassword in ${releaseKeystorePropertiesFile.absolutePath}")
            keyAlias = releaseSigningValue("NXIW_RELEASE_KEY_ALIAS", "keyAlias")
                ?: throw GradleException("Missing NXIW_RELEASE_KEY_ALIAS or keyAlias in ${releaseKeystorePropertiesFile.absolutePath}")
            keyPassword = releaseSigningValue("NXIW_RELEASE_KEY_PASSWORD", "keyPassword")
                ?: throw GradleException("Missing NXIW_RELEASE_KEY_PASSWORD or keyPassword in ${releaseKeystorePropertiesFile.absolutePath}")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/coreJniLibs").get().asFile)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

val goCoreDir = rootProject.projectDir.resolve("../core-go").canonicalFile
val rustCoreDir = rootProject.projectDir.resolve("../core-rs").canonicalFile
val generatedCoreJniLibsDir = layout.buildDirectory.dir("generated/coreJniLibs")
val rustCargoTargetDir = layout.buildDirectory.dir("cargo/core-rs")
val androidSdkDirProvider = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse("/home/shark/Android/Sdk")

fun registerGoClientBuildTask(
    taskName: String,
    abi: String,
    goArch: String,
    goArm: String?,
    clangPrefix: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedCoreJniLibsDir.map { it.file("$abi/libcore_go.so") }
    val sdkDir = androidSdkDirProvider
    val llvmBin = sdkDir.map { "$it/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin" }

    group = "build"
    description = "Build Go client core for Android $abi"
    workingDir = goCoreDir

    inputs.dir(goCoreDir)
    outputs.file(outputFile)

    doFirst {
        val ccFile = file("${llvmBin.get()}/$clangPrefix-clang")
        if (!ccFile.exists()) {
            throw GradleException("Android NDK 26.1.10909125 is required. Missing: ${ccFile.absolutePath}")
        }
        outputFile.get().asFile.parentFile.mkdirs()
    }

    environment("GOOS", "android")
    environment("GOARCH", goArch)
    environment("CGO_ENABLED", "1")
    environment("CC", "${llvmBin.get()}/$clangPrefix-clang")
    environment("CXX", "${llvmBin.get()}/$clangPrefix-clang++")
    if (goArm != null) {
        environment("GOARM", goArm)
    }

    commandLine(
        "go",
        "build",
        "-buildvcs=false",
        "-trimpath",
        "-buildmode=pie",
        "-ldflags=-s -w -checklinkname=0",
        "-o",
        outputFile.get().asFile.absolutePath,
        "."
    )
}

fun registerRustClientBuildTask(
    taskName: String,
    abi: String,
    rustTarget: String,
    clangPrefix: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedCoreJniLibsDir.map { it.file("$abi/libcore_rs.so") }
    val sdkDir = androidSdkDirProvider
    val llvmBin = sdkDir.map { "$it/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin" }
    val linkerEnvName = "CARGO_TARGET_${rustTarget.uppercase().replace("-", "_")}_LINKER"
    val cargoTargetDir = rustCargoTargetDir

    group = "build"
    description = "Build Rust client core for Android $abi"
    workingDir = rustCoreDir

    inputs.files(
        fileTree(rustCoreDir) {
            include("Cargo.toml", "Cargo.lock", "build.rs", "src/**", "native/**")
        }
    )
    outputs.file(outputFile)

    doFirst {
        val ccFile = file("${llvmBin.get()}/$clangPrefix-clang")
        if (!ccFile.exists()) {
            throw GradleException("Android NDK 26.1.10909125 is required. Missing: ${ccFile.absolutePath}")
        }
        outputFile.get().asFile.parentFile.mkdirs()
    }

    environment("CC", "${llvmBin.get()}/$clangPrefix-clang")
    environment("CXX", "${llvmBin.get()}/$clangPrefix-clang++")
    environment("AR", "${llvmBin.get()}/llvm-ar")
    environment(linkerEnvName, "${llvmBin.get()}/$clangPrefix-clang")
    environment("CARGO_TARGET_DIR", cargoTargetDir.get().asFile.absolutePath)

    commandLine(
        "cargo",
        "build",
        "--locked",
        "--release",
        "--target",
        rustTarget,
        "--bin",
        "client_rs"
    )

    doLast {
        val builtBinary = cargoTargetDir.get().asFile.resolve("$rustTarget/release/client_rs")
        if (!builtBinary.exists()) {
            throw GradleException("Rust core build did not produce ${builtBinary.absolutePath}")
        }
        builtBinary.copyTo(outputFile.get().asFile, overwrite = true)
        providers.exec {
            commandLine("${llvmBin.get()}/llvm-strip", "--strip-unneeded", outputFile.get().asFile.absolutePath)
        }.result.get().assertNormalExitValue()
        outputFile.get().asFile.setExecutable(true, false)
    }
}

val buildGoClientArm64 = registerGoClientBuildTask(
    taskName = "buildGoClientArm64",
    abi = "arm64-v8a",
    goArch = "arm64",
    goArm = null,
    clangPrefix = "aarch64-linux-android29"
)

val buildGoClientArmv7 = registerGoClientBuildTask(
    taskName = "buildGoClientArmv7",
    abi = "armeabi-v7a",
    goArch = "arm",
    goArm = "7",
    clangPrefix = "armv7a-linux-androideabi29"
)

val buildRustClientArm64 = registerRustClientBuildTask(
    taskName = "buildRustClientArm64",
    abi = "arm64-v8a",
    rustTarget = "aarch64-linux-android",
    clangPrefix = "aarch64-linux-android29"
)

val buildRustClientArmv7 = registerRustClientBuildTask(
    taskName = "buildRustClientArmv7",
    abi = "armeabi-v7a",
    rustTarget = "armv7-linux-androideabi",
    clangPrefix = "armv7a-linux-androideabi29"
)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildGoClientArm64, buildGoClientArmv7, buildRustClientArm64, buildRustClientArmv7)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha19")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.github.mwiede:jsch:0.2.20")
}

val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
val renameReleaseApks = tasks.register("renameReleaseApks") {
    doLast {
        val outputDir = releaseApkDir.get().asFile
        val sourceApks = outputDir.listFiles { file ->
            file.isFile && file.name.endsWith(".apk") && (
                file.name == "app-release.apk" || file.name.startsWith("app-") && file.name.endsWith("-release.apk")
            )
        }.orEmpty()
        if (sourceApks.isEmpty()) {
            return@doLast
        }

        outputDir.listFiles { file ->
            file.isFile && file.name.startsWith("NxiwNetwork-v$appVersionName-") && file.name.endsWith(".apk")
        }?.forEach { it.delete() }

        sourceApks.forEach { apk ->
            val abi = when {
                apk.name.contains("arm64-v8a") -> "arm64-v8a"
                apk.name.contains("armeabi-v7a") -> "armeabi-v7a"
                apk.name.contains("universal") || apk.name == "app-release.apk" -> "universal"
                else -> apk.name.removePrefix("app-").removeSuffix("-release.apk")
            }
            val target = outputDir.resolve("NxiwNetwork-v$appVersionName-$abi.apk")
            if (!apk.renameTo(target)) {
                apk.copyTo(target, overwrite = true)
                apk.delete()
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(renameReleaseApks)
}
