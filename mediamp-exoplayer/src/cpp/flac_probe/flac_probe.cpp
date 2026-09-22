/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * On-device probe for the FLAC software decoder.
 *
 * Playback failures can only be attributed from a real device, and the app path is slow to
 * iterate on: it needs a rebuild, an install and UI driving. This executable drives the exact
 * same decode entry point the renderer uses (mediamp_flac::decodeFrame) over a FLAC file read
 * from disk and reports where decoding stops being correct:
 *
 *   1. whether dr_flac can open the file as one stream (rules the library itself out),
 *   2. whether the STREAMINFO the extractor would hand over parses into sane fields,
 *   3. whether frames decode when cut at real frame boundaries (parsed from each frame header,
 *      not by scanning for the sync code, which also occurs inside compressed subframes), and
 *   4. how the decoder copes with a buffer that holds more than one frame, since Media3's
 *      Decoder contract is fed one container sample at a time and that sample is not guaranteed
 *      to be a single frame.
 *
 * Not part of the shipped native libraries; see registerFlacProbeExecutable.
 *
 * Usage: flac_probe <file.flac> [maxFrames]
 */

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "flac_decode.h"

#include "thirdparty/dr_flac.h"

namespace {

constexpr size_t kStreamInfoSize = 34;

std::vector<uint8_t> readFile(const char *path) {
    std::vector<uint8_t> data;
    std::FILE *file = std::fopen(path, "rb");
    if (file == nullptr) {
        std::printf("cannot open %s\n", path);
        return data;
    }
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (size <= 0) {
        std::fclose(file);
        return data;
    }
    data.resize(static_cast<size_t>(size));
    const size_t read = std::fread(data.data(), 1, data.size(), file);
    std::fclose(file);
    data.resize(read);
    return data;
}

/** True when `bytes` starts with a FLAC frame sync code: 14 bits set followed by the reserved 0. */
bool isFrameSync(const uint8_t *bytes) {
    return bytes[0] == 0xFF && (bytes[1] & 0xFE) == 0xF8;
}

struct FrameHeader {
    /** Bytes of the header, i.e. where the subframes start. */
    size_t headerSize = 0;
    /** PCM frames per subframe, from the block size code (0 when stored in the header). */
    uint32_t blockSize = 0;
    bool valid = false;
};

/**
 * Parses the parts of a frame header needed to walk the stream (RFC 9639 §9.1).
 *
 * Walking by sync-code scanning is wrong: the sync bytes also occur inside a frame's compressed
 * subframes, so a scan finds false boundaries and truncates frames. Parsing the header gives the
 * exact end of the header; combined with the next header's position that yields the frame size,
 * and the block size code gives the sample count to verify the walk against.
 */
FrameHeader parseFrameHeader(const uint8_t *frame, size_t available) {
    FrameHeader result{};
    if (available < 6 || !isFrameSync(frame)) {
        return result;
    }
    const int blockSizeCode = frame[2] >> 4;
    const int sampleRateCode = frame[2] & 0x0F;

    size_t offset = 4;
    // UTF-8 coded frame number (fixed block size) or sample number (variable block size).
    const uint8_t firstByte = frame[offset++];
    if ((firstByte & 0x80) != 0) {
        for (int bit = 6; bit >= 0; --bit) {
            if ((firstByte & (1 << bit)) != 0) {
                ++offset;
            } else {
                break;
            }
        }
    }

    switch (blockSizeCode) {
        case 0x1: result.blockSize = 192; break;
        case 0x2: result.blockSize = 576; break;
        case 0x3: result.blockSize = 1152; break;
        case 0x4: result.blockSize = 2304; break;
        case 0x5: result.blockSize = 4608; break;
        case 0x6:
            if (offset + 1 > available) return result;
            result.blockSize = static_cast<uint32_t>(frame[offset]) + 1;
            offset += 1;
            break;
        case 0x7:
            if (offset + 2 > available) return result;
            result.blockSize =
                ((static_cast<uint32_t>(frame[offset]) << 8) | frame[offset + 1]) + 1;
            offset += 2;
            break;
        case 0x8: result.blockSize = 256; break;
        case 0x9: result.blockSize = 512; break;
        case 0xA: result.blockSize = 1024; break;
        case 0xB: result.blockSize = 2048; break;
        case 0xC: result.blockSize = 4096; break;
        case 0xD: result.blockSize = 8192; break;
        case 0xE: result.blockSize = 16384; break;
        case 0xF: result.blockSize = 32768; break;
        default: return result;  // 0x0 is reserved
    }

    switch (sampleRateCode) {
        case 0xC:
            if (offset + 1 > available) return result;
            offset += 1;
            break;
        case 0xD:
        case 0xE:
            if (offset + 2 > available) return result;
            offset += 2;
            break;
        default:
            break;
    }

    // CRC-8 of everything before it.
    if (offset + 1 > available) {
        return result;
    }
    offset += 1;

    result.headerSize = offset;
    result.valid = true;
    return result;
}

/**
 * Locates the STREAMINFO body the extractor would hand to the decoder.
 *
 * A raw `.flac` file carries the signature and the metadata block chain, while the decoder only
 * ever receives the bare 34-byte STREAMINFO body, so the probe has to strip the wrapper to test
 * the same input the renderer gets.
 */
bool findStreamInfo(const std::vector<uint8_t> &file, size_t *bodyOffset) {
    if (file.size() >= 4 && std::memcmp(file.data(), "fLaC", 4) == 0 && file.size() > 8) {
        // Signature, then the first metadata block header: type 0 is STREAMINFO, length 34.
        if ((file[4] & 0x7F) != 0) {
            return false;
        }
        *bodyOffset = 8;
        return true;
    }
    // Already a bare body (what Matroska's A_FLAC codec private holds).
    if (file.size() >= kStreamInfoSize) {
        *bodyOffset = 0;
        return true;
    }
    return false;
}

/** Byte offset of the first frame, derived from the metadata block chain. */
size_t firstFrameOffset(const std::vector<uint8_t> &file) {
    size_t offset = 0;
    if (file.size() >= 4 && std::memcmp(file.data(), "fLaC", 4) == 0) {
        offset = 4;
    }
    while (offset + 4 <= file.size()) {
        const bool last = (file[offset] & 0x80) != 0;
        const uint32_t length = (static_cast<uint32_t>(file[offset + 1]) << 16) |
            (static_cast<uint32_t>(file[offset + 2]) << 8) | file[offset + 3];
        offset += 4 + static_cast<size_t>(length);
        if (last) {
            break;
        }
        if (offset > file.size()) {
            return file.size();
        }
    }
    return offset;
}

/**
 * Frame sizes discovered by walking headers: the size of frame `index` is `index + 1` minus
 * `index`, so the walk needs the next header's offset. Returns the offsets of every frame start.
 */
std::vector<size_t> frameOffsets(const std::vector<uint8_t> &file, size_t first, size_t limit) {
    std::vector<size_t> offsets;
    size_t offset = first;
    while (offset + 6 <= file.size() && offsets.size() < limit) {
        const FrameHeader header = parseFrameHeader(file.data() + offset, file.size() - offset);
        if (!header.valid) {
            break;
        }
        offsets.push_back(offset);
        // The subframe section is at least a few bytes per channel; step past the header and
        // continue scanning for the next header. False syncs are rejected by requiring a
        // parsable header, which is far stricter than the two sync bytes alone.
        size_t candidate = offset + header.headerSize + 2;
        bool found = false;
        while (candidate + 6 <= file.size()) {
            if (isFrameSync(file.data() + candidate) &&
                parseFrameHeader(file.data() + candidate, file.size() - candidate).valid) {
                offset = candidate;
                found = true;
                break;
            }
            ++candidate;
        }
        if (!found) {
            break;
        }
    }
    return offsets;
}

void hexPreview(const uint8_t *bytes, size_t size, char *out, size_t outSize) {
    size_t written = 0;
    for (size_t index = 0; index < size && written + 4 < outSize; ++index) {
        written += static_cast<size_t>(
            std::snprintf(out + written, outSize - written, "%02X ", bytes[index]));
    }
    out[written] = '\0';
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::printf("usage: flac_probe <file.flac> [maxFrames]\n");
        return 2;
    }
    const int maxFrames = argc >= 3 ? std::atoi(argv[2]) : 5;

    const std::vector<uint8_t> file = readFile(argv[1]);
    if (file.empty()) {
        std::printf("FAIL: empty or unreadable file\n");
        return 2;
    }
    std::printf("file: %s (%zu bytes)\n", argv[1], file.size());

    const size_t firstFrame = firstFrameOffset(file);
    std::printf("first frame offset: %zu, first bytes:", firstFrame);
    for (size_t index = 0; index < 4 && firstFrame + index < file.size(); ++index) {
        std::printf(" %02X", file[firstFrame + index]);
    }
    std::printf("\n");
    if (firstFrame >= file.size()) {
        std::printf("FAIL: no frame found\n");
        return 2;
    }

    size_t streamInfoOffset = 0;
    if (!findStreamInfo(file, &streamInfoOffset)) {
        std::printf("FAIL: cannot locate STREAMINFO\n");
        return 2;
    }
    const std::vector<uint8_t> streamInfo(
        file.begin() + static_cast<long>(streamInfoOffset),
        file.begin() + static_cast<long>(streamInfoOffset) + static_cast<long>(kStreamInfoSize));
    const mediamp_flac::StreamInfo info =
        mediamp_flac::parseStreamInfo(streamInfo.data(), streamInfo.size());
    std::printf(
        "stream info: rate=%u channels=%u bits=%u blockSize=%u..%u totalSamples=%llu\n",
        info.sampleRate,
        info.channels,
        info.bitsPerSample,
        info.minBlockSize,
        info.maxBlockSize,
        static_cast<unsigned long long>(info.totalSamples));
    if (info.sampleRate == 0 || info.channels == 0 || info.bitsPerSample == 0) {
        std::printf("FAIL: stream info did not parse\n");
        return 1;
    }

    // Step 1: can dr_flac open the file as one stream? Rules the library itself out.
    drflac *whole = drflac_open_memory(file.data(), file.size(), nullptr);
    if (whole == nullptr) {
        std::printf("FAIL: dr_flac cannot open the file as one stream\n");
        return 1;
    }
    std::printf(
        "dr_flac whole-file: rate=%u channels=%u bits=%u\n",
        whole->sampleRate,
        whole->channels,
        whole->bitsPerSample);
    drflac_close(whole);

    // Step 2: decode frames cut at real boundaries.
    const size_t bytesPerFrame =
        static_cast<size_t>(info.channels) * static_cast<size_t>(sizeof(int32_t));
    std::vector<uint8_t> output(static_cast<size_t>(info.maxBlockSize) * bytesPerFrame * 4);

    const std::vector<size_t> offsets = frameOffsets(file, firstFrame, maxFrames + 1);
    std::printf("frame starts found: %zu\n", offsets.size());
    int decoded = 0;
    int failed = 0;
    for (size_t index = 0; index + 1 <= offsets.size() && index < static_cast<size_t>(maxFrames);
         ++index) {
        const size_t start = offsets[index];
        if (index + 1 >= offsets.size()) {
            break;
        }
        const size_t size = offsets[index + 1] - start;
        const FrameHeader header = parseFrameHeader(file.data() + start, file.size() - start);
        int32_t pcmFrames = 0;
        const mediamp_flac::DecodeStatus status = mediamp_flac::decodeFrame(
            streamInfo.data(),
            streamInfo.size(),
            file.data() + start,
            size,
            output.data(),
            output.size(),
            &pcmFrames);
        if (status == mediamp_flac::DecodeStatus::Ok) {
            ++decoded;
            std::printf(
                "frame %zu: offset=%zu size=%zu blockSize=%u -> %d PCM frames%s\n",
                index,
                start,
                size,
                header.blockSize,
                pcmFrames,
                pcmFrames == static_cast<int32_t>(header.blockSize) ? "" : "  <- MISMATCH");
        } else {
            ++failed;
            char preview[64];
            hexPreview(file.data() + start, 8, preview, sizeof(preview));
            std::printf(
                "frame %zu: offset=%zu size=%zu blockSize=%u -> FAILED: %s (head: %s)\n",
                index,
                start,
                size,
                header.blockSize,
                mediamp_flac::describe(status),
                preview);
        }
    }
    std::printf("single-frame results: %d ok, %d failed\n", decoded, failed);

    // Step 3: what if a container sample carries several frames at once? Media3 does not promise
    // one frame per sample, so the decoder has to cope with this shape too.
    if (offsets.size() >= 3 && decoded > 0) {
        const size_t multiStart = offsets[0];
        const size_t multiSize = offsets[2] - multiStart;
        int32_t pcmFrames = 0;
        const mediamp_flac::DecodeStatus status = mediamp_flac::decodeFrame(
            streamInfo.data(),
            streamInfo.size(),
            file.data() + multiStart,
            multiSize,
            output.data(),
            output.size(),
            &pcmFrames);
        std::printf(
            "two-frames-in-one-buffer: size=%zu -> %s (%d PCM frames)\n",
            multiSize,
            mediamp_flac::describe(status),
            pcmFrames);
    }

    if (decoded == 0) {
        std::printf("FAIL: no frame decoded\n");
        return 1;
    }
    std::printf("PASS: %d frame(s) decoded\n", decoded);
    return 0;
}
