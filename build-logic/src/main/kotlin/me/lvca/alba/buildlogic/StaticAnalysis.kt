package me.lvca.alba.buildlogic

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * ktlint (rules in `.editorconfig`) and detekt (`config/detekt/detekt.yml`) for every module.
 * The code is ktlint-clean. Existing detekt findings are recorded per module in `detekt-baseline.xml`,
 * whose entries match code by content, so they survive lines moving.
 */
internal fun Project.configureStaticAnalysis() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        // Every source set, including test, androidTest and build-type sources.
        source.setFrom("src")
        parallel = true
    }

    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(libs.findVersion("ktlint").get().requiredVersion)
    }
}
