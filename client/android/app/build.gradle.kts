import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nxiwnetwork.client"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.nxiwnetwork.client"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.1.0"

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

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/goJniLibs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val goCoreDir = rootProject.projectDir.resolve("../core").canonicalFile
val generatedGoJniLibsDir = layout.buildDirectory.dir("generated/goJniLibs")
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
    val outputFile = generatedGoJniLibsDir.map { it.file("$abi/libclient.so") }
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
        "-trimpath",
        "-buildmode=pie",
        "-ldflags=-s -w -checklinkname=0",
        "-o",
        outputFile.get().asFile.absolutePath,
        "."
    )
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

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildGoClientArm64, buildGoClientArmv7)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.github.mwiede:jsch:0.2.20")
}
