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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Locale

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayer(
    id: String,
    reader: PlaybackReader,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    localReference: String? = null,
    onPlayingChanged: (Boolean) -> Unit = {},
    optionsOpen: Boolean = false,
    onDismissOptions: () -> Unit = {},
    loopEnabled: Boolean = true,
    showPlaybackControls: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(id) { mutableStateOf<Int?>(null) }
    var state by remember(id) { mutableStateOf(R.string.loading) }
    var videoAspect by remember(id) { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember(id) { mutableStateOf(false) }
    var position by remember(id) { mutableLongStateOf(0L) }
    var duration by remember(id) { mutableLongStateOf(0L) }
    var scrubbing by remember(id) { mutableStateOf(false) }
    var scrubPosition by remember(id) { mutableLongStateOf(0L) }
    var resumeAfterSeek by remember(id) { mutableStateOf(false) }
    var overlayVisible by remember(id) { mutableStateOf(false) }
    var muted by rememberSaveable(id) { mutableStateOf(false) }
    var speed by rememberSaveable(id) { mutableFloatStateOf(1f) }
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
                isPlaying = player.isPlaying
                duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
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
        repeatMode = if (loopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        setPlaybackSpeed(speed)
        volume = if (muted) 0f else 1f
        memory.player = this
        prepare()
    } }
    LaunchedEffect(isPlaying) { onPlayingChanged(isPlaying) }
    LaunchedEffect(loopEnabled) { player.repeatMode = if (loopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(showPlaybackControls) {
        if (!showPlaybackControls) { player.pause(); overlayVisible = false }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) position = player.currentPosition.coerceAtLeast(0L)
            delay(100)
        }
    }
    LaunchedEffect(isPlaying, overlayVisible) {
        if (isPlaying && overlayVisible) {
            delay(3_000)
            overlayVisible = false
        }
    }
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
        contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * videoAspect)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val controlsOnVideo = (maxHeight + width / videoAspect) / 2 > maxHeight - navBottom - 163.dp
        AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = player } },
            update = { it.player = player; it.keepScreenOn = state == R.string.playing }, modifier = Modifier.size(width, width / videoAspect))
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { overlayVisible = !overlayVisible })
        if (showPlaybackControls && (!isPlaying || overlayVisible || scrubbing)) {
            val darkControls = isPlaying || controlsOnVideo
            val ink = if (darkControls) Color(0xFFF4F4F4) else Color(0xFF30323A)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = navBottom + 76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause() else { overlayVisible = true; player.play() }
                    }, modifier = Modifier.size(48.dp).testTag("video-play-pause")) {
                        Icon(if (isPlaying) MelaIcons.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (isPlaying) R.string.pause_video else R.string.play_video), tint = ink)
                    }
                    Text("${videoTime(if (scrubbing) scrubPosition else position)} / ${videoTime(duration)}",
                        Modifier.weight(1f), textAlign = TextAlign.Center, color = ink,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f },
                        modifier = Modifier.size(48.dp).testTag("video-mute")) {
                        Icon(if (muted) MelaIcons.VolumeOff else MelaIcons.VolumeUp,
                            stringResource(if (muted) R.string.unmute_video else R.string.mute_video), tint = ink)
                    }
                }
                Spacer(Modifier.height(15.dp))
                VideoTimeline(
                    position = if (scrubbing) scrubPosition else position,
                    duration = duration,
                    darkCanvas = darkControls,
                    onSeekStart = {
                        resumeAfterSeek = player.isPlaying
                        scrubbing = true; scrubPosition = position; player.pause(); overlayVisible = true
                    },
                    onSeek = { next -> scrubPosition = next; player.seekTo(next) },
                    onSeekFinished = {
                        scrubbing = false
                        if (resumeAfterSeek) player.play()
                        resumeAfterSeek = false
                    },
                )
            }
        }
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
    if (optionsOpen) ModalBottomSheet(onDismissRequest = onDismissOptions, containerColor = Color(0xFFEDEDF6),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.TopCenter) {
                Surface(Modifier.padding(top = 8.dp).size(32.dp, 4.dp),
                    shape = RoundedCornerShape(100), color = Color(0xFFB5B7C1)) {}
            }
        }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.playback_speed), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(.25f, .5f, 1f, 1.5f, 2f).forEach { value ->
                    Surface(onClick = { speed = value; player.setPlaybackSpeed(value); onDismissOptions() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp), color = if (speed == value) Color(0xFFDDE4FA) else Color.White) {
                        Box(contentAlignment = Alignment.Center) {
                            val label = when (value) { .25f -> "0.25X"; .5f -> "0.5X"; 1f -> "1X"; 1.5f -> "1.5X"; else -> "2X" }
                            Text(if (speed == value) "✓ $label" else label,
                                color = Color(0xFF30323A), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

private fun videoTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) / 1_000L).toInt()
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun VideoTimeline(
    position: Long,
    duration: Long,
    darkCanvas: Boolean,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val played = if (darkCanvas) Color(0xFFF4F4F4) else Color(0xFF30323A)
    val remaining = if (darkCanvas) Color(0xFF929292) else Color(0xFF838489)
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(48.dp)
        .testTag("video-timeline")
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { value ->
                if (duration <= 0L) false else { onSeekStart(); onSeek((duration * value.coerceIn(0f, 1f)).toLong()); onSeekFinished(); true }
            }
        }
        .pointerInput(duration) {
            detectTapGestures { tap ->
                if (duration > 0L) {
                    onSeekStart(); onSeek((duration * (tap.x / size.width).coerceIn(0f, 1f)).toLong()); onSeekFinished()
                }
            }
        }
        .pointerInput(duration) {
            detectDragGestures(onDragStart = { onSeekStart() }, onDragEnd = onSeekFinished, onDragCancel = onSeekFinished) { change, _ ->
                if (duration > 0L) onSeek((duration * (change.position.x / size.width).coerceIn(0f, 1f)).toLong())
                change.consume()
            }
        }) {
        val y = size.height / 2f
        val bar = 8.dp.toPx()
        val x = size.width * fraction
        val gap = 7.dp.toPx()
        if (x > gap) drawLine(played, start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(x - gap, y), strokeWidth = bar, cap = StrokeCap.Round)
        if (x < size.width - gap) drawLine(remaining, start = androidx.compose.ui.geometry.Offset(x + gap, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = bar, cap = StrokeCap.Round)
        drawLine(played, start = androidx.compose.ui.geometry.Offset(x, y - 22.dp.toPx()), end = androidx.compose.ui.geometry.Offset(x, y + 22.dp.toPx()),
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    }
}
