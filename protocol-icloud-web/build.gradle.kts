plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.mela.protocol"
    compileSdk = 37

    testOptions {
        managedDevices {
            localDevices {
                for (api in listOf(30, 36)) {
                    create("pixel2Api$api") {
                        device = "Pixel 2"
                        apiLevel = api
                        testedAbi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64-v8a" else "x86_64"
                        systemImageSource = "aosp"
                    }
                }
            }
        }
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
