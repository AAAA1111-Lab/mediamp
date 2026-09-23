/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how container samples are reassembled into FLAC frames.
 *
 * This is the part that made 24-bit FLAC fail on device: a Matroska block is not frame aligned
 * (measured on a real release: 161 blocks carried about 118 frames), so the decoder has to buffer
 * whatever bytes arrive and only decode whole frames.
 *
 * A frame's length is known from where the next frame starts, so the last frame of a stream can
 * only be recognised once no more bytes can arrive — that is what [FlacFrameAccumulator.endOfInput]
 * expresses and what several of these tests rely on.
 */
class FlacFrameAccumulatorTest {
    @Test
    fun `reassembles frames split across arbitrary chunk boundaries`() {
        val frames = listOf(buildFrame(blockSize = 16), buildFrame(blockSize = 32), buildFrame(blockSize = 8))
        val stream = frames.reduce { acc, frame -> acc + frame }

        // Three bytes at a time is an arbitrary, unaligned chunking.
        val accumulator = FlacFrameAccumulator()
        val recovered = drainInChunks(accumulator, stream, chunkSize = 3)
        accumulator.endOfInput()
        recovered += drain(accumulator)

        assertEquals(frames.size, recovered.size)
        frames.zip(recovered).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun `yields every frame when one chunk carries several`() {
        val frames = listOf(buildFrame(blockSize = 16), buildFrame(blockSize = 24), buildFrame(blockSize = 16))
        val whole = frames.reduce { acc, frame -> acc + frame }

        val accumulator = FlacFrameAccumulator()
        accumulator.append(whole, 0, whole.size)

        // The first two frames are bounded by the one that follows them.
        assertContentEquals(frames[0], accumulator.nextFrame())
        assertContentEquals(frames[1], accumulator.nextFrame())
        assertNull(accumulator.nextFrame())

        accumulator.endOfInput()
        assertContentEquals(frames[2], accumulator.nextFrame())
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun `holds a partial frame until the rest arrives`() {
        val frame = buildFrame(blockSize = 64)
        val accumulator = FlacFrameAccumulator()

        // Everything but the last byte: no frame is available yet.
        accumulator.append(frame, 0, frame.size - 1)
        assertNull(accumulator.nextFrame())
        assertEquals(frame.size - 1, accumulator.bufferedBytes)

        accumulator.append(frame, frame.size - 1, 1)
        accumulator.endOfInput()
        assertContentEquals(frame, accumulator.nextFrame())
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun `the final frame needs the end of input to be recognised`() {
        val frame = buildFrame(blockSize = 32)
        val accumulator = FlacFrameAccumulator()
        accumulator.append(frame, 0, frame.size)

        // A frame could always be followed by more bytes, so nothing is emitted yet.
        assertNull(accumulator.nextFrame())

        accumulator.endOfInput()
        assertContentEquals(frame, accumulator.nextFrame())
    }

    @Test
    fun `skips garbage before the first header after a seek`() {
        val frame = buildFrame(blockSize = 32)
        val accumulator = FlacFrameAccumulator()

        // Leading bytes that cannot be a header, as if the stream were joined mid-frame.
        val leading = byteArrayOf(0x00, 0x11, 0x22, 0x33)
        val payload = leading + frame
        accumulator.append(payload, 0, payload.size)
        accumulator.endOfInput()

        // The garbage is reported as discarded — never handed out as a frame — and the real frame
        // follows intact.
        assertTrue(accumulator.nextFrame()!!.isEmpty())
        assertContentEquals(frame, accumulator.nextFrame())
    }

    @Test
    fun `reset discards a partial frame`() {
        val frame = buildFrame(blockSize = 32)
        val accumulator = FlacFrameAccumulator()
        accumulator.append(frame, 0, frame.size / 2)
        assertTrue(accumulator.bufferedBytes > 0)

        accumulator.reset()

        assertEquals(0, accumulator.bufferedBytes)
        accumulator.append(frame, 0, frame.size)
        accumulator.endOfInput()
        assertContentEquals(frame, accumulator.nextFrame())
    }

    @Test
    fun `ignores a sync code that is not a real header`() {
        // A body that starts with the sync bytes but whose header CRC does not match must not be
        // mistaken for a frame boundary, which is why the CRC is verified.
        val frame = buildFrame(blockSize = 32)
        val fake = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x18, 0x00, 0x1F, 0x00)
        val stream = frame + fake
        val accumulator = FlacFrameAccumulator()
        accumulator.append(stream, 0, stream.size)

        // `fake` does not start with a valid header, so it cannot bound the frame either.
        assertNull(accumulator.nextFrame())

        accumulator.endOfInput()
        assertContentEquals(stream, accumulator.nextFrame())
    }

    /**
     * The regression that stopped playback after a seek (auto-skip OP/ED, scrubbing).
     *
     * A seek rejoins the stream mid-frame. Re-aligning finds the next sync code, and a sync code
     * inside the compressed subframes that also carries a valid header CRC-8 is indistinguishable
     * from a header by that check alone — so it was accepted as a boundary, the frame was truncated,
     * and dr_flac produced no PCM, which failed the whole track on device.
     *
     * The preceding frame's CRC-16 rules it out. This pins the observable consequence: the frame is
     * never cut short at the false sync. Before the CRC-16 check the emitted frame ended *at* the
     * false sync, i.e. well short of the whole damaged region.
     */
    @Test
    fun `a false sync with a valid header CRC does not truncate the frame`() {
        val frame = buildFrame(blockSize = 64)
        val headerSize = frame.size - SUBFRAME_SIZE * CHANNELS - FOOTER_SIZE
        assertEquals(HEADER_SIZE, headerSize, "fixture header size must match the parser's view")

        val fake = falseSyncSequence()
        // The fixture must reproduce the failure mode: a sync code whose own header CRC-8 agrees.
        assertEquals(0, crc8(fake, 0, fake.size - 1) - (fake[fake.size - 1].toInt() and 0xFF))

        // Put the false sync inside the frame's body, after the first subframe.
        val insertAt = headerSize + SUBFRAME_SIZE
        val damaged = frame.copyOfRange(0, insertAt) + fake + frame.copyOfRange(insertAt, frame.size)

        val accumulator = FlacFrameAccumulator()
        accumulator.append(damaged, 0, damaged.size)
        accumulator.append(frame, 0, frame.size)

        val reported = generateSequence { accumulator.nextFrame() }.toMutableList()
        accumulator.endOfInput()
        generateSequence { accumulator.nextFrame() }.forEach { reported += it }

        assertTrue(reported.isNotEmpty(), "no frame was produced at all")
        assertTrue(
            reported.none { it.size < damaged.size },
            "output was truncated at the false sync: ${reported.map { it.size }}",
        )
    }

    /**
     * Before a seek the accumulator may already hold the tail of a frame it never saw the start of.
     * Those bytes are not decodable and must be reported as discarded, never emitted as a frame.
     */
    @Test
    fun `a mid-frame remainder is discarded rather than emitted`() {
        val frame = buildFrame(blockSize = 32)
        val accumulator = FlacFrameAccumulator()

        // The last two thirds of a frame, as after a seek into the middle of it.
        val tail = frame.copyOfRange(frame.size / 3, frame.size)
        accumulator.append(tail, 0, tail.size)
        accumulator.endOfInput()

        val first = accumulator.nextFrame()
        assertTrue(first == null || first.isEmpty(), "a frame tail was emitted as a frame")
    }

    private fun drainInChunks(
        accumulator: FlacFrameAccumulator,
        stream: ByteArray,
        chunkSize: Int,
    ): MutableList<ByteArray> {
        val recovered = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val length = minOf(chunkSize, stream.size - offset)
            accumulator.append(stream, offset, length)
            offset += length
            recovered += drain(accumulator)
        }
        return recovered
    }

    private fun drain(accumulator: FlacFrameAccumulator): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        while (true) {
            val frame = accumulator.nextFrame() ?: break
            // An empty array is the accumulator's "discarded bytes" sentinel, not a frame.
            if (frame.isNotEmpty()) {
                frames.add(frame)
            }
        }
        return frames
    }

    /**
     * A complete, structurally valid frame: `FF F8`, block size code 0x6 (8-bit block size follows)
     * with sample rate code 9 (44.1 kHz from STREAMINFO), channel assignment 0b0001 with sample size
     * code 0b100 (16 bit), a one-byte frame number, the stored 8-bit block size, the header CRC-8,
     * then per channel a `00000010` constant subframe header with one constant sample, and finally
     * the frame CRC-16 over everything before it.
     *
     * Both checksums are genuine. The accumulator recognises a header by its CRC-8, but confirms a
     * *boundary* by the preceding frame's CRC-16, so a fake of either would make these fixtures test
     * the wrong thing.
     */
    private fun buildFrame(blockSize: Int): ByteArray {
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF8.toByte(),
            0x69,
            0x18,
            0x00, // one-byte frame number
            (blockSize - 1).toByte(),
        )
        val body = header + byteArrayOf(crc8(header, 0, header.size).toByte()) + subframe() + subframe()
        return body + crc16(body, 0, body.size).toBigEndianBytes()
    }

    /** One channel's constant subframe holding a single sample. */
    private fun subframe(): ByteArray = byteArrayOf(0x02, 0x00, 0x00)

    /**
     * A sync code plus a header whose own CRC-8 is valid — structurally indistinguishable from a
     * real frame header. Placed inside a frame's body it must NOT be accepted as a boundary, because
     * the preceding frame's CRC-16 will not agree with the bytes in between.
     */
    private fun falseSyncSequence(): ByteArray {
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF8.toByte(),
            0x69,
            0x18,
            0x00,
            0x3F,
        )
        return header + byteArrayOf(crc8(header, 0, header.size).toByte())
    }

    /** CRC-8 with polynomial x^8 + x^2 + x + 1, as FLAC uses for frame headers. */
    private fun crc8(bytes: ByteArray, from: Int, until: Int): Int {
        var crc = 0
        for (index in from until until) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /** CRC-16 with polynomial x^16 + x^15 + x^2 + 1, as FLAC uses for the frame footer. */
    private fun crc16(bytes: ByteArray, from: Int, until: Int): Int {
        var crc = 0
        for (index in from until until) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun Int.toBigEndianBytes(): ByteArray =
        byteArrayOf(((this shr 8) and 0xFF).toByte(), (this and 0xFF).toByte())

    private companion object {
        const val CHANNELS = 2
        const val SUBFRAME_SIZE = 3
        const val FOOTER_SIZE = 2

        /** Sync(2) + block size/rate(1) + channel/size(1) + number(1) + size(1) + CRC(1). */
        const val HEADER_SIZE = 7
    }
}
