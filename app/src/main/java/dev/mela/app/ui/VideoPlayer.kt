package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import dev.mela.app.R
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.mela.engine.source.MediaRead
import kotlinx.coroutines.runBlocking

typealias PlaybackReader = suspend (String, Long, Long) -> MediaRead

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LibraryDataSource(private val id: String, private val reader: PlaybackReader) : BaseDataSource(true) {
    private var handle: MediaRead? = null
    private var uri: Uri? = null
    private var remaining = -1L
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val opened = runBlocking { reader(id, dataSpec.position, dataSpec.length) }
        handle = opened; uri = dataSpec.uri
        remaining = if (dataSpec.length >= 0) dataSpec.length else opened.length
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = requireNotNull(handle).input.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        if (count > 0) { if (remaining >= 0) remaining -= count; bytesTransferred(count) }
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() { if (handle != null) { handle?.close(); handle = null; uri = null; transferEnded() } }
}

private class PlaybackMemory(position: Long, playing: Boolean) {
    var position by mutableLongStateOf(position)
    var playing by mutableStateOf(playing)
    var suspended = false
    var player: ExoPlayer? = null

    fun capture() {
        player?.let {
            position = it.currentPosition.coerceAtLeast(0)
            if (!suspended) playing = it.playWhenReady
        }
    }

    companion object {
        val Saver = listSaver<PlaybackMemory, Any>(
            save = { it.capture(); listOf(it.position, it.playing) },
            restore = { PlaybackMemory(it[0] as Long, it[1] as Boolean) },
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(id: String, reader: PlaybackReader, modifier: Modifier = Modifier, autoPlay: Boolean = false, localReference: String? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(id) { mutableStateOf<Int?>(null) }
    var state by remember(id) { mutableStateOf(R.string.loading) }
    var videoAspect by remember(id) { mutableFloatStateOf(16f / 9f) }
    val memory = rememberSaveable(id, saver = PlaybackMemory.Saver) { PlaybackMemory(0, autoPlay) }
    val currentReader by rememberUpdatedState(reader)
    val player = remember(id, localReference) { ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
        if (localReference != null) setMediaItem(MediaItem.fromUri(localReference))
        else setMediaSource(ProgressiveMediaSource.Factory { LibraryDataSource(id) { media, start, count -> currentReader(media, start, count) } }
            .createMediaSource(MediaItem.fromUri("mela://playback")))
        addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onEvents(player: Player, events: Player.Events) {
                memory.capture()
                state = when (player.playbackState) {
                Player.STATE_READY -> if (player.isPlaying) R.string.playing else R.string.ready
                Player.STATE_ENDED -> R.string.ended
                else -> R.string.loading
            } }
            override fun onPlayerError(exception: PlaybackException) {
                error = if (exception.errorCode in 4000..4999) R.string.this_device_cannot_decode_this_video_format else R.string.video_playback_failed
            }
        })
        seekTo(memory.position)
        playWhenReady = memory.playing
        memory.player = this
        prepare()
    } }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                memory.capture()
                memory.suspended = true
                player.pause()
            } else if (event == Lifecycle.Event.ON_START && memory.suspended) {
                memory.suspended = false
                player.playWhenReady = memory.playing
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            if (memory.player === player) { memory.capture(); memory.player = null }
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    val playbackState = stringResource(state)
    BoxWithConstraints(modifier.testTag("video-player").semantics { stateDescription = playbackState },
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * videoAspect)
        AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player; it.keepScreenOn = state == R.string.playing }, modifier = Modifier.size(width, width / videoAspect))
        error?.let { message ->
            Surface(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp)) {
                Column(Modifier.padding(12.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(stringResource(message))
                    FilledTonalButton(onClick = {
                        memory.capture(); error = null
                        player.seekTo(memory.position)
                        player.prepare()
                        player.playWhenReady = memory.playing
                    }, modifier = Modifier.testTag("retry-video")) { Text(stringResource(R.string.retry_video)) }
                }
            }
        }
    }
}
