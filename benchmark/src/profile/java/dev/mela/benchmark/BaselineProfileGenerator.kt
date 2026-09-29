package dev.mela.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun gallery() = profile.collect(packageName = TARGET) {
        pressHome()
        startActivityAndWait()
        browseGallery()
    }
}
