package dev.droiduse.system;

/** Single control lane. Any Telecom callback invalidates previously issued observations. */
public final class CallObservationGate {
    private long generation = 1;
    private long sequence;
    private long frame = -1;
    private long observedAt;

    public long observe(long now) { observedAt = now; return frame = ++sequence; }
    public long generation() { return generation; }
    public void invalidate() { generation++; frame = -1; }
    public void consume(long expectedFrame, long expectedGeneration, long now) {
        if (frame < 1 || expectedFrame != frame || expectedGeneration != generation
                || now < observedAt || now - observedAt >= 5000)
            throw new IllegalStateException("STALE_OBSERVATION");
        frame = -1;
    }
}
