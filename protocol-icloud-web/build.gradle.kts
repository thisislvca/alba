plugins {
    alias(libs.plugins.alba.android.library)
}

android {
    namespace = "me.lvca.alba.protocol"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.runtime)
}

// The live contract test (src/test/.../livecontract) talks to a real iCloud account, read-only. It never runs with
// testDebugUnitTest or in CI's normal jobs: only through liveContractTest, which skips when no session is configured.
// See README, "Live contract test".
val liveContract = "**/livecontract/**"
tasks.withType<Test>().configureEach {
    if (name != "liveContractTest") exclude(liveContract)
}
afterEvaluate {
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    tasks.register<Test>("liveContractTest") {
        description = "Checks the iCloud web protocol's assumptions against a real account, read-only."
        group = "verification"
        testClassesDirs = files(unitTests.map { it.testClassesDirs })
        classpath = files(unitTests.map { it.classpath })
        dependsOn(unitTests.map { it.dependsOn })
        include(liveContract)
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
    tasks.register<JavaExec>("liveSignIn") {
        description = "Signs in to iCloud interactively and saves the session the live contract test uses."
        group = "verification"
        classpath = files(unitTests.map { it.classpath })
        dependsOn(unitTests.map { it.dependsOn })
        mainClass.set("me.lvca.alba.protocol.livecontract.LiveSignInKt")
        standardInput = System.`in`
        workingDir = rootDir
    }
}
