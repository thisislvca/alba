package me.lvca.alba.protocol.fixture

/** The demo library's personal media ids start with this. */
private const val DEMO_MEDIA_PREFIX = "fixture:"

/** The demo library's shared albums, and the media and posts inside them, have ids that start with this. */
private const val DEMO_SHARED_PREFIX = "shared:demo-library:"

/** Whether [id] names media, a shared album or shared media that exists only in the demo library. */
fun isDemoId(id: String): Boolean = id.startsWith(DEMO_MEDIA_PREFIX) || isDemoSharedId(id)

/** Whether [id] names a demo shared album or something inside one. */
fun isDemoSharedId(id: String): Boolean = id.startsWith(DEMO_SHARED_PREFIX)
