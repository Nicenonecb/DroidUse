package dev.droiduse.system;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Transport 1: LE magic, payload byte count, sequence, elapsedRealtimeNanos, PCM16 mono. */
public final class CallPcmFrame {
    public static final int HEADER_BYTES = 24;
    private static final int MAGIC = 0x44554131;
    public final int size;
    public final long sequence;
    private CallPcmFrame(int size, long sequence) { this.size = size; this.sequence = sequence; }
    public static byte[] encode(long sequence, long timestamp, byte[] pcm, int size) {
        if (size <= 0 || size > 1920 || size > pcm.length || size % 2 != 0 || sequence < 0 || timestamp < 0)
            throw new IllegalArgumentException("INVALID_PCM_FRAME");
        return ByteBuffer.allocate(HEADER_BYTES + size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putInt(size).putLong(sequence).putLong(timestamp).put(pcm, 0, size).array();
    }
    public static CallPcmFrame parse(byte[] header, long expectedSequence, long nowNanos, int rate) {
        if (header.length != HEADER_BYTES || rate != 8000 && rate != 16000 && rate != 48000)
            throw new IllegalArgumentException("INVALID_PCM_HEADER");
        ByteBuffer value = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int magic = value.getInt(), size = value.getInt(); long sequence = value.getLong(), timestamp = value.getLong();
        if (magic != MAGIC || size <= 0 || size % 2 != 0 || size > rate / 50 * 2
                || sequence < 0 || sequence != expectedSequence || timestamp < 0
                || timestamp > nowNanos + 100_000_000L || timestamp < nowNanos - 500_000_000L)
            throw new IllegalArgumentException("INVALID_OR_STALE_PCM_FRAME");
        return new CallPcmFrame(size, sequence);
    }
}
