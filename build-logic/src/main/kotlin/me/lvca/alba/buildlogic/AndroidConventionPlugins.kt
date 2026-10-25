package me.lvca.alba.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        configureStaticAnalysis()
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            configureDeviceTests(this)
            configureJvmTests(this)
            defaultConfig.targetSdk = TARGET_SDK
            lint.abortOnError = true
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        configureStaticAnalysis()
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            configureDeviceTests(this)
            configureJvmTests(this)
            lint.abortOnError = true
        }
    }
}

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        configureStaticAnalysis()
        extensions.configure<TestExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = TARGET_SDK
        }
    }
}
