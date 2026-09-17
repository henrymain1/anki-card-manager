package com.borderless.ankicards.ui.common

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.borderless.ankicards.domain.model.Card as CardModel

/**
 * Card preview that mirrors the desktop project's `.anki-card` styling so the
 * preview reads the same way as a card studied inside AnkiDroid.
 *
 * Editing model (mobile-equivalent of the desktop's hover-to-reveal):
 *  - Every text element is a [BasicTextField] with no border, no label, no
 *    background — it just renders as styled text.
 *  - Tapping the text places the cursor and opens the keyboard. Tapping outside
 *    blurs and the cursor disappears, leaving the styled text intact.
 *  - Empty fields show a faint placeholder so they remain tappable.
 *
 * If [onCardChanged] is null the fields render as plain Text (read-only).
 */
@Composable
fun CardPreview(
    card: CardModel,
    isLoadingImage: Boolean = false,
    onCardChanged: ((CardModel) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = AnkiCardPalette

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardBackground)
    ) {
        // Top accent stripe (matches desktop: linear-gradient(90deg, accent, #a78bfa))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.accent, palette.purple)
                    )
                )
                .align(Alignment.TopCenter)
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardImageSlot(bytes = card.imageBytes, isLoading = isLoadingImage)

            // English headword
            CardEditableLine(
                value = card.front,
                onChange = { v -> onCardChanged?.invoke(card.copy(front = v)) },
                editable = onCardChanged != null,
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center
                ),
                placeholder = "English word"
            )

            Divider(palette.border)

            // Chinese characters — big bold purple, with shadow approximating the
            // desktop's layered text-shadow.
            CardEditableLine(
                value = card.chinese,
                onChange = { v -> onCardChanged?.invoke(card.copy(chinese = v)) },
                editable = onCardChanged != null,
                style = TextStyle(
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = palette.purple,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = palette.purpleShadow,
                        offset = androidx.compose.ui.geometry.Offset(3f, 3f),
                        blurRadius = 8f
                    )
                ),
                placeholder = "中文"
            )

            // Jyutping in monospace, muted
            CardEditableLine(
                value = card.jyutping,
                onChange = { v -> onCardChanged?.invoke(card.copy(jyutping = v)) },
                editable = onCardChanged != null,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = palette.textMuted,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center
                ),
                placeholder = "jyutping"
            )

            // TTS play button — appears only once audio has been generated.
            card.audioBytes?.let { bytes ->
                AudioPlayButton(bytes = bytes, palette = palette)
            }

            // Measure word — amber pill
            MeasureWordPill(
                value = card.measureWord,
                onChange = { v -> onCardChanged?.invoke(card.copy(measureWord = v)) },
                editable = onCardChanged != null,
                palette = palette
            )

            ExampleBox(
                en = card.exampleEnglish,
                jp = card.exampleJyutping,
                cn = card.exampleChinese,
                editable = onCardChanged != null,
                onEnChange = { onCardChanged?.invoke(card.copy(exampleEnglish = it)) },
                onJpChange = { onCardChanged?.invoke(card.copy(exampleJyutping = it)) },
                onCnChange = { onCardChanged?.invoke(card.copy(exampleChinese = it)) },
                palette = palette
            )
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────────────

@Composable
private fun Divider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

@Composable
private fun CardEditableLine(
    value: String,
    onChange: (String) -> Unit,
    editable: Boolean,
    style: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    if (!editable) {
        Text(
            text = value.ifBlank { "" },
            style = style,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = style,
            cursorBrush = SolidColor(style.color),
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = style.copy(color = style.color.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MeasureWordPill(
    value: String,
    onChange: (String) -> Unit,
    editable: Boolean,
    palette: AnkiCardPaletteValues
) {
    val style = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.amber,
        textAlign = TextAlign.Center
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(palette.amberSubtle)
            .border(1.dp, palette.amberBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        if (!editable) {
            Text(value.ifBlank { "MW" }, style = style.copy(
                color = if (value.isBlank()) palette.textMuted.copy(alpha = 0.4f) else palette.amber
            ))
        } else {
            Box {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = style,
                    cursorBrush = SolidColor(palette.amber)
                )
                if (value.isEmpty()) {
                    Text(
                        "MW",
                        style = style.copy(color = palette.textMuted.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ExampleBox(
    en: String,
    jp: String,
    cn: String,
    editable: Boolean,
    onEnChange: (String) -> Unit,
    onJpChange: (String) -> Unit,
    onCnChange: (String) -> Unit,
    palette: AnkiCardPaletteValues
) {
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.textMuted,
        letterSpacing = 0.8.sp
    )
    val enStyle = TextStyle(fontSize = 14.sp, color = palette.textPrimary)
    val jpStyle = TextStyle(
        fontSize = 12.5.sp,
        fontFamily = FontFamily.Monospace,
        color = palette.textMuted
    )
    val cnStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = palette.purple
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.exampleBoxBackground)
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("EXAMPLE", style = labelStyle)
        CardEditableLine(en, onEnChange, editable, enStyle, "English example",
            modifier = Modifier.fillMaxWidth())
        CardEditableLine(jp, onJpChange, editable, jpStyle, "Jyutping example",
            modifier = Modifier.fillMaxWidth())
        CardEditableLine(cn, onCnChange, editable, cnStyle, "中文例句",
            modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Tap-to-play speaker button for the card's Cantonese audio.
 *
 * Writes the MP3 bytes to a per-card cache file (named by content hash so
 * different cards don't fight over the same file), then plays via MediaPlayer.
 * MediaPlayer is released on completion / error / composable disposal.
 */
@Composable
private fun AudioPlayButton(bytes: ByteArray, palette: AnkiCardPaletteValues) {
    val context = LocalContext.current
    val cacheFile = remember(bytes) {
        File(context.cacheDir, "preview-tts-${bytes.contentHashCode()}.mp3").also {
            if (!it.exists()) it.writeBytes(bytes)
        }
    }
    val player = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    IconButton(
        onClick = {
            runCatching {
                player.reset()
                player.setDataSource(cacheFile.absolutePath)
                player.setOnCompletionListener { it.reset() }
                player.prepare()
                player.start()
            }
        },
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(palette.purple.copy(alpha = 0.15f))
    ) {
        Icon(
            imageVector = Icons.Filled.VolumeUp,
            contentDescription = "Play Cantonese audio",
            tint = palette.purple
        )
    }
}

@Composable
private fun CardImageSlot(bytes: ByteArray?, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1024f / 545f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            bytes != null -> AsyncImage(
                model = bytes,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            isLoading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = AnkiCardPalette.purple
                )
                Text(
                    "Generating image…",
                    color = AnkiCardPalette.textMuted,
                    style = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
            }
            else -> Text(
                "No image",
                color = AnkiCardPalette.textMuted.copy(alpha = 0.5f),
                style = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
        }
    }
}

// ── Palette ─────────────────────────────────────────────────────────────────

/**
 * Hard-coded values mirroring desktop project's CSS variables. We don't tie
 * these to MaterialTheme because the card always uses its own dark theme
 * regardless of the surrounding app theme — same as in AnkiDroid.
 */
private data class AnkiCardPaletteValues(
    val cardBackground: Color = Color(0xFF1E1E2A),
    val accent: Color = Color(0xFF6E56CF),
    val purple: Color = Color(0xFFA78BFA),
    val purpleShadow: Color = Color(0xFF5B3EC4),
    val amber: Color = Color(0xFFFBBF24),
    val amberSubtle: Color = Color(0x33FBBF24),
    val amberBorder: Color = Color(0x66FBBF24),
    val border: Color = Color(0x33FFFFFF),
    val textPrimary: Color = Color(0xFFE6E6F0),
    val textMuted: Color = Color(0xFF8B8BA0),
    val exampleBoxBackground: Color = Color(0x40000000)
)

private val AnkiCardPalette = AnkiCardPaletteValues()

// Suppress unused-warning for the SpanStyle import while we evolve this file.
@Suppress("unused")
private val _spanStyleKeepImport: SpanStyle? = null
