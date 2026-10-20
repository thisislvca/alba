package me.lvca.alba.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 30
internal const val TARGET_SDK = 36

/** API levels of the managed devices. CI runs `:<module>:pixel2Api<api>DebugAndroidTest`. */
private val MANAGED_DEVICE_API_LEVELS = listOf(30, 36)

/** SDK levels, Java 17 bytecode and the test runner shared by every Android module. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = COMPILE_SDK
    android.defaultConfig.minSdk = MIN_SDK
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Gradle-managed Pixel 2 emulators named `pixel2Api<api>`, using AOSP images for the host architecture. */
internal fun CommonExtension.configureManagedDevices() {
    val abi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64-v8a" else "x86_64"
    testOptions.managedDevices.localDevices.apply {
        for (api in MANAGED_DEVICE_API_LEVELS) {
            create("pixel2Api$api") {
                device = "Pixel 2"
                apiLevel = api
                testedAbi = abi
                systemImageSource = "aosp"
            }
        }
    }
}
