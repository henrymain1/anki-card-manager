package com.borderless.ankicards.ui.common

import android.media.MediaPlayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Tap-to-play button for one card field's audio bytes.
 *
 * Writes the MP3 to a per-content-hash cache file so the same bytes don't
 * spawn duplicate files across recomposes, plays through a [MediaPlayer], and
 * brightens + scales up while playing so the user has a clear "yes, it's
 * playing" signal. The MediaPlayer is released on completion and on the
 * composable leaving composition.
 *
 * Lifted from the legacy CardPreview's inner AudioPlayButton — kept the
 * MediaPlayer-by-cache-file approach because it's robust to large blobs that
 * wouldn't fit in a Binder transaction.
 *
 * @param bytes      The MP3 payload to play.
 * @param tint       Icon color. Defaults to Material's primary.
 * @param background Optional background tint behind the icon. Defaults to a
 *                   faint version of [tint].
 * @param sizeDp     Outer diameter of the circular button.
 */
@Composable
fun AudioPlayButton(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    background: Color = tint.copy(alpha = 0.15f),
    sizeDp: Int = 44
) {
    val context = LocalContext.current
    val cacheFile = remember(bytes) {
        File(context.cacheDir, "audio-${bytes.contentHashCode()}.mp3").also {
            if (!it.exists()) it.writeBytes(bytes)
        }
    }
    val player = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
        }
    }

    // Subtle scale + background brightness animation while audio plays.
    val playingScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.08f else 1.0f,
        label = "audio-playing-scale"
    )
    val activeBackground = if (isPlaying) tint.copy(alpha = 0.30f) else background

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = {
                runCatching {
                    player.reset()
                    player.setDataSource(cacheFile.absolutePath)
                    player.setOnCompletionListener {
                        it.reset()
                        isPlaying = false
                    }
                    player.setOnErrorListener { _, _, _ ->
                        isPlaying = false
                        true
                    }
                    player.prepare()
                    player.start()
                    isPlaying = true
                }.onFailure { isPlaying = false }
            },
            modifier = Modifier
                .size(sizeDp.dp)
                .scale(playingScale)
                .clip(CircleShape)
                .background(activeBackground)
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "Play audio",
                tint = tint
            )
        }
    }
}
