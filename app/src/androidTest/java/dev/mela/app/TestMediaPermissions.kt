package dev.mela.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

internal fun grantTestMediaPermissions(context: Context) {
    val permissions = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    permissions.forEach { automation.grantRuntimePermission(context.packageName, it) }
}
