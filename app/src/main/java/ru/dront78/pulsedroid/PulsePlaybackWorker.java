package ru.dront78.pulsedroid;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.PowerManager.WakeLock;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;

public class PulsePlaybackWorker implements Runnable {
    private final String host;
    private final int port;
    private final WakeLock wakeLock;
    private final Handler handler;
    private final Listener listener;

    private Throwable error;
    private boolean stopped = false;

    PulsePlaybackWorker(String host, String port, WakeLock wakeLock, Handler handler, Listener listener) {
        this.host = host;
        this.port = Integer.valueOf(port);
        this.wakeLock = wakeLock;
        this.handler = handler;
        this.listener = listener;
    }

    public void stop() {
        stopped = true;
    }

    private void stopWithError(Throwable e) {
        Log.e(PulsePlaybackWorker.class.getSimpleName(), "stopWithError", e);
        error = e;
        stopped = true;
        handler.post(() -> listener.onPlaybackError(this, e));
    }

    public Throwable getError() {
        return error;
    }

    public void run() {
        BufferedInputStream audioData = null;
        AudioTrack audioTrack = null;
        try {
            Socket sock = new Socket(host, port);
            audioData = new BufferedInputStream(sock.getInputStream());

            // TODO native audio?
            final int sampleRate = 48000;

            int musicLength = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    sampleRate, AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, musicLength,
                    AudioTrack.MODE_STREAM);
            audioTrack.play();

            boolean started = false;

            // TODO buffer size computation
            byte[] audioBuffer = new byte[musicLength * 8];

            while (!stopped) {
                wakeLock.acquire(1000);
                int sizeRead = audioData.read(audioBuffer, 0, musicLength * 8);
                int sizeWrite = audioTrack.write(audioBuffer, 0, sizeRead);
                if (sizeWrite == AudioTrack.ERROR_INVALID_OPERATION) {
                    sizeWrite = 0;
                }
                if (sizeWrite == AudioTrack.ERROR_BAD_VALUE) {
                    sizeWrite = 0;
                }
                if (sizeWrite < 0) {
                    stop();
                } else if (!started) {
                    started = true;
                    handler.post(() -> listener.onPlaybackStarted(this));
                }
            }

            handler.post(() -> listener.onPlaybackStopped(this));
        } catch (Exception e) {
            stopWithError(e);
        } finally {
            if (audioTrack != null) {
                audioTrack.stop();
            }
            if (audioData != null) {
                try {
                    audioData.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public interface Listener {
        @MainThread
        void onPlaybackError(@NonNull PulsePlaybackWorker worker, @NonNull Throwable t);

        @MainThread
        void onPlaybackStarted(@NonNull PulsePlaybackWorker worker);

        @MainThread
        void onPlaybackStopped(@NonNull PulsePlaybackWorker worker);
    }
}
