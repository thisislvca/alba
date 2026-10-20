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
