package io.github.vvb2060.callrecording.xposed;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pulls phone-number strings out of a protobuf-encoded file without knowing
 * its schema.
 *
 * Google Phone keeps its "Selected numbers" in a DataStore file of its own
 * (obfuscated) message type. Every protobuf field carries a small tag saying
 * how to skip it, so the file can be walked generically: text values that look
 * like phone numbers are collected, other byte strings are tried as nested
 * messages. Read-only; anything malformed is simply ignored.
 */
final class ProtoNumbers {
    private static final int MAX_DEPTH = 4;

    private ProtoNumbers() {}

    static Set<String> collect(byte[] data) {
        Set<String> numbers = new LinkedHashSet<>();
        parse(data, 0, data.length, 0, numbers);
        return numbers;
    }

    /** Returns false if the range is not a well-formed message. */
    private static boolean parse(byte[] data, int start, int end, int depth, Set<String> out) {
        int[] pos = {start};
        while (pos[0] < end) {
            long key = varint(data, pos, end);
            if (key < 0 || (key >>> 3) == 0) return false;
            switch ((int) (key & 7)) {
                case 0: // varint
                    if (varint(data, pos, end) < 0) return false;
                    break;
                case 1: // 64-bit
                    pos[0] += 8;
                    break;
                case 5: // 32-bit
                    pos[0] += 4;
                    break;
                case 2: { // length-delimited: string, bytes or nested message
                    long length = varint(data, pos, end);
                    if (length < 0 || pos[0] + length > end) return false;
                    int from = pos[0];
                    int to = from + (int) length;
                    if (looksLikeNumber(data, from, to)) {
                        out.add(new String(data, from, to - from, StandardCharsets.US_ASCII));
                    } else if (depth < MAX_DEPTH && to > from) {
                        Set<String> nested = new LinkedHashSet<>();
                        if (parse(data, from, to, depth + 1, nested)) out.addAll(nested);
                    }
                    pos[0] = to;
                    break;
                }
                default:
                    return false;
            }
        }
        return pos[0] == end;
    }

    private static long varint(byte[] data, int[] pos, int end) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= end) return -1;
            byte b = data[pos[0]++];
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }

    /** "+48600123456" or "600123456": optional leading +, then 6 to 18 digits. */
    private static boolean looksLikeNumber(byte[] data, int from, int to) {
        int i = from;
        if (i < to && data[i] == '+') i++;
        int digits = to - i;
        if (digits < 6 || digits > 18) return false;
        for (; i < to; i++) {
            if (data[i] < '0' || data[i] > '9') return false;
        }
        return true;
    }
}
