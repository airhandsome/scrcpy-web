package com.genymobile.scrcpy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String VIDEO_SOCKET_NAME = "svideo";
    private static final String AUDIO_SOCKET_NAME = "saudio";
    private static final String CONTROL_NAME = "svideo-control";


    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket controlSocket;
    private final InputStream controlInputStream;
    private final OutputStream controlOutputStream;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;
    private final ControlMessageReader reader = new ControlMessageReader();
    private final DeviceMessageWriter writer = new DeviceMessageWriter();

    private DesktopConnection(LocalSocket videoSocket, LocalSocket controlSocket, LocalSocket audioSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.controlSocket = controlSocket;
        this.audioSocket = audioSocket;

        controlInputStream = controlSocket.getInputStream();
        controlOutputStream = controlSocket.getOutputStream();
        videoFd = videoSocket.getFileDescriptor();
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor(): null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    public static DesktopConnection open(boolean tunnelForward, boolean audio) throws IOException {
        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;
        if (tunnelForward) {

            LocalServerSocket videoServerSocket = new LocalServerSocket(VIDEO_SOCKET_NAME);
            LocalServerSocket audioServerSocket = new LocalServerSocket(AUDIO_SOCKET_NAME);
            LocalServerSocket controlServerSocket = new LocalServerSocket(CONTROL_NAME);
            try {
                videoSocket = videoServerSocket.accept();
                // send one byte so the client may read() to detect a connection error
//                videoSocket.getOutputStream().write(0);//wen disable
                try {
                    controlSocket = controlServerSocket.accept();
                } catch (IOException | RuntimeException e) {
                    controlServerSocket.close();
                    throw e;
                }
                if (audio){
                    try {
                        audioSocket = audioServerSocket.accept();
                    } catch (IOException | RuntimeException e) {
                        audioServerSocket.close();
                        throw e;
                    }
                }
            } finally {
                videoServerSocket.close();
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
        return connection;
    }

    public void close() throws IOException {
        videoSocket.shutdownInput();
        videoSocket.shutdownOutput();
        videoSocket.close();
        controlSocket.shutdownInput();
        controlSocket.shutdownOutput();
        controlSocket.close();
        audioSocket.shutdownInput();
        audioSocket.shutdownOutput();;
        audioSocket.close();
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
    public FileDescriptor getAudioFd(){
        return audioFd;
    }
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
