package me.lvca.alba.app.testing

/**
 * Marks a device test that pull requests run: one that checks behaviour only a real device shows (video decoding,
 * MediaStore and the system trash dialog, intents, WorkManager and job scheduling, real rendering and system bars) or
 * a short journey through the whole app. CI selects these with the instrumentation argument
 * `annotation=me.lvca.alba.app.testing.Smoke`; pushes to `main`, the nightly run and local runs run every device test.
 *
 * Behaviour a screen shows from its state belongs in a JVM test under `app/src/test` instead.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Smoke
