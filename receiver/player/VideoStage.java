package com.secondscreen.receiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Receives the shell's H.264 stream and draws it on whichever surface is attached. */
public final class VideoStage implements SurfaceHolder.Callback {
    public interface Callback {
        void onFormat(int width, int height, int fps);
        void onPicture();
        void onIdle(String message);
    }

    private static final String TAG = "MiracastPlayer";
    private static final int PORT = 19723;
    private static VideoStage instance;

    private final ArrayDeque<byte[]> pending = new ArrayDeque<byte[]>();
    private volatile boolean running = true;
    private volatile Surface surface;
    private volatile Callback callback;
    private SurfaceView view;
    private int videoW;
    private int videoH;
    private int videoFps = 60;
    private boolean headerSeen;
    private MediaCodec codec;
    private long frames;
    private boolean loggedOutput;
    private boolean awaitingKeyframe;
    private boolean renderFrames = true;
    private long lastIdrRequestMs;

    public static void start() {
        if (instance != null) {
            return;
        }
        instance = new VideoStage();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                instance.serve();
            }
        }, "miracast-player");
        thread.setDaemon(true);
        thread.start();
    }

    public static void attach(SurfaceView view, Callback callback) {
        start();
        instance.callback = callback;
        instance.view = view;
        view.getHolder().addCallback(instance);
        if (view.getHolder().getSurface() != null && view.getHolder().getSurface().isValid()) {
            instance.surface = view.getHolder().getSurface();
            instance.drainPending();
        }
    }

    private void serve() {
        ServerSocket server = null;
        try {
            server = new ServerSocket(PORT);
            Log.i(TAG, "listening 127.0.0.1:" + PORT);
            while (running) {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                try {
                    readLoop(new DataInputStream(socket.getInputStream()));
                } catch (EOFException eof) {
                    notifyIdle("這次畫面結束了。Win + K 仍找得到這台手機，可以再連一次。");
                } catch (Exception ex) {
                    Log.e(TAG, "stream error", ex);
                    notifyIdle("畫面中斷了。若電腦還開著投屏，它會再試一次。");
                } finally {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                    stopCodec();
                    headerSeen = false;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "server failed", ex);
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void readLoop(DataInputStream in) throws Exception {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'W' || magic[1] != 'F' || magic[2] != 'D' || magic[3] != '1') {
            throw new IllegalStateException("bad magic");
        }
        applyFormat(in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort());
        while (running) {
            int len = in.readInt();
            if (len == -1) {
                applyFormat(in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort());
                continue;
            }
            if (len <= 0 || len > 8 * 1024 * 1024) {
                throw new IllegalStateException("bad au length " + len);
            }
            byte[] au = new byte[len];
            in.readFully(au);
            synchronized (pending) {
                if (awaitingKeyframe && !hasNal(au, 5) && pending.isEmpty()) {
                    // P-frames before the next keyframe cannot start a new decoder.
                } else {
                    if (awaitingKeyframe && hasNal(au, 5)) {
                        pending.clear();
                        Log.i(TAG, "keyframe queued " + au.length);
                    }
                    while (pending.size() > 90) {
                        byte[] first = pending.peekFirst();
                        if (awaitingKeyframe && first != null && hasNal(first, 5) && pending.size() > 1) {
                            byte[] key = pending.removeFirst();
                            pending.removeFirst();
                            pending.addFirst(key);
                        } else {
                            pending.removeFirst();
                        }
                    }
                    pending.addLast(au);
                }
            }
            if (awaitingKeyframe) {
                requestIdrSoon();
            }
            drainPending();
        }
    }

    private void applyFormat(int w, int h, int fps) {
        boolean changed = headerSeen && (w != videoW || h != videoH);
        videoW = w;
        videoH = h;
        videoFps = fps > 0 ? fps : 60;
        headerSeen = true;
        final Callback current = callback;
        if (current != null) {
            current.onFormat(videoW, videoH, videoFps);
        }
        if (changed) {
            stopCodec();
        }
        drainPending();
    }

    private synchronized void drainPending() {
        if (!headerSeen) {
            return;
        }
        boolean surfaceReady = surface != null && surface.isValid();
        if (codec == null && !surfaceReady) {
            return;
        }
        ensureCodec();
        if (codec == null) {
            return;
        }
        while (true) {
            byte[] au;
            synchronized (pending) {
                au = pending.pollFirst();
            }
            if (au == null) {
                break;
            }
            if (awaitingKeyframe && !hasNal(au, 5) && !hasNal(au, 7) && !hasNal(au, 8)) {
                continue;
            }
            if (hasNal(au, 5)) {
                awaitingKeyframe = false;
            }
            queue(au);
            releaseOutput();
        }
        releaseOutput();
    }

    private void ensureCodec() {
        if (codec != null || surface == null) {
            return;
        }
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6000000);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, videoFps);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, surface, null, 0);
            codec.start();
            loggedOutput = false;
            frames = 0;
            Log.i(TAG, "decoder started " + videoW + "x" + videoH + "@" + videoFps);
        } catch (Exception ex) {
            Log.e(TAG, "decoder configure failed", ex);
            stopCodec();
        }
    }

    private void queue(byte[] au) {
        try {
            int index = codec.dequeueInputBuffer(20000);
            if (index < 0) {
                releaseOutput();
                index = codec.dequeueInputBuffer(20000);
            }
            if (index < 0) {
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) {
                return;
            }
            buffer.clear();
            buffer.put(au);
            int flags = isKeyframe(au) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            codec.queueInputBuffer(index, 0, au.length, frames * (1000000L / videoFps), flags);
        } catch (Exception ex) {
            Log.e(TAG, "queue failed", ex);
            stopCodec();
            if (headerSeen) {
                awaitingKeyframe = true;
                requestIdrSoon();
            }
        }
    }

    private void releaseOutput() {
        if (codec == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 0);
            } catch (Exception ex) {
                Log.e(TAG, "dequeue output failed", ex);
                stopCodec();
                if (headerSeen) {
                    awaitingKeyframe = true;
                    requestIdrSoon();
                }
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (index < 0) {
                return;
            }
            boolean show = renderFrames && surface != null && surface.isValid();
            codec.releaseOutputBuffer(index, show);
            frames++;
            if (frames == 1) {
                final Callback current = callback;
                if (current != null) {
                    current.onPicture();
                }
            }
            if (!loggedOutput) {
                loggedOutput = true;
                Log.i(TAG, "OUTPUT " + codec.getOutputFormat());
            }
        }
    }

    private void notifyIdle(final String message) {
        final Callback current = callback;
        if (current != null) {
            current.onIdle(message);
        }
    }

    private static boolean isKeyframe(byte[] au) {
        return hasNal(au, 5) || hasNal(au, 7);
    }

    private static boolean hasNal(byte[] au, int type) {
        for (int i = 0; i + 4 < au.length; i++) {
            int nal = -1;
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
                nal = au[i + 3] & 0x1F;
            } else if (i + 5 < au.length && au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                nal = au[i + 4] & 0x1F;
            }
            if (nal == type) {
                return true;
            }
        }
        return false;
    }

    private synchronized void stopCodec() {
        MediaCodec current = codec;
        codec = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (Exception ignored) {
        }
        try {
            current.release();
        } catch (Exception ignored) {
        }
    }

    private void requestIdrSoon() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastIdrRequestMs < 500) {
            return;
        }
        lastIdrRequestMs = now;
        ShellBridge.requestIdr();
    }

    private void adoptSurface(Surface next) {
        surface = next;
        renderFrames = next != null && next.isValid();
        if (!renderFrames) {
            return;
        }
        if (codec != null) {
            try {
                codec.setOutputSurface(next);
                awaitingKeyframe = false;
                Log.i(TAG, "output surface replaced");
                return;
            } catch (Exception ex) {
                Log.e(TAG, "setOutputSurface failed", ex);
                stopCodec();
            }
        }
        if (headerSeen) {
            awaitingKeyframe = true;
            requestIdrSoon();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (this) {
            adoptSurface(holder.getSurface());
        }
        drainPending();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (this) {
            if (surface != holder.getSurface()) {
                adoptSurface(holder.getSurface());
            } else {
                renderFrames = true;
            }
        }
        drainPending();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (this) {
            renderFrames = false;
            if (surface == holder.getSurface()) {
                surface = null;
            }
            stopCodec();
            if (headerSeen) {
                awaitingKeyframe = true;
                requestIdrSoon();
            }
        }
    }
}
