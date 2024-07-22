package com.genymobile.scrcpy;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class AudioEncoder implements AsyncProcessor {

    private static class InputTask {
        private final int index;

        InputTask(int index) {
            this.index = index;
        }
    }

    private static class OutputTask {
        private final int index;
        private final MediaCodec.BufferInfo bufferInfo;

        OutputTask(int index, MediaCodec.BufferInfo bufferInfo) {
            this.index = index;
            this.bufferInfo = bufferInfo;
        }
    }

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final int CHANNELS = AudioCapture.CHANNELS;

    private final AudioCapture capture;
    private final Streamer streamer;
    private final int bitRate;
    private final List<CodecOption> codecOptions;
    private final String encoderName;

    // Capacity of 64 is in practice "infinite" (it is limited by the number of available MediaCodec buffers, typically 4).
    // So many pending tasks would lead to an unacceptable delay anyway.
    private final BlockingQueue<InputTask> inputTasks = new ArrayBlockingQueue<>(64);
    private final BlockingQueue<OutputTask> outputTasks = new ArrayBlockingQueue<>(64);

    private Thread thread;
    private HandlerThread mediaCodecThread;

    private Thread inputThread;
    private Thread outputThread;

    private boolean ended;

    public AudioEncoder(AudioCapture capture, Streamer streamer, int bitRate, List<CodecOption> codecOptions, String encoderName) {
        this.capture = capture;
        this.streamer = streamer;
        this.bitRate = bitRate;
        this.codecOptions = codecOptions;
        this.encoderName = encoderName;
    }

    private static MediaFormat createFormat(String mimeType, int bitRate, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        ADTSUtil.initADTS(SAMPLE_RATE, CHANNELS);
        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Audio codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }

        return format;
    }

    @TargetApi(Build.VERSION_CODES.N)
    private void inputThread(MediaCodec mediaCodec, AudioCapture capture) throws IOException, InterruptedException {
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (!Thread.currentThread().isInterrupted()) {
            InputTask task = inputTasks.take();
            ByteBuffer buffer = mediaCodec.getInputBuffer(task.index);
            int r = capture.read(buffer, bufferInfo);
            if (r <= 0) {
                throw new IOException("Could not read audio: " + r);
            }

            mediaCodec.queueInputBuffer(task.index, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);
        }
    }

    private void outputThread(MediaCodec mediaCodec) throws IOException, InterruptedException {
        streamer.writeAudioHeader();

        while (!Thread.currentThread().isInterrupted()) {
            OutputTask task = outputTasks.take();
            ByteBuffer buffer = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                buffer = mediaCodec.getOutputBuffer(task.index);
            }
            try {
//                streamer.writeRawData(buffer);
//                streamer.writePacket(buffer, task.bufferInfo);
                streamer.writeAACBuffer(buffer, task.bufferInfo);
            } finally {
                mediaCodec.releaseOutputBuffer(task.index, false);
            }
        }
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            boolean fatalError = false;
            try {
                encode();
            } catch (ConfigurationException e) {
                // Do not print stack trace, a user-friendly error-message has already been logged
                fatalError = true;
            } catch (AudioCaptureForegroundException e) {
                // Do not print stack trace, a user-friendly error-message has already been logged
            } catch (IOException e) {
                Ln.e("Audio encoding error", e);
                fatalError = true;
            } finally {
                Ln.d("Audio encoder stopped");
                listener.onTerminated(fatalError);
            }
        }, "audio-encoder");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            // Just wake up the blocking wait from the thread, so that it properly releases all its resources and terminates
            end();
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    private synchronized void end() {
        ended = true;
        notify();
    }

    private synchronized void waitEnded() {
        try {
            while (!ended) {
                wait();
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }
    @TargetApi(Build.VERSION_CODES.M)
    public void encode() throws IOException, ConfigurationException, AudioCaptureForegroundException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Ln.w("Audio disabled: it is not supported before Android 11");
            streamer.writeDisableStream(false);
            return;
        }

        final int[] totalBytesRead = {0};
        final Long[] mPresentationTime = {0L};
        MediaCodec mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);

        boolean mediaCodecStarted = false;
        try {
//            Codec codec = streamer.getCodec();
//            mediaCodec = createMediaCodec(codec, encoderName);
            ADTSUtil.initADTS(SAMPLE_RATE, CHANNELS);
            mediaCodecThread = new HandlerThread("media-codec");
            mediaCodecThread.start();

            MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 196000);
            mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//            mediaCodec.setCallback(new EncoderCallback(), new Handler(mediaCodecThread.getLooper()));
            mediaCodec.setCallback(
                    new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mediaCodec, int i) {
                    ByteBuffer codecInputBuffer = mediaCodec.getInputBuffer(i);
                    int capacity = codecInputBuffer.capacity();
                    byte[] buffer = new byte[capacity];
                    int readBytes = capture.read(buffer, 0, buffer.length);
                    if (readBytes > 0) {
                        codecInputBuffer.put(buffer, 0, readBytes);
                        mediaCodec.queueInputBuffer(i, 0, readBytes, mPresentationTime[0], 0);
                        totalBytesRead[0] += readBytes;
                        mPresentationTime[0] = 1000000L * (totalBytesRead[0] / 2) / SAMPLE_RATE;
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int outputBufferIndex, MediaCodec.BufferInfo mBufferInfo) {
                    if (mBufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        Ln.i("AudioService config data");
                    } else {
                        byte[] oneADTSFrameBytes = new byte[7 + mBufferInfo.size];
                        ADTSUtil.addADTS(oneADTSFrameBytes);
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                        outputBuffer.get(oneADTSFrameBytes, 7, mBufferInfo.size);
                        try {

                            streamer.writeRawData(oneADTSFrameBytes, 0, oneADTSFrameBytes.length);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                }

                @Override
                public void onError(MediaCodec mediaCodec, MediaCodec.CodecException e) {
                    e.printStackTrace();
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mediaCodec,  MediaFormat mediaFormat) {

                }
            }, new Handler(mediaCodecThread.getLooper()));
            capture.start();
            mediaCodec.start();
            mediaCodecStarted = true;

            waitEnded();
        } catch (Throwable e) {
            // Notify the client that the audio could not be captured
            streamer.writeDisableStream(false);
            throw e;
        } finally {
            // Cleanup everything (either at the end or on error at any step of the initialization)
            if (mediaCodecThread != null) {
                Looper looper = mediaCodecThread.getLooper();
                if (looper != null) {
                    looper.quitSafely();
                }
            }
            if (inputThread != null) {
                inputThread.interrupt();
            }
            if (outputThread != null) {
                outputThread.interrupt();
            }

            try {
                if (mediaCodecThread != null) {
                    mediaCodecThread.join();
                }
                if (inputThread != null) {
                    inputThread.join();
                }
                if (outputThread != null) {
                    outputThread.join();
                }
            } catch (InterruptedException e) {
                // Should never happen
                throw new AssertionError(e);
            }

            if (mediaCodec != null) {
                if (mediaCodecStarted) {
                    mediaCodec.stop();
                }
                mediaCodec.release();
            }
            if (capture != null) {
                capture.stop();
            }
        }
    }

    private static MediaCodec createMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName != null) {
            Ln.d("Creating audio encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                Ln.e("Audio encoder '" + encoderName + "' for " + codec.getName() + " not found\n" + LogUtils.buildAudioEncoderListMessage());
                throw new ConfigurationException("Unknown encoder: " + encoderName);
            } catch (IOException e) {
                Ln.e("Could not create audio encoder '" + encoderName + "' for " + codec.getName() + "\n" + LogUtils.buildAudioEncoderListMessage());
                throw e;
            }
        }

        try {
            MediaCodec mediaCodec = MediaCodec.createEncoderByType(codec.getMimeType());
            Ln.d("Using audio encoder: '" + mediaCodec.getName() + "'");
            return mediaCodec;
        } catch (IOException | IllegalArgumentException e) {
            Ln.e("Could not create default audio encoder for " + codec.getName() + "\n" + LogUtils.buildAudioEncoderListMessage());
            throw e;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private final class EncoderCallback extends MediaCodec.Callback {
        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            try {
                inputTasks.put(new InputTask(index));
            } catch (InterruptedException e) {
                end();
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo bufferInfo) {
            try {
                outputTasks.put(new OutputTask(index, bufferInfo));
            } catch (InterruptedException e) {
                end();
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Ln.e("MediaCodec error", e);
            end();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // ignore
        }
    }
}
