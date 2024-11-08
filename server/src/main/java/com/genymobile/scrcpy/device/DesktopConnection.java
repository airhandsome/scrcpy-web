package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;

    private final LocalSocket controlSocket;
    private final ControlChannel controlChannel;
    private static final String SOCKET_NAME = "svideo";
    private static final String CONTROL_NAME = "svideo-control";
    private static final String AUDIO_NAME = "saudio";

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket) : null;
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
    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException, InterruptedException, ExecutionException {

        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;

        try {
            if (tunnelForward) {
                // 创建一个固定大小为3的线程池
                ExecutorService executor = Executors.newFixedThreadPool(3);

                // 用于存储Future对象，以便稍后获取结果
                Future<LocalSocket> videoFuture = null;
                Future<LocalSocket> audioFuture = null;
                Future<LocalSocket> controlFuture = null;

                if (video) {
                    final boolean finalSendDummyByte = sendDummyByte; // 创建final变量
                    videoFuture = executor.submit(() -> {
                        try (LocalServerSocket localServerSocket = SafelyCreateServer(SOCKET_NAME)) {
                            LocalSocket socket = localServerSocket.accept();
                            Ln.d("video connect");
                            if (finalSendDummyByte) { // 使用final变量
                                // 发送一个字节，以便客户端可以读取来检测连接错误
                                socket.getOutputStream().write(0);
//                                sendDummyByte = false; // 修改原始变量
                            }
                            return socket;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                if (audio) {
                    final boolean finalSendDummyByte = sendDummyByte; // 创建final变量
                    audioFuture = executor.submit(() -> {
                        try (LocalServerSocket localServerSocket = SafelyCreateServer(AUDIO_NAME)) {
                            LocalSocket socket = localServerSocket.accept();
                            Ln.d("audio connect");
                            if (finalSendDummyByte) { // 使用final变量
                                // 发送一个字节，以便客户端可以读取来检测连接错误
                                socket.getOutputStream().write(0);
//                                sendDummyByte = false; // 修改原始变量
                            }
                            return socket;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                if (control) {
                    final boolean finalSendDummyByte = sendDummyByte; // 创建final变量
                    controlFuture = executor.submit(() -> {
                        try (LocalServerSocket localServerSocket = SafelyCreateServer(CONTROL_NAME)) {
                            LocalSocket socket = localServerSocket.accept();
                            Ln.d("control connect");
                            if (finalSendDummyByte) { // 使用final变量
                                // 发送一个字节，以便客户端可以读取来检测连接错误
                                socket.getOutputStream().write(0);
//                                sendDummyByte = false; // 修改原始变量
                            }
                            return socket;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                // 获取结果并处理异常
                if (video && videoFuture != null) {
                    videoSocket = videoFuture.get();
                }
                if (audio && audioFuture != null) {
                    audioSocket = audioFuture.get();
                }
                if (control && controlFuture != null) {
                    controlSocket = controlFuture.get();
                }

                // 关闭线程池
                executor.shutdown();
            } else {
                if (video) {
                    videoSocket = connect(SOCKET_NAME);
                }
                if (audio) {
                    audioSocket = connect(AUDIO_NAME);
                }
                if (control) {
                    controlSocket = connect(CONTROL_NAME);
                }
            }
        } catch (InterruptedException | ExecutionException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw new IOException(e);
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private LocalSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        FileDescriptor fd = getFirstSocket().getFileDescriptor();
        Ln.d("write Device Meta: " + deviceNameBytes.toString());
//        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
