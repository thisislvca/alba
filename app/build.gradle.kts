import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sentry)
}

// Keep signing credentials outside the checkout. Missing credentials produce unsigned artifacts.
val signingPropertiesFile = file(providers.environmentVariable("MELA_SIGNING_PROPERTIES")
    .getOrElse("${System.getProperty("user.home")}/.config/mela/release-signing/signing.properties"))
val releaseCredentials = Properties()
if (signingPropertiesFile.isFile) {
    releaseCredentials.load(providers.fileContents(layout.file(provider { signingPropertiesFile })).asText.get().reader())
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !releaseCredentials.getProperty(it).isNullOrBlank()
    }) { "Release signing properties are incomplete." }
}

val sentryPropertiesFile = file(providers.environmentVariable("MELA_SENTRY_PROPERTIES")
    .getOrElse("${System.getProperty("user.home")}/.config/mela/sentry/sentry.properties"))
val sentryProperties = Properties()
if (sentryPropertiesFile.isFile) {
    sentryProperties.load(providers.fileContents(layout.file(provider { sentryPropertiesFile })).asText.get().reader())
}
val sentryDsn = providers.environmentVariable("MELA_SENTRY_DSN")
    .getOrElse(sentryProperties.getProperty("dsn", "")).trim()
val sentryDsnLiteral = "\"" + sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "dev.mela.app"
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
        buildConfigField("boolean", "BENCHMARK_MODE", "false")
        applicationId = "com.mannaworks.mela"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en", "it")
        buildConfigField(
            "String",
            "SENTRY_DSN",
            sentryDsnLiteral,
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseCredentials.isNotEmpty()) {
            create("production") {
                storeFile = file(releaseCredentials.getProperty("storeFile"))
                storePassword = releaseCredentials.getProperty("storePassword")
                keyAlias = releaseCredentials.getProperty("keyAlias")
                keyPassword = releaseCredentials.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"development\"")
        }
        release {
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"family-beta\"")
            if (releaseCredentials.isNotEmpty()) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildTypes {
        for (name in listOf("benchmark", "profile")) {
            create(name) {
                initWith(getByName("release"))
                applicationIdSuffix = ".benchmark"
                signingConfig = signingConfigs.getByName("debug")
                isDebuggable = false
                isMinifyEnabled = name == "benchmark"
                matchingFallbacks += "release"
                buildConfigField("boolean", "BENCHMARK_MODE", "true")
                buildConfigField("String", "SENTRY_DSN", "\"\"")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

sentry {
    ignoredBuildTypes.set(setOf("benchmark", "profile"))
    org.set(sentryProperties.getProperty("org", "mannaworks"))
    projectName.set(sentryProperties.getProperty("project", "mela-android"))
    sentryProperties.getProperty("authToken")?.takeIf(String::isNotBlank)?.let(authToken::set)
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(!sentryProperties.getProperty("authToken").isNullOrBlank())
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    tracingInstrumentation { enabled.set(false) }
    autoInstallation { enabled.set(false) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.exif)
    implementation(libs.media3.player)
    implementation(libs.media3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(project(":engine"))
    implementation(project(":protocol-icloud-web"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sentry.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
