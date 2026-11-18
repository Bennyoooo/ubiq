import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The owner's rules as plain Kotlin: hard floors, the Stop switch, per-app rules and approvals.
// No Android here, so the same code runs in the app (stock phones) and in Ubiq Core (the ROM),
// and its tests run on the JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
