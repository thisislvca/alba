package me.lvca.alba.app.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause
import me.lvca.alba.engine.model.ProtocolStatusGate

/**
 * The remote protocol status for the live iCloud account only. The demo writes nothing to Apple, so it is never
 * paused and never fetches the status document.
 */
internal class LiveProtocolStatus(
    private val remote: ProtocolStatusGate,
    demo: Flow<Boolean>,
    private val isDemo: () -> Boolean,
) : ProtocolStatusGate {
    override val pauses: Flow<List<ProtocolPause>> = combine(remote.pauses, demo) { pauses, inDemo -> if (inDemo) emptyList() else pauses }

    override suspend fun pauseFor(area: ProtocolArea): ProtocolPause? = if (isDemo()) null else remote.pauseFor(area)
}
