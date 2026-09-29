package dev.mela.protocol.network

object AppleClientProfile {
    const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"

    const val FD_CLIENT_INFO = "{\"U\":\"$USER_AGENT\",\"L\":\"en-US\",\"Z\":\"GMT+00:00\"," +
        "\"V\":\"1.1\",\"F\":\"\"}"
}
