package me.lvca.alba.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 30
internal const val TARGET_SDK = 36

/**
 * Managed devices by name: a Pixel 9 at the minimum and target API levels, and a compact phone (Pixel 2, 411 x 731 dp)
 * at the target level. CI runs `:<module>:<name>DebugAndroidTest`.
 */
private val MANAGED_DEVICES = mapOf(
    "pixel9Api30" to ("Pixel 9" to 30),
    "pixel9Api36" to ("Pixel 9" to 36),
    "compactApi36" to ("Pixel 2" to 36),
)

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
 * JVM tests (`src/test`): plain JUnit plus Robolectric, which runs Android framework code, resources, Room and Compose
 * UI tests on the JVM. Native graphics gives Robolectric real bitmaps and drawing, which decoding fixtures and
 * Roborazzi screenshots need.
 */
internal fun Project.configureJvmTests(android: CommonExtension, isolateTestClasses: Boolean = false) {
    android.testOptions.unitTests.isIncludeAndroidResources = true
    val crashReports = layout.buildDirectory.dir("jvm-crash").get().asFile
    tasks.withType(Test::class.java).configureEach {
        maxHeapSize = "3g"
        // Robolectric's Android 16 runtime reaches into JDK internals for file descriptors and shared memory.
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        // C1 only: test JVMs are short-lived, so C2 rarely pays off.
        jvmArgs("-XX:TieredStopAtLevel=1")
        // A test JVM whose main thread never reaches a safepoint stops every other thread for good (see below). Fail it
        // after a minute, with a crash report in the build directory, instead of letting it run until CI's timeout.
        jvmArgs(
            "-XX:+UnlockDiagnosticVMOptions", "-XX:+SafepointTimeout", "-XX:SafepointTimeoutDelay=60000",
            "-XX:+AbortVMOnSafepointTimeout", "-XX:ErrorFile=" + crashReports.resolve("hs_err_pid%p.log").path,
        )
        if (isolateTestClasses) {
            // One JVM per test class. In one long JVM, background work an earlier whole-app test left running (each test
            // gets a new Robolectric application, not a new process) used resources while a later test resolved a
            // theme. Robolectric's resource code is not thread-safe: the main thread then looped forever in
            // AttributeResolution10.ApplyStyle, and the JVM stalled at its next safepoint. Separate JVMs, run side by
            // side, also finish sooner than one.
            forkEvery = 1
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_TEST_FORKS)
        }
        // C1-only JVMs default to a small code cache, and every Robolectric sandbox (one per device configuration) loads
        // its own copy of the app and framework classes; a full run filled it ("Out of space in CodeCache").
        jvmArgs("-XX:ReservedCodeCacheSize=512m")
        systemProperty("robolectric.graphicsMode", "NATIVE")
    }
}

/** Each test JVM can take its 3 GB heap; three fit beside Gradle on a 16 GB CI runner. */
private const val MAX_TEST_FORKS = 3

/**
 * Device tests: Gradle-managed emulators ([MANAGED_DEVICES]), using AOSP images for the host architecture,
 * run through the test orchestrator. The orchestrator gives every test its own instrumentation process and clears
 * the app's data first, so a crash, a stuck screen or leftover state fails one test rather than the rest of the run.
 */
internal fun Project.configureDeviceTests(android: CommonExtension) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val abi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64-v8a" else "x86_64"
    android.testOptions.managedDevices.localDevices.apply {
        for ((name, profile) in MANAGED_DEVICES) {
            create(name) {
                device = profile.first
                apiLevel = profile.second
                testedAbi = abi
                systemImageSource = "aosp"
            }
        }
    }
    android.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
    // Files tests write through androidx.test's TestStorage are copied into the build's additional test output.
    android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    dependencies {
        add("androidTestUtil", libs.findLibrary("androidx-test-orchestrator").get())
        add("androidTestUtil", libs.findLibrary("androidx-test-services").get())
    }
}
