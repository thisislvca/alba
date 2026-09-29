plugins { alias(libs.plugins.android.test) }

android {
    namespace = "dev.mela.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
        create("profile") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants { it.enable = it.buildType in setOf("benchmark", "profile") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
