package dev.droiduse.telecom;

import static dev.droiduse.system.DroidUseContract.*;
import android.content.Context;
import android.media.*;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.*;
import dev.droiduse.system.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded PCM16 pipes. Audio I/O never runs in system_server or a Binder callback. */
final class CallAudioStreams {
    private final AudioManager audio;
    private final Map<Integer, Pump> active = new HashMap<>();
    CallAudioStreams(Context context) { audio = context.getSystemService(AudioManager.class); }

    synchronized StreamHandle open(SessionHandle session, StreamSpec spec) throws Exception {
        if (spec.kind == STREAM_PRIVATE_MIC) throw new IllegalStateException("PRIVATE_MIC_ROUTE_NOT_VERIFIED");
        if (spec.kind < STREAM_CALL_UPLINK || spec.kind > STREAM_CALL_INJECTION)
            throw new IllegalArgumentException("INVALID_CALL_STREAM");
        // One capture source and one injection stream. Separate uplink/downlink are probed serially.
        boolean captureBusy = spec.kind != STREAM_CALL_INJECTION
                && active.keySet().stream().anyMatch(kind -> kind != STREAM_CALL_INJECTION);
        if (captureBusy || active.containsKey(spec.kind) || active.size() >= 2) throw new IllegalStateException(RESOURCE_LIMIT);
        if (audio.getMode() != AudioManager.MODE_IN_CALL || !audio.isPstnCallAudioInterceptable())
            throw new IllegalStateException(AUDIO_ROUTE_UNAVAILABLE);
        boolean inject = spec.kind == STREAM_CALL_INJECTION;
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(spec.sampleRateHz).setChannelMask(inject
                        ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_IN_MONO).build();
        AudioRecord record = null; AudioTrack track = null;
        ParcelFileDescriptor[] pipe = null;
        try {
            if (inject) track = audio.getCallUplinkInjectionAudioTrack(format);
            else if (spec.kind == STREAM_CALL_DOWNLINK) record = audio.getCallDownlinkExtractionAudioRecord(format);
            else {
                int source = spec.kind == STREAM_CALL_UPLINK ? MediaRecorder.AudioSource.VOICE_UPLINK
                        : MediaRecorder.AudioSource.VOICE_CALL;
                record = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                        .setBufferSizeInBytes(spec.sampleRateHz / 5 * 2).build();
                if (record.getState() != AudioRecord.STATE_INITIALIZED || record.getAudioSource() != source)
                    throw new IllegalStateException("CALL_CAPTURE_SOURCE_UNAVAILABLE");
            }
            pipe = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor local = pipe[inject ? 0 : 1], remote = pipe[inject ? 1 : 0];
            Os.fcntlInt(local.getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            // Linux pipe capacity is bounded independently of client-supplied capacityBytes.
            Pump pump = new Pump(spec, local, record, track);
            active.put(spec.kind, pump);
            StreamHandle result = new StreamHandle(); result.streamId = UUID.randomUUID().toString();
            result.sessionId = session.sessionId; result.kind = spec.kind; result.direction = spec.direction;
            result.format = spec.format; result.sampleRateHz = spec.sampleRateHz; result.channelCount = 1;
            result.capacityBytes = 65536; result.openedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
            result.transport = 1; result.descriptor = remote;
            pump.thread.start();
            return result;
        } catch (Exception e) {
            if (record != null) record.release(); if (track != null) track.release();
            if (pipe != null) for (ParcelFileDescriptor fd : pipe) try { fd.close(); } catch (IOException ignored) { }
            active.remove(spec.kind); throw e;
        }
    }
    synchronized void close() { for (Pump pump : active.values()) pump.stopped.set(true); }
    synchronized boolean drained() { return active.isEmpty(); }

    private final class Pump implements Runnable {
        final StreamSpec spec;
        final ParcelFileDescriptor fd;
        final AudioRecord record;
        final AudioTrack track;
        final AtomicBoolean stopped = new AtomicBoolean();
        final Thread thread;
        Pump(StreamSpec spec, ParcelFileDescriptor fd, AudioRecord record, AudioTrack track) {
            this.spec = spec; this.fd = fd; this.record = record; this.track = track;
            thread = new Thread(this, "DroidUseCallPcm");
        }
        public void run() {
            String failure = "STREAM_CLOSED";
            try {
                if (record != null) capture(); else inject();
            } catch (Exception e) { failure = "AUDIO_STREAM_FAILED"; }
            finally {
                boolean released = true;
                if (record != null) {
                    try { record.stop(); } catch (RuntimeException ignored) { }
                    try { record.release(); } catch (RuntimeException error) { released = false; }
                }
                if (track != null) {
                    try { track.pause(); track.flush(); track.stop(); } catch (RuntimeException ignored) { }
                    try { track.release(); } catch (RuntimeException error) { released = false; }
                }
                try { fd.closeWithError(failure); } catch (IOException ignored) { }
                // A failed native release must not be reported as drained or admit another stream.
                if (released) synchronized (CallAudioStreams.this) { active.remove(spec.kind, this); }
            }
        }
        private void live() throws IOException {
            if (stopped.get() || audio.getMode() != AudioManager.MODE_IN_CALL) throw new IOException("closed");
        }
        private void capture() throws Exception {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("not recording");
            byte[] pcm = new byte[spec.sampleRateHz / 50 * 2]; // at most 20 ms per packet
            long seq = 0, progress = SystemClock.elapsedRealtime();
            while (!stopped.get()) {
                live();
                int size = record.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                if (size < 0 || size % 2 != 0) throw new IOException("capture failed");
                if (size == 0) {
                    if (SystemClock.elapsedRealtime() - progress > 1000) throw new IOException("capture stalled");
                    SystemClock.sleep(10); continue;
                }
                progress = SystemClock.elapsedRealtime();
                byte[] packet = CallPcmFrame.encode(seq++, SystemClock.elapsedRealtimeNanos(), pcm, size);
                transfer(packet, true, SystemClock.elapsedRealtime() + 1000);
            }
        }
        private void inject() throws Exception {
            track.play(); long seq = 0;
            while (!stopped.get()) {
                byte[] header = new byte[CallPcmFrame.HEADER_BYTES];
                transfer(header, false, SystemClock.elapsedRealtime() + 1000);
                CallPcmFrame frame = CallPcmFrame.parse(header, seq++, SystemClock.elapsedRealtimeNanos(), spec.sampleRateHz);
                byte[] pcm = new byte[frame.size];
                transfer(pcm, false, SystemClock.elapsedRealtime() + 500);
                // Check age again after a potentially partial/slow client write.
                CallPcmFrame.parse(header, frame.sequence, SystemClock.elapsedRealtimeNanos(), spec.sampleRateHz);
                int offset = 0; long deadline = SystemClock.elapsedRealtime() + 500;
                while (offset < pcm.length) {
                    live();
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("injection stalled");
                    int count = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (count < 0) throw new IOException("injection failed");
                    offset += count; if (count == 0) SystemClock.sleep(5);
                }
            }
        }
        private void transfer(byte[] bytes, boolean write, long deadline) throws Exception {
            int offset = 0;
            while (offset < bytes.length) {
                live();
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("pipe stalled");
                StructPollfd poll = new StructPollfd(); poll.fd = fd.getFileDescriptor();
                poll.events = (short) (write ? OsConstants.POLLOUT : OsConstants.POLLIN);
                if (Os.poll(new StructPollfd[] {poll}, 50) == 0) continue;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) throw new IOException("pipe lost");
                try {
                    int count = write ? Os.write(poll.fd, bytes, offset, bytes.length - offset)
                            : Os.read(poll.fd, bytes, offset, bytes.length - offset);
                    if (count <= 0) throw new IOException("pipe EOF");
                    offset += count;
                } catch (ErrnoException error) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error;
                }
            }
        }
    }
}
