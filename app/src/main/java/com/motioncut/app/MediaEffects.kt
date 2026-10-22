package com.motioncut.app

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.exoplayer.audio.SonicAudioProcessor
import androidx.media3.transformer.Effects

/*
 * The one place that turns EditSettings into Media3 objects. The preview player
 * and the exporter both call buildFrameEffects, so rotate, flip and crop
 * cannot drift apart between them. Speed and volume are previewed with the
 * player's own speed and volume and exported with the processors below.
 */

/**
 * Frame effects in a fixed order: flip, then rotate, then crop. Flip is a
 * mirror of the source frame; rotate is applied afterwards. Empty when the
 * frame is untouched, so the original render path is used.
 */
@OptIn(UnstableApi::class)
internal fun buildFrameEffects(settings: EditSettings): List<Effect> {
    val effects = mutableListOf<Effect>()

    if (settings.flipHorizontal) {
        effects.add(MatrixTransformation { Matrix().apply { postScale(-1f, 1f) } })
    }

    if (settings.rotationDegrees != 0) {
        effects.add(
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(settings.counterClockwiseDegrees.toFloat())
                .build()
        )
    }

    settings.aspect.ratio?.let { ratio ->
        effects.add(
            Presentation.createForAspectRatio(
                ratio,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        )
    }

    return effects
}

/**
 * Effects for the exporter: the frame effects plus speed and volume. Both
 * lists are empty when nothing is changed. When audio is muted the exporter
 * drops the audio track instead (see EditedMediaItem.Builder.setRemoveAudio),
 * so no audio processors are returned.
 */
@OptIn(UnstableApi::class)
internal fun buildExportEffects(settings: EditSettings): Effects {
    val videoEffects = buildFrameEffects(settings).toMutableList()
    val audioProcessors = mutableListOf<AudioProcessor>()

    if (settings.changesSpeed) {
        videoEffects.add(0, SpeedChangeEffect(settings.speed))

        if (!settings.muted) {
            audioProcessors.add(SonicAudioProcessor().apply { setSpeed(settings.speed) })
        }
    }

    if (!settings.muted && settings.volume < 1f) {
        audioProcessors.add(volumeProcessor(settings.volume))
    }

    return Effects(audioProcessors, videoEffects)
}

/** Scales mono and stereo audio. Sources with more channels are not supported. */
@OptIn(UnstableApi::class)
private fun volumeProcessor(volume: Float): ChannelMixingAudioProcessor =
    ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix(1, 1, floatArrayOf(volume)))
        putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(volume, 0f, 0f, volume)))
    }
