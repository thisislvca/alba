package me.lvca.alba.protocol.network

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import java.io.InputStream

/**
 * One client's view of the shared transport: it accumulates the session headers of its own
 * responses, so a concurrent client on the same transport cannot change what it reads.
 */
internal class AppleSessionHeaderTracker(
    private val transport: AppleHttpTransport,
) : AppleHttpTransport by transport {
    @Volatile
    var headers = AppleSessionHeaders()
        private set

    override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse =
        transport.execute(request).also(::record)

    override suspend fun executeRawUpload(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        openBodyOnce: () -> InputStream,
    ): AppleHttpResponse = transport.executeRawUpload(url, headers, contentLength, openBodyOnce).also(::record)

    override fun restore(snapshot: AppleSessionSnapshot) {
        transport.restore(snapshot)
        headers = AppleSessionHeaders.of(snapshot)
    }

    override fun clear() {
        transport.clear()
        headers = AppleSessionHeaders()
    }

    @Synchronized
    private fun record(response: AppleHttpResponse) {
        headers = headers.updatedBy(response.sessionHeaders)
    }
}
