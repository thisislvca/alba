plugins {
    `kotlin-dsl`
}

dependencies {
    // The root build puts these plugins on the classpath; compile against them only.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "alba.android.application"
            implementationClass = "me.lvca.alba.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "alba.android.library"
            implementationClass = "me.lvca.alba.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidTest") {
            id = "alba.android.test"
            implementationClass = "me.lvca.alba.buildlogic.AndroidTestConventionPlugin"
        }
    }
}
