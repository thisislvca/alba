package me.lvca.alba.app.testing

/**
 * Marks a device test in the fast subset for local runs: one per area of device-only behaviour (video decoding,
 * MediaStore and the system trash dialog, intents, WorkManager and job scheduling, real rendering and system bars), so
 * a change can be checked on an emulator in a few minutes. Select it with the instrumentation argument
 * `annotation=me.lvca.alba.app.testing.Smoke`.
 *
 * CI does not rely on it for the target device: pull requests, pushes to `main` and the nightly run all run every
 * device test on the Pixel 9 at the target API level. The nightly and manual runs also run this subset on the minimum
 * SDK and a compact phone.
 *
 * Behaviour that needs no device belongs in a JVM test under `app/src/test` instead, whole-app journeys included
 * (`AlbaAppRule`).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Smoke
