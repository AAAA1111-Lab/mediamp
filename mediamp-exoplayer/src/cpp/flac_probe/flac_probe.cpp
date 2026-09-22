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
 * Playback failures can only be attributed from a real device, and iterating through the app needs
 * a rebuild, an install and UI driving. This executable drives the same decode entry point the
 * renderer uses (mediamp_flac::decodeFrame) over a FLAC file read from disk, but it reproduces the
 * *streaming* shape of the real decoder rather than reading whole frames from the file:
 *
 *   1. it splits the byte stream into container-sized chunks (the measured average Matroska block
 *      size by default), which is what Media3 hands the decoder, and
 *   2. it reassembles frames from those chunks using the same rules as
 *      FlacFrameAccumulator: a header is recognised by the sync code plus its CRC-8, and a frame
 *      ends where the next valid header begins.
 *
 * That makes it a real check of the reassembly logic, which is where 24-bit FLAC failed: the
 * blocks are neither frame aligned nor one frame per block.
 *
 * Not part of the shipped native libraries; see registerFlacProbeExecutable.
 *
 * Usage: flac_probe <file.flac> [chunkBytes] [maxFrames]
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
constexpr size_t kMinHeaderSize = 6;
constexpr size_t kMaxHeaderSize = 16;

const uint8_t *g_file = nullptr;
size_t g_fileSize = 0;

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

bool isHeaderStart(const uint8_t *bytes) {
    return bytes[0] == 0xFF && (bytes[1] & 0xFE) == 0xF8;
}

int crc8(const uint8_t *bytes, size_t count) {
    int crc = 0;
    for (size_t index = 0; index < count; ++index) {
        crc ^= bytes[index];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
    }
    return crc;
}

size_t headerSizeIgnoringCrc(const uint8_t *bytes, size_t available) {
    if (available < kMinHeaderSize || !isHeaderStart(bytes)) {
        return 0;
    }
    const int blockSizeCode = bytes[2] >> 4;
    const int sampleRateCode = bytes[2] & 0x0F;
    if (blockSizeCode == 0x0) {
        return 0;
    }
    size_t cursor = 4;
    const uint8_t first = bytes[cursor++];
    if ((first & 0x80) != 0) {
        for (int bit = 6; bit >= 0; --bit) {
            if ((first & (1 << bit)) != 0) {
                ++cursor;
            } else {
                break;
            }
        }
    }
    if (cursor > kMaxHeaderSize) return 0;
    if (blockSizeCode == 0x6) cursor += 1;
    if (blockSizeCode == 0x7) cursor += 2;
    if (sampleRateCode == 0xC) cursor += 1;
    if (sampleRateCode == 0xD || sampleRateCode == 0xE) cursor += 2;
    cursor += 1;  // header CRC-8
    if (cursor > kMaxHeaderSize) return 0;
    return cursor;
}

/** Same validation as FlacFrameAccumulator: the header must carry a matching CRC-8. */
size_t parseHeaderSize(const uint8_t *bytes, size_t available) {
    const size_t size = headerSizeIgnoringCrc(bytes, available);
    if (size == 0 || size > available) {
        return 0;
    }
    if (crc8(bytes, size - 1) != bytes[size - 1]) {
        return 0;
    }
    return size;
}

bool findStreamInfo(const std::vector<uint8_t> &file, size_t *bodyOffset) {
    if (file.size() > 8 && std::memcmp(file.data(), "fLaC", 4) == 0) {
        if ((file[4] & 0x7F) != 0) return false;
        *bodyOffset = 8;
        return true;
    }
    if (file.size() >= kStreamInfoSize) {
        *bodyOffset = 0;
        return true;
    }
    return false;
}

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
        if (last) break;
        if (offset > file.size()) return file.size();
    }
    return offset;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::printf("usage: flac_probe <file.flac> [chunkBytes] [maxFrames]\n");
        return 2;
    }
    const size_t chunkBytes = argc >= 3 ? static_cast<size_t>(std::atoi(argv[2])) : 10795;
    const int maxFrames = argc >= 4 ? std::atoi(argv[3]) : 6;

    const std::vector<uint8_t> file = readFile(argv[1]);
    if (file.empty()) {
        std::printf("FAIL: empty or unreadable file\n");
        return 2;
    }
    g_file = file.data();
    g_fileSize = file.size();
    std::printf("file: %s (%zu bytes), chunk=%zu bytes\n", argv[1], file.size(), chunkBytes);

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
        "stream info: rate=%u channels=%u bits=%u blockSize=%u..%u\n",
        info.sampleRate,
        info.channels,
        info.bitsPerSample,
        info.minBlockSize,
        info.maxBlockSize);
    if (info.sampleRate == 0 || info.channels == 0) {
        std::printf("FAIL: stream info did not parse (the bug that broke 24-bit)\n");
        return 1;
    }

    const size_t frameStart = firstFrameOffset(file);
    std::printf("first frame offset: %zu\n", frameStart);

    const size_t bytesPerFrame =
        static_cast<size_t>(info.channels) * static_cast<size_t>(sizeof(int32_t));
    std::vector<uint8_t> output(static_cast<size_t>(info.maxBlockSize) * bytesPerFrame * 4);

    // Walk the stream exactly as the decoder does: append container-sized chunks, emit whole
    // frames, keep the tail.
    std::vector<uint8_t> pending;
    size_t offset = frameStart;
    int decodedFrames = 0;
    int emitted = 0;
    long long totalPcmFrames = 0;
    int frameIndex = 0;

    auto drain = [&](bool endOfInput) {
        while (true) {
            if (pending.size() < kMinHeaderSize) {
                return;
            }
            if (parseHeaderSize(pending.data(), pending.size()) == 0) {
                // Realign on the next plausible header, as the accumulator does after a seek.
                size_t start = static_cast<size_t>(-1);
                for (size_t probe = 0; probe + kMinHeaderSize <= pending.size(); ++probe) {
                    if (isHeaderStart(pending.data() + probe) &&
                        parseHeaderSize(pending.data() + probe, pending.size() - probe) != 0) {
                        start = probe;
                        break;
                    }
                }
                if (start == static_cast<size_t>(-1)) {
                    return;
                }
                pending.erase(pending.begin(), pending.begin() + static_cast<long>(start));
                continue;
            }
            const size_t headerSize = parseHeaderSize(pending.data(), pending.size());
            size_t frameLength = 0;
            for (size_t candidate = headerSize + 1;
                 candidate + kMinHeaderSize <= pending.size();
                 ++candidate) {
                if (isHeaderStart(pending.data() + candidate) &&
                    parseHeaderSize(pending.data() + candidate, pending.size() - candidate) != 0) {
                    frameLength = candidate;
                    break;
                }
            }
            if (frameLength == 0) {
                if (endOfInput) {
                    frameLength = pending.size();
                } else {
                    return;
                }
            }
            if (emitted >= maxFrames) {
                return;
            }
            int32_t pcmFrames = 0;
            const mediamp_flac::DecodeStatus status = mediamp_flac::decodeFrame(
                streamInfo.data(),
                streamInfo.size(),
                pending.data(),
                frameLength,
                output.data(),
                output.size(),
                &pcmFrames);
            if (status == mediamp_flac::DecodeStatus::Ok) {
                ++decodedFrames;
                totalPcmFrames += pcmFrames;
                std::printf(
                    "frame %d: size=%zu -> %d PCM frames (%.1f ms)\n",
                    frameIndex,
                    frameLength,
                    pcmFrames,
                    1000.0 * pcmFrames / static_cast<double>(info.sampleRate));
            } else {
                std::printf(
                    "frame %d: size=%zu -> FAILED: %s\n",
                    frameIndex,
                    frameLength,
                    mediamp_flac::describe(status));
            }
            ++frameIndex;
            ++emitted;
            pending.erase(pending.begin(), pending.begin() + static_cast<long>(frameLength));
        }
    };

    while (offset < file.size()) {
        const size_t length = std::min(chunkBytes, file.size() - offset);
        pending.insert(pending.end(), file.begin() + static_cast<long>(offset),
                       file.begin() + static_cast<long>(offset + length));
        offset += length;
        drain(false);
    }
    drain(true);

    std::printf(
        "streaming result: %d/%d frames decoded, %lld PCM frames total, %zu bytes left over\n",
        decodedFrames,
        emitted,
        totalPcmFrames,
        pending.size());
    if (decodedFrames == 0) {
        std::printf("FAIL: nothing decoded\n");
        return 1;
    }
    if (decodedFrames != emitted) {
        std::printf("FAIL: %d of %d frames failed\n", emitted - decodedFrames, emitted);
        return 1;
    }
    std::printf("PASS\n");
    return 0;
}
