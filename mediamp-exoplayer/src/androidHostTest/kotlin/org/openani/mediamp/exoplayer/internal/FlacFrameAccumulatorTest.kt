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

        // The real frame ends where the fake header begins, so it is emitted as soon as the fake
        // header's presence makes the frame's end known... which it must not, because the fake
        // header fails validation and therefore cannot bound the frame.
        assertNull(accumulator.nextFrame())

        accumulator.endOfInput()
        assertContentEquals(stream, accumulator.nextFrame())
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
            frames.add(frame)
        }
        return frames
    }

    /**
     * A frame with two constant subframes per channel, which is the smallest legal FLAC frame:
     * `FF F8`, block size code 0x6 (8-bit block size follows) with sample rate code 9 (44.1 kHz
     * from STREAMINFO), channel assignment 0b0001 with sample size code 0b100 (16 bit), a one-byte
     * frame number, the stored 8-bit block size, the header CRC-8, then per channel a `00000010`
     * constant subframe header with one sample, and the frame CRC-8.
     *
     * The header CRC has to be genuine, because the accumulator uses it to tell a real frame header
     * apart from the sync bytes that occur inside compressed subframes.
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
        val headerCrc = crc8(header, 0, header.size)
        val subframe = byteArrayOf(0x02, 0x00, 0x00)
        return header + byteArrayOf(headerCrc.toByte()) + subframe + subframe + byteArrayOf(0x00)
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
}
