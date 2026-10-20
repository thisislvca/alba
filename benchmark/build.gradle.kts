plugins { alias(libs.plugins.alba.android.test) }

android {
    // The test APK's package. The app's benchmark and profile builds already use me.lvca.alba.benchmark.
    namespace = "me.lvca.alba.benchmark.test"
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
        create("profile") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
    }
}
androidComponents.beforeVariants { it.enable = it.buildType in setOf("benchmark", "profile") }
dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
