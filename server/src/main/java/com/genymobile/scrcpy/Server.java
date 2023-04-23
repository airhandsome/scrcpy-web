package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.BatteryManager;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.lang.System;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

import android.os.Handler;

public final class Server {

    private static Handler handler;

    private Server() {
        // not instantiable
    }
    private static void initAndCleanUp(Options options) {
        boolean mustDisableShowTouchesOnCleanUp = false;
        int restoreStayOn = -1;
        boolean restoreNormalPowerMode = options.getControl(); // only restore power mode if control is enabled
        if (options.getShowTouches() || options.getStayAwake()) {
            if (options.getShowTouches()) {
                try {
                    String oldValue = Settings.getAndPutValue(Settings.TABLE_SYSTEM, "show_touches", "1");
                    // If "show touches" was disabled, it must be disabled back on clean up
                    mustDisableShowTouchesOnCleanUp = !"1".equals(oldValue);
                } catch (SettingsException e) {
                    Ln.e("Could not change \"show_touches\"", e);
                }
            }

            if (options.getStayAwake()) {
                int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                try {
                    String oldValue = Settings.getAndPutValue(Settings.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                    try {
                        restoreStayOn = Integer.parseInt(oldValue);
                        if (restoreStayOn == stayOn) {
                            // No need to restore
                            restoreStayOn = -1;
                        }
                    } catch (NumberFormatException e) {
                        restoreStayOn = 0;
                    }
                } catch (SettingsException e) {
                    Ln.e("Could not change \"stay_on_while_plugged_in\"", e);
                }
            }
        }

        if (options.getCleanup()) {
            try {
                CleanUp.configure(options.getDisplayId(), restoreStayOn, mustDisableShowTouchesOnCleanUp, restoreNormalPowerMode,
                        options.getPowerOffScreenOnClose());
            } catch (IOException e) {
                Ln.e("Could not configure cleanup", e);
            }
        }
    }
    private static void scrcpy(Options options) throws IOException, ConfigurationException {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        AccessibilityNodeInfoDumper dumper = null;
        final Device device = new Device(options);
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean audio = options.getAudio();
        boolean sendDummyByte = options.getSendDummyByte();

        Workarounds.prepareMainLooper();

        // Workarounds must be applied for Meizu phones:
        //  - <https://github.com/Genymobile/scrcpy/issues/240>
        //  - <https://github.com/Genymobile/scrcpy/issues/365>
        //  - <https://github.com/Genymobile/scrcpy/issues/2656>
        //
        // But only apply when strictly necessary, since workarounds can cause other issues:
        //  - <https://github.com/Genymobile/scrcpy/issues/940>
        //  - <https://github.com/Genymobile/scrcpy/issues/994>
        boolean mustFillAppInfo = Build.BRAND.equalsIgnoreCase("meizu");

        // Before Android 11, audio is not supported.
        // Since Android 12, we can properly set a context on the AudioRecord.
        // Only on Android 11 we must fill app info for the AudioRecord to work.
        mustFillAppInfo |= audio && Build.VERSION.SDK_INT == Build.VERSION_CODES.R;

        if (mustFillAppInfo) {
            Workarounds.fillAppInfo();
        }

        List<AsyncProcessor> asyncProcessors = new ArrayList<>();

        try (DesktopConnection connection = DesktopConnection.open(tunnelForward, audio)) {

            if (control) {

                Controller controller = new Controller(device, connection, options.getClipboardAutosync(), options.getPowerOn());
                device.setClipboardListener(text -> controller.getSender().pushClipboardText(text));
                asyncProcessors.add(controller);
                /*
                Controller controller = new Controller(device, connection);
                // asynchronous
                startController(controller);
                startDeviceMessageSender(controller.getSender());

                 */
            }
            if (audio){
                AudioCodec audioCodec = options.getAudioCodec();
                Streamer audioStreamer = new Streamer(connection.getAudioFd(), audioCodec, options.getSendCodecMeta(),
                        options.getSendFrameMeta());
                AsyncProcessor audioRecorder;
                if (audioCodec == AudioCodec.RAW) {
                    audioRecorder = new AudioRawRecorder(audioStreamer);
                } else {
                    audioRecorder = new AudioEncoder(audioStreamer, options.getAudioBitRate(), options.getAudioCodecOptions(),
                            options.getAudioEncoder());
                }
                asyncProcessors.add(audioRecorder);
            }

            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.start();
            }


            if (options.getDumpHierarchy()) {
                dumper = new AccessibilityNodeInfoDumper(handler, device, connection);
                dumper.start();
            }

            try {
                Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), options.getSendCodecMeta(),
                        options.getSendFrameMeta());
                ScreenEncoder screenEncoder = new ScreenEncoder(options, device, videoStreamer);
                handler = screenEncoder.getHandler();
                // synchronous
                screenEncoder.streamScreen(device, connection.getVideoFd());
            } catch (IOException e) {
                if (!IO.isBrokenPipe(e)){
                    Ln.e("video encoding error" + e.getMessage());
                }
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


    @SuppressWarnings("checkstyle:MagicNumber")
    private static Options createOptions(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        Ln.i("VERSION_NAME: " + BuildConfig.VERSION_NAME);
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + clientVersion + ") does not match the client " + "(" + BuildConfig.VERSION_NAME + ")");
        }

        if (args.length != 8) {
            throw new IllegalArgumentException("Expecting 8 parameters");
        }

        Options options = new Options();

        int maxSize = Integer.parseInt(args[1]) & ~7; // multiple of 8
        options.setMaxSize(maxSize);

        int bitRate = Integer.parseInt(args[2]);
        options.setVideoBitRate(bitRate);

        int maxFps = Integer.parseInt(args[3]);
        options.setMaxFps(maxFps);

        // use "adb forward" instead of "adb tunnel"? (so the server must listen)
        boolean tunnelForward = Boolean.parseBoolean(args[4]);
        options.setTunnelForward(tunnelForward);

        Rect crop = parseCrop(args[5]);
        options.setCrop(crop);

        boolean sendFrameMeta = Boolean.parseBoolean(args[6]);
        options.setSendFrameMeta(sendFrameMeta);

        boolean control = Boolean.parseBoolean(args[7]);
        options.setControl(control);

        return options;
    }

    private static Options customOptions(String... args) {
        org.apache.commons.cli.CommandLine commandLine = null;
        org.apache.commons.cli.CommandLineParser parser = new org.apache.commons.cli.BasicParser();
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
        options.addOption("a", true, "If or not capture audio(default true)");
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
        o.setVideoBitRate(3000000);
        o.setSendFrameMeta(true);
        o.setQuality(60);
        // control
        o.setControlOnly(false);
        // dump
        o.setDumpHierarchy(false);
        if (commandLine.hasOption('b')) {
            int i = 0;
            try {
                i = Integer.parseInt(commandLine.getOptionValue('b'));
            } catch (Exception e) {
            }
            if (i > 200 && i <= 10000) {
                o.setVideoBitRate(i * 1000);
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
        if (commandLine.hasOption('D')) {
            o.setDumpHierarchy(true);
        }
        if (commandLine.hasOption('m')){
            try{
                String mode = commandLine.getOptionValue('m');
                Ln.e("mode:" + mode);

                if (mode.equals("image")){
                    ScreenEncoder.videoMode = false;
                }
                Ln.e("videoMode:" + ScreenEncoder.videoMode);
            }catch (Exception e){
            }
        }
        if (commandLine.hasOption('a')){
            try{
                boolean audio = Boolean.parseBoolean(commandLine.getOptionValue('a'));
                o.setAudio(audio);
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
//        Options options = createOptions(args);
        final Options options = customOptions(args);
        Ln.i("Options frame rate: " + options.getMaxFps() + " (1 ~ 10)");
        Ln.i("Options bitrate: " + options.getVideoBitRate() + " (200K-10M)");
        Ln.i("Options projection: " + options.getScale() + " (1080, 720, 480, 360...)");
        Ln.i("Options control only: " + options.getControlOnly() + " (true / false)");
        scrcpy(options);
    }
}
