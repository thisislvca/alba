package me.lvca.alba.app.testing

/**
 * Marks a test whose job is to capture store or README artwork rather than to check behaviour. [AlbaTestRunner] leaves
 * these tests out unless the run selects them with the instrumentation argument
 * `annotation=me.lvca.alba.app.testing.ScreenshotCapture`; see tools/store-screenshots/README.md.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ScreenshotCapture
