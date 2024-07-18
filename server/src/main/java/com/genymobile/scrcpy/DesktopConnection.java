package com.genymobile.scrcpy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME = "svideo";
    private static final String CONTROL_NAME = "svideo-control";
    private static final String AUDIO_NAME = "saudio";
    private static final int SOCKET_PORT = 6612;
    private static final int CONTROL_SOCKET_PORT = 6613;

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket audioSocket;

    private final FileDescriptor audioFd;
    private final LocalSocket controlSocket;

    private final InputStream controlInputStream;
    private final OutputStream controlOutputStream;

    private final ControlMessageReader reader = new ControlMessageReader();
    private final DeviceMessageWriter writer = new DeviceMessageWriter();

    private DesktopConnection(LocalSocket videoSocket, LocalSocket controlSocket, LocalSocket audioSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.controlSocket = controlSocket;
        this.audioSocket = audioSocket;
        controlInputStream = controlSocket.getInputStream();
        controlOutputStream = controlSocket.getOutputStream();
        videoFd = videoSocket.getFileDescriptor();
        audioFd = audioSocket.getFileDescriptor();
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    public static String RunCmd(String cmd){
        StringBuilder build = new StringBuilder();
        try{
            Process process = Runtime.getRuntime().exec(cmd);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine())!= null) {
                    build.append(line);
                }
            }
            process.waitFor();
        }catch (Exception e){
            Ln.e("Execute command line error " + cmd);
        }
        return build.toString();
    }


    public static LocalServerSocket SafelyCreateServer(String sockeName) {
        LocalServerSocket socket = null;
        for (int i = 0; i < 3; i++){
            try{
                socket = new LocalServerSocket(sockeName);
                return socket;
            }catch (IOException e){
                String cmd = "lsof | grep svideo";
                String res = RunCmd(cmd);
                if (res.length() > 0){
                    String[] resArray = res.split("\\s+");
                    if (resArray.length > 1){
                        String killCmd = "kill -15 " + resArray[1];
                        RunCmd(killCmd);
                    }
                }
            }
        }
        return null;
    }

    public static DesktopConnection open(Device device, boolean tunnelForward) throws IOException {
        LocalSocket videoSocket = null;
        LocalSocket controlSocket = null;
        LocalSocket audioSocket = null;
        if (tunnelForward) {
//            ServerSocketChannel localServerSocket = ServerSocketChannel.open();
//            ServerSocketChannel controlServerSocket = ServerSocketChannel.open();
//            localServerSocket.socket().bind(new InetSocketAddress(SOCKET_PORT));
//            controlServerSocket.socket().bind(new InetSocketAddress(CONTROL_SOCKET_PORT));

            LocalServerSocket localServerSocket = SafelyCreateServer(SOCKET_NAME);
            LocalServerSocket controlServerSocket = SafelyCreateServer(CONTROL_NAME);
            LocalServerSocket audioServerSocket = SafelyCreateServer(AUDIO_NAME);

            try {
                videoSocket = localServerSocket.accept();
                // send one byte so the client may read() to detect a connection error
//                videoSocket.getOutputStream().write(0);//wen disable
                try {
                    controlSocket = controlServerSocket.accept();
                } catch (IOException | RuntimeException e) {
                    videoSocket.close();
                    throw e;
                }
                try{
                    audioSocket = audioServerSocket.accept();
                }catch (IOException | RuntimeException e){
                    audioSocket.close();
                    throw e;
                }

            } finally {
                localServerSocket.close();
            }
        } else {
//            videoSocket = connect(SOCKET_NAME);
//            try {
//                controlSocket = connect(SOCKET_NAME);
//            } catch (IOException | RuntimeException e) {
//                videoSocket.close();
//                throw e;
//            }
        }

        DesktopConnection connection = new DesktopConnection(videoSocket, controlSocket, audioSocket);
        Size videoSize = device.getScreenInfo().getVideoSize();
//        connection.send(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());//wen disable
        return connection;
    }

    public void close() throws IOException {
        videoSocket.shutdownInput();
        videoSocket.shutdownOutput();
        videoSocket.close();
        controlSocket.shutdownInput();
        controlSocket.shutdownOutput();
        controlSocket.close();
    }

//    @SuppressWarnings("checkstyle:MagicNumber")
//    private void send(String deviceName, int width, int height) throws IOException {
//        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH + 4];
//
//        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
//        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
//        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
//        // byte[] are always 0-initialized in java, no need to set '\0' explicitly
//
//        buffer[DEVICE_NAME_FIELD_LENGTH] = (byte) (width >> 8);
//        buffer[DEVICE_NAME_FIELD_LENGTH + 1] = (byte) width;
//        buffer[DEVICE_NAME_FIELD_LENGTH + 2] = (byte) (height >> 8);
//        buffer[DEVICE_NAME_FIELD_LENGTH + 3] = (byte) height;
//        IO.writeFully(videoFd, buffer, 0, buffer.length);
//    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {return audioFd;}

    public ControlMessage receiveControlMessage() throws IOException {
        ControlMessage msg = reader.next();
        while (msg == null) {
            reader.readFrom(controlInputStream);
            msg = reader.next();
        }
        return msg;
    }

    public void sendDeviceMessage(DeviceMessage msg) throws IOException {
        writer.writeTo(msg, controlOutputStream);
    }
}
