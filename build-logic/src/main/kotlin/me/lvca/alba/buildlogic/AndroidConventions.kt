package me.lvca.alba.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
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

/**
 * Device tests: Gradle-managed Pixel 2 emulators named `pixel2Api<api>`, using AOSP images for the host architecture,
 * run through the test orchestrator. The orchestrator gives every test its own instrumentation process and clears
 * the app's data first, so a crash, a stuck screen or leftover state fails one test rather than the rest of the run.
 */
internal fun Project.configureDeviceTests(android: CommonExtension) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val abi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64-v8a" else "x86_64"
    android.testOptions.managedDevices.localDevices.apply {
        for (api in MANAGED_DEVICE_API_LEVELS) {
            create("pixel2Api$api") {
                device = "Pixel 2"
                apiLevel = api
                testedAbi = abi
                systemImageSource = "aosp"
            }
        }
    }
    android.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
    dependencies {
        add("androidTestUtil", libs.findLibrary("androidx-test-orchestrator").get())
        add("androidTestUtil", libs.findLibrary("androidx-test-services").get())
    }
}
