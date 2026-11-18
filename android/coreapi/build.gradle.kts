import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The contract between the agent app (ai.ubiq.app) and Ubiq Core (com.agentos.core): the AIDL
// interfaces, the names both sides use, and the wire format of shared data (ZenModes).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentos.coreapi"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
