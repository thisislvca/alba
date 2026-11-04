package me.lvca.alba.protocol.livecontract

import me.lvca.alba.protocol.account.AppleSessionCodec
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * The real iCloud session the live contract test uses. It is never committed and never part of a normal test run:
 *
 * - `ALBA_LIVE_SESSION` holds the session itself, as written by `liveSignIn` or that file base64-encoded (for a CI
 *   secret), or
 * - `ALBA_LIVE_SESSION_FILE` names a session file, or
 * - the default file, `.live/icloud-session.json` at the repository root, which `.gitignore` excludes.
 *
 * `./gradlew :protocol-icloud-web:liveSignIn` signs in once, interactively, and writes the default file.
 */
internal object LiveSession {
    const val SESSION_VARIABLE = "ALBA_LIVE_SESSION"
    const val FILE_VARIABLE = "ALBA_LIVE_SESSION_FILE"
    private const val DEFAULT_PATH = ".live/icloud-session.json"

    /** A session and, when it came from a file, the file a renewed session is saved back to. */
    class Loaded(val snapshot: AppleSessionSnapshot, val file: File?)

    fun load(): Loaded? {
        System.getenv(SESSION_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            val json = if (value.startsWith("{")) value else String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            return Loaded(AppleSessionCodec.decode(json), file = null)
        }
        val file = sessionFile()
        if (!file.isFile) return null
        return Loaded(AppleSessionCodec.decode(file.readText()), file)
    }

    /** The session file: `ALBA_LIVE_SESSION_FILE`, else `.live/icloud-session.json` in the repository. */
    fun sessionFile(): File = System.getenv(FILE_VARIABLE)?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(repositoryRoot(), DEFAULT_PATH)

    /** Writes [snapshot] to [file], readable only by its owner. */
    fun save(file: File, snapshot: AppleSessionSnapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText("")
        runCatching { Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------")) }
        temporary.writeText(AppleSessionCodec.encode(snapshot))
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not save the session to $file" }
        }
    }

    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File(System.getProperty("user.dir"))
}
