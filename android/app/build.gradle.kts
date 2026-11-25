import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.agentos"
    compileSdk = 36

    defaultConfig {
        // The app ID is permanent (installs, updates and Google developer verification are tied to it).
        // The Kotlin code keeps its com.agentos packages (namespace).
        applicationId = "ai.ubiq.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"

    }

    // Release signing: the key lives outside the repo, in ~/.ubiq/release-signing.properties (or the file
    // UBIQ_SIGNING points to) with storeFile, storePassword, keyAlias and keyPassword. Without it,
    // release builds are signed with the debug key, which is fine for trying the app but not for
    // handing it out: phones only accept updates signed with the key the first install had.
    val signingFile = (System.getenv("UBIQ_SIGNING") ?: "${System.getProperty("user.home")}/.ubiq/release-signing.properties").let(::file)
    val releaseSigning = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; aidl = true; buildConfig = true }

    // Lint fails on new errors only; lint-baseline.xml holds the ones that predate it.
    lint { baseline = file("lint-baseline.xml") }

    // The on-phone Pi runtime (scripts/build-node-runtime.sh): Node and its libraries as jniLibs, the harness as an asset.
    // Native libraries are extracted at install (legacy packaging) because Node is exec'd from nativeLibraryDir.
    sourceSets["main"].jniLibs.srcDir("node-runtime/jniLibs")
    sourceSets["main"].assets.srcDir("node-runtime/assets")
    // The "Hey Ubiq" wake word engine (scripts/fetch-wakeword.sh): sherpa-onnx's JNI library and its keyword model.
    sourceSets["main"].jniLibs.srcDir("wakeword/jniLibs")
    sourceSets["main"].assets.srcDir("wakeword/assets")
    androidResources { noCompress += "onnx" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/license.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/notice.txt", "META-INF/ASL2.0",
                "META-INF/*.kotlin_module", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Rebuilds node-runtime/ when the script or the harness changes. Skip with -PnoPi=true (a build without Pi on the phone).
val nodeRuntime by tasks.registering(Exec::class) {
    // The repo root, whichever build includes this module (oss/android or the private one).
    val root = projectDir.parentFile.parentFile
    inputs.file(File(root, "scripts/build-node-runtime.sh"))
    inputs.files(fileTree(File(root, "harness")) { exclude("node_modules/**", ".state/**") })
    outputs.dir("node-runtime")
    commandLine(File(root, "scripts/build-node-runtime.sh").path)
    environment("PATH", "/opt/homebrew/bin:/usr/local/bin:" + System.getenv("PATH"))
    onlyIf { (project.findProperty("noPi") as String?)?.toBoolean() != true }
}
// Downloads the wake word engine once (checksummed; skipped when android/app/wakeword/ is current).
val wakeWord by tasks.registering(Exec::class) {
    val root = projectDir.parentFile.parentFile
    inputs.file(File(root, "scripts/fetch-wakeword.sh"))
    outputs.dir("wakeword")
    commandLine(File(root, "scripts/fetch-wakeword.sh").path)
}
tasks.named("preBuild") { dependsOn(nodeRuntime, wakeWord) }

dependencies {
    // AIDL contract with Ubiq Core (com.agentos.core), the privileged broker on the ROM.
    implementation(project(":coreapi"))
    // The owner's rules (floors, Stop, per-app rules), shared by the app and Core.
    implementation(project(":policy"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OpenAI Responses API over plain HTTP (org.json is part of Android).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Codes for passes on the lock screen (QR, Aztec, PDF417).
    implementation("com.google.zxing:core:3.5.3")
    // Google account authorization for the Gmail connector.
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Shizuku: lets Ubiq run a small helper as the ADB shell user on stock Android, which is
    // what creating a trusted virtual display (the hidden background workspace) requires.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

}
