/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Mirrors Media3's default `silence skipping -> Sonic` chain, replacing the Sonic slot with
 * [FallbackTimeStretchAudioProcessor]. Playback parameters and time accounting follow the default
 * chain contract.
 */
@OptIn(UnstableApi::class)
internal class WsolaAudioProcessorChain : AudioProcessorChain {
    private val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor()
    private val timeStretchProcessor = FallbackTimeStretchAudioProcessor()
    private val audioProcessors: Array<AudioProcessor> =
        arrayOf(silenceSkippingAudioProcessor, timeStretchProcessor)

    val timeStretchBackend: FallbackTimeStretchAudioProcessor.Backend
        get() = timeStretchProcessor.backend

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        timeStretchProcessor.setSpeed(playbackParameters.speed)
        timeStretchProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDurationUs: Long): Long {
        return if (timeStretchProcessor.isActive) {
            timeStretchProcessor.getMediaDuration(playoutDurationUs)
        } else {
            playoutDurationUs
        }
    }

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.skippedFrames
}

/**
 * The renderers factory Mediamp installs on its ExoPlayer.
 *
 * It exists for two reasons:
 *  - it installs [WsolaAudioProcessorChain] on the audio sink, replacing Media3's default
 *    Sonic-based time stretcher, and
 *  - it adds [FlacAudioRenderer], the bundled FLAC software decoder, so FLAC does not have to go
 *    through a platform decoder that may be unable to decode 24-bit streams.
 *
 * Both features share one audio sink on purpose: Media3 hands the renderers of a factory the same
 * sink, and building it here keeps the WSOLA chain in effect for the software FLAC path too.
 *
 * [mediaCodecSelector] is forwarded to Media3 and only affects the platform path.
 */
@OptIn(UnstableApi::class)
internal class WsolaRenderersFactory(
    private val context: Context,
    mediaCodecSelector: MediaCodecSelector? = null,
) : DefaultRenderersFactory(context) {
    /** The most recently installed chain, exposed for diagnostics and tests. */
    var audioProcessorChain: WsolaAudioProcessorChain? = null
        private set

    /** Built once and shared by every audio renderer; see the class comment. */
    private val audioSink: AudioSink by lazy {
        val chain = WsolaAudioProcessorChain().also { audioProcessorChain = it }
        Log.i(TAG, "Installed WSOLA audio processor chain (fallback to Sonic if unavailable)")
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioProcessorChain(chain)
            .build()
    }

    init {
        // Preferring a software decoder cannot fix a format whose only decoder is a broken
        // hardware one unless the caller injects a selector that also falls back to the default
        // list; a selector that simply returns empty makes the renderer fail at init. That is the
        // caller's contract, see the ExoPlayerMediampPlayer constructor documentation.
        if (mediaCodecSelector != null) {
            setMediaCodecSelector(mediaCodecSelector)
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        FlacAudioRenderer.addTo(
            renderers = out,
            context = context,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink,
        )
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = audioSink

    private companion object {
        private const val TAG = "WsolaRenderersFactory"
    }
}

