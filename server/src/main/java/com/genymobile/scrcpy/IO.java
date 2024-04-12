package com.genymobile.scrcpy;

import android.annotation.TargetApi;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

public final class IO {
    private IO() {
        // not instantiable
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public synchronized static void writeFully(FileDescriptor channel, ByteBuffer from) throws IOException {
        // ByteBuffer position is not updated as expected by Os.write() on old Android versions, so
        // count the remaining bytes manually.
        // See <https://github.com/Genymobile/scrcpy/issues/291>.
        int remaining = from.remaining();
        while (remaining > 0) {
            try {
                int w = Os.write(channel, from);
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
                    throw new AssertionError("write() returned a negative value (" + w + ")");
                }
                remaining -= w;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    public synchronized static void writeFully(FileDescriptor channel, byte[] buffer, int offset, int len) throws IOException {
        writeFully(channel, ByteBuffer.wrap(buffer, offset, len));
    }
    public static String toString(InputStream inputStream) {
        StringBuilder builder = new StringBuilder();
        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine()).append('\n');
        }
        return builder.toString();
    }

    public static boolean isBrokenPipe(IOException e) {
        Throwable cause = e.getCause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.EPIPE;
        }
        return false;
    }
}
