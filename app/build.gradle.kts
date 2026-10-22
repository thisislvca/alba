import java.util.Properties

plugins {
    alias(libs.plugins.alba.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sentry)
}

// Release builds stay unsigned. Only the isolated release job receives the upload key.
val sentryPropertiesFile = file(providers.environmentVariable("ALBA_SENTRY_PROPERTIES")
    .getOrElse("${System.getProperty("user.home")}/.config/alba/sentry/sentry.properties"))
val sentryProperties = Properties()
if (sentryPropertiesFile.isFile) {
    sentryProperties.load(providers.fileContents(layout.file(provider { sentryPropertiesFile })).asText.get().reader())
}
val sentryDsn = providers.environmentVariable("ALBA_SENTRY_DSN")
    .getOrElse(sentryProperties.getProperty("dsn", "")).trim()
val sentryDsnLiteral = "\"" + sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

val releaseVersionName = providers.gradleProperty("albaVersionName")
val releaseVersionCode = providers.gradleProperty("albaVersionCode")
require(releaseVersionName.isPresent == releaseVersionCode.isPresent) { "Supply both albaVersionName and albaVersionCode." }
val appVersionName = releaseVersionName.getOrElse("0.1.0")
val appVersionCode = releaseVersionCode.getOrElse("1").toInt()
require(appVersionName.matches(Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"))) { "Invalid Alba version name." }
require(appVersionCode in 1..2100000000) { "Invalid Alba version code." }

android {
    namespace = "me.lvca.alba.app"

    defaultConfig {
        buildConfigField("boolean", "BENCHMARK_MODE", "false")
        applicationId = "me.lvca.alba"
        // Prepares the emulator before each test; see AlbaTestRunner.
        testInstrumentationRunner = "me.lvca.alba.app.testing.AlbaTestRunner"
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("en", "it")
        buildConfigField(
            "String",
            "SENTRY_DSN",
            sentryDsnLiteral,
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"development\"")
        }
        release {
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"${providers.environmentVariable("ALBA_RELEASE_ENVIRONMENT").getOrElse("family-beta")}\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        checkReleaseBuilds = true
    }
}

sentry {
    ignoredBuildTypes.set(setOf("benchmark", "profile"))
    org.set(sentryProperties.getProperty("org", "mannaworks"))
    projectName.set(sentryProperties.getProperty("project", "alba-android"))
    sentryProperties.getProperty("authToken")?.takeIf(String::isNotBlank)?.let(authToken::set)
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(!sentryProperties.getProperty("authToken").isNullOrBlank())
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    tracingInstrumentation { enabled.set(false) }
    autoInstallation { enabled.set(false) }
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
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.services.storage)
    // Compose's test rules inject input through Espresso; pin a release that supports current Android versions.
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
