package me.lvca.alba.protocol.network

import okhttp3.HttpUrl

object AppleClientProfile {
    const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"

    const val FD_CLIENT_INFO = "{\"U\":\"$USER_AGENT\",\"L\":\"en-US\",\"Z\":\"GMT+00:00\"," +
        "\"V\":\"1.1\",\"F\":\"\"}"

    /** Build identifiers iCloud.com reports to the setup, account and CloudKit services. */
    const val WEB_CLIENT_BUILD = "2534Project66"
    const val WEB_CLIENT_MASTERING = "2534B22"

    /** iCloud.com's photo upload service reports a newer build of its own. */
    const val UPLOAD_CLIENT_BUILD = "2634Build25"
    const val UPLOAD_CLIENT_MASTERING = "2634Build25"

    internal fun HttpUrl.Builder.addWebClientBuild(): HttpUrl.Builder = this
        .addQueryParameter("clientBuildNumber", WEB_CLIENT_BUILD)
        .addQueryParameter("clientMasteringNumber", WEB_CLIENT_MASTERING)

    internal fun HttpUrl.Builder.addUploadClientBuild(): HttpUrl.Builder = this
        .addQueryParameter("clientBuildNumber", UPLOAD_CLIENT_BUILD)
        .addQueryParameter("clientMasteringNumber", UPLOAD_CLIENT_MASTERING)
}
