package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.lang.System;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.security.cert.PKIXRevocationChecker;
import java.util.ArrayList;
import java.util.List;

import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

public final class Server {

    private static final String SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";
    private static Handler handler;
    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException {

        try{
            if (Build.VERSION.RELEASE.contains(".")){
                String version = Build.VERSION.RELEASE.split(".")[0];
                ScreenEncoder.AndroidVersion = Integer.parseInt(version);
            }else{
                ScreenEncoder.AndroidVersion = Integer.parseInt(Build.VERSION.RELEASE);
            }
        }catch (Exception e){
            Log.e("svideo","parse version error, use default");
            ScreenEncoder.AndroidVersion = 10;
        }


        AccessibilityNodeInfoDumper dumper = null;
        final Device device = new Device(options);
        boolean tunnelForward = options.isTunnelForward();

        try (DesktopConnection connection = DesktopConnection.open(device, tunnelForward)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options, device);
            handler = screenEncoder.getHandler();
            if (options.getControl()) {
                Controller controller = new Controller(device, connection);
                device.setClipboardListener(text -> controller.getSender().pushClipboardText(text));

                // asynchronous
                startController(controller);
                startDeviceMessageSender(controller.getSender());
            }

            if (options.getAudio()){
                AsyncProcessor audioRecorder = getAsyncProcessor(options, connection);
                audioRecorder.start((fatalError -> {
                    Ln.e("audio error: " + fatalError);
                }));
            }

            if (options.getDumpHierarchy()) {
                dumper = new AccessibilityNodeInfoDumper(handler, device, connection);
                dumper.start();
            }

            try {
                // synchronous
                screenEncoder.streamScreen(device, connection.getVideoFd());
            } catch (IOException e) {
                Ln.i("exit: " + e.getMessage());
                //do exit(0)
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (options.getDumpHierarchy() && dumper != null) {
                    dumper.stop();
                }
                // this is expected on close
                Ln.d("Screen streaming stopped");
                System.exit(0);
            }

        }
    }

    private static AsyncProcessor getAsyncProcessor(Options options, DesktopConnection connection) {
        AudioCodec audioCodec = options.getAudioCodec();
        AudioCapture audioCapture = new AudioCapture(options.getAudioSource());
        Streamer audioStreamer = new Streamer(connection.getAudioFd(), audioCodec, options.getSendCodecMeta(), options.getSendFrameMeta());
        AsyncProcessor audioRecorder;
        if (audioCodec == AudioCodec.RAW) {
            audioRecorder = new AudioRawRecorder(audioCapture, audioStreamer);
        } else {
            audioRecorder = new AudioEncoder(audioCapture, audioStreamer, options.getAudioBitRate(), options.getAudioCodecOptions(),
                    options.getAudioEncoder());
        }
        return audioRecorder;
    }

    private static void startController(final Controller controller) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Controller stopped");
                    Common.stopScrcpy(handler, "control");
                }
            }
        }).start();
    }

    private static void startDeviceMessageSender(final DeviceMessageSender sender) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (IOException | InterruptedException e) {
                    // this is expected on close
                    Ln.d("Device message sender stopped");
                }
            }
        }).start();
    }
    private static Options customOptions(String... args) {
        org.apache.commons.cli.CommandLine commandLine = null;
        org.apache.commons.cli.CommandLineParser parser = new org.apache.commons.cli.DefaultParser();
        org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();
        options.addOption("Q", true, "JPEG quality (0-100)");
        options.addOption("r", true, "maxFps (0-100)");
        options.addOption("b", true, "bitrate (200K-10M)");
        options.addOption("P", true, "Display projection (1080, 720, 480...).");
        options.addOption("c", false, "Control only");
        options.addOption("L", false, "Library path");
        options.addOption("D", false, "Dump window hierarchy");
        options.addOption("h", false, "Show help");
        options.addOption("m", true, "choose the stream mode for video or image");
        options.addOption("a", false, "record the audio or not");
        options.addOption("audio", true, "audio record type");

        try {
            commandLine = parser.parse(options, args);
        } catch (Exception e) {
            Ln.e(e.getMessage());
            System.exit(0);
        }

        if (commandLine.hasOption('h')) {
            System.out.println(
                    "Usage: [-h]\n\n"
                            + "h264:\n"
                            + "  -r <value>:    maxFps (15-60).\n"
                            + "  -P <value>:    Display projection (1080, 720, 480, 360...).\n"
                            + "  -b <value>:    bitrate (200K-10M)\n"
                            + "\n"
                            + "JPEG \n"
                            + "  -Q <value>:    JPEG quality (0-100).\n"
                            + "  -c:            Control only.\n"
                            + "  -L:            Library path.\n"
                            + "  -D:            Dump window hierarchy.\n"
                            + "  -h:            Show help.\n"
                            + "  -m:            Choose stream mode"
            );
            System.exit(0);
        }
        if (commandLine.hasOption('L')) {
            System.out.println(System.getProperty("java.library.path"));
            System.exit(0);
        }
        Options o = new Options();
        o.setMaxSize(0);
        o.setTunnelForward(true);
        o.setCrop(null);
        o.setControl(true);
        o.setAudio(true);
        // global
        o.setMaxFps(24);
        o.setScale(480);
        o.setBitRate(3000000);
        o.setSendFrameMeta(true);
        o.setQuality(60);
        // control
        o.setControlOnly(false);
        // dump
        o.setDumpHierarchy(false);
        // audio
//        List of audio encoders:
//        https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md
        o.setAudioBitRate(64000);
        o.setAudioEncoder("c2.android.aac.encoder");
        o.setAudioCodec(AudioCodec.AAC);
//        o.setAudioCodec(AudioCodec.valueOf(MediaFormat.MIMETYPE_AUDIO_AAC));
        o.setAudioSource(AudioSource.OUTPUT);

        if (commandLine.hasOption('b')) {
            int i = 0;
            try {
                i = Integer.parseInt(commandLine.getOptionValue('b'));
            } catch (Exception e) {
            }
            if (i > 200 && i <= 10000) {
                o.setBitRate(i * 1000);
            }
        }
        if (commandLine.hasOption('Q')) {
            int i = 0;
            try {
                i = Integer.parseInt(commandLine.getOptionValue('Q'));
            } catch (Exception e) {
            }
            if (i > 0 && i <= 100) {
                o.setQuality(i);
            }
        }
        if (commandLine.hasOption('r')) {
            int i = 0;
            try {
                i = Integer.parseInt(commandLine.getOptionValue('r'));
            } catch (Exception e) {
            }
            if (i > 0 && i <= 100) {
                o.setMaxFps(i);
            }
        }
        if (commandLine.hasOption('P')) {
            int i = 0;
            try {
                i = Integer.parseInt(commandLine.getOptionValue('P'));
            } catch (Exception e) {
            }
            if (i > 0) {
                o.setScale(i);
            }
        }
        if (commandLine.hasOption('c')) {
            o.setControlOnly(true);
        }
        if (commandLine.hasOption('a')){
            o.setAudio(true);
        }
        if (commandLine.hasOption("audio")) {
            try {
                String type = commandLine.getOptionValue("audio");
                switch (type){
                    case "aac":
                        o.setAudioEncoder("c2.android.aac.encoder");
                        o.setAudioCodec(AudioCodec.AAC);
                        Ln.d("set type to aac");
                        break;
                    case "opus":
                        o.setAudioEncoder("c2.android.opus.encoder");
                        o.setAudioCodec(AudioCodec.OPUS);
                        Ln.d("set type to opus");
                        break;
                    case "flac":
                        o.setAudioEncoder("c2.android.flac.encoder");
                        o.setAudioCodec(AudioCodec.FLAC);
                        Ln.d("set type to flac");
                        break;
                    default:
                        o.setAudioCodec(AudioCodec.RAW);
                        Ln.d("set type to raw");
                }

            } catch (Exception e) {
            }
        }
        if (commandLine.hasOption('D')) {
            o.setDumpHierarchy(true);
        }
        if (commandLine.hasOption('m')){
            try{
                String mode = commandLine.getOptionValue('m');
                Ln.d("mode:" + mode);

                if (mode.equals("image")){
                    ScreenEncoder.videoMode = false;
                }
                Ln.d("videoMode:" + ScreenEncoder.videoMode);
            }catch (Exception e){
            }
        }
        return o;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static Rect parseCrop(String crop) {
        if ("-".equals(crop)) {
            return null;
        }
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        return new Rect(x, y, x + width, y + height);
    }

    private static void unlinkSelf() {
        try {
            new File(SERVER_PATH).delete();
        } catch (Exception e) {
            Ln.e("Could not unlink server", e);
        }
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static void suggestFix(Throwable e) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (e instanceof MediaCodec.CodecException) {//api level 21
//                MediaCodec.CodecException mce = (MediaCodec.CodecException) e;
//                if (mce.getErrorCode() == 0xfffffc0e) {
//                    Ln.e("The hardware encoder is not able to encode at the given definition.");
//                    Ln.e("Try with a lower definition:");
//                    Ln.e("    scrcpy -m 1024");
//                }
//            }
        }
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
                suggestFix(e);
            }
        });

//        unlinkSelf();
//        Options options = Options.parse(args);
        final Options options = customOptions(args);
        Ln.i("Options frame rate: " + options.getMaxFps() + " (1 ~ 10)");
        Ln.i("Options bitrate: " + options.getBitRate() + " (200K-10M)");
        Ln.i("Options projection: " + options.getScale() + " (1080, 720, 480, 360...)");
        Ln.i("Options control only: " + options.getControlOnly() + " (true / false)");
        Workarounds.apply(false, true);
        scrcpy(options);
    }
}
