import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kindleidle.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kindleidle.host"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // The web assets are not copied into the app -- they are read straight
    // out of ../../public. One copy, so the Kindle page can never drift
    // between the Node host and this one.
    sourceSets.named("main") {
        assets.directories.add(rootProject.file("../public").absolutePath)
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // HttpServer is plain JVM code apart from one android.util.Log call,
        // so it can be exercised with real sockets in a unit test. Without
        // this, that Log call throws and takes the test with it.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}

/* ---------------------------------------------------------------------------
   scenes.json is generated from server/idle.js rather than hand-ported.

   The task is skipped when Node is not on PATH, because the generated file is
   checked in: a machine without Node can still build the app, it just cannot
   regenerate the scenes. See android/tools/gen-scenes.js.
--------------------------------------------------------------------------- */

fun nodeOnPath(): Boolean {
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .any { it.isNotBlank() && File(it, exe).canExecute() }
}

val genScenes = tasks.register<Exec>("genScenes") {
    group = "build"
    description = "Regenerates src/main/assets/scenes.json from server/idle.js"

    workingDir = rootProject.file("..")
    commandLine(
        if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node",
        "android/tools/gen-scenes.js"
    )

    inputs.file(rootProject.file("../server/idle.js"))
    outputs.file(file("src/main/assets/scenes.json"))

    onlyIf { nodeOnPath() }
}

tasks.named("preBuild") {
    dependsOn(genScenes)
}
