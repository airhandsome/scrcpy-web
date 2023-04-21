package com.genymobile.scrcpy;

import java.io.IOException;

public final class DeviceMessageSender {

    private final DesktopConnection connection;

    private String clipboardText;
    private Thread thread;
    private long ack;
    public DeviceMessageSender(DesktopConnection connection) {
        this.connection = connection;
    }

    public synchronized void pushClipboardText(String text) {
        clipboardText = text;
        notify();
    }
    public synchronized void pushAckClipboard(long sequence) {
        ack = sequence;
        notify();
    }
    public void loop() throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            String text;
            long sequence;
            synchronized (this) {
                while (ack == DeviceMessage.SEQUENCE_INVALID && clipboardText == null) {
                    wait();
                }
                text = clipboardText;
                clipboardText = null;
            }
            DeviceMessage event = DeviceMessage.createClipboard(text);
            connection.sendDeviceMessage(event);
        }
    }
    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
            } finally {
                Ln.d("Device message sender stopped");
            }
        });
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}
