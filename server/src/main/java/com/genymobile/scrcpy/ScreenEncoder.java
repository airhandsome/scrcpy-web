package com.genymobile.scrcpy;

import static android.media.MediaFormat.KEY_MAX_FPS_TO_ENCODER;

import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.media.Image;
import android.util.Log;

import java.io.ByteArrayOutputStream;

import android.media.ImageReader;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Message;

import java.nio.ByteOrder;

import android.os.Process;
import android.os.HandlerThread;

public class ScreenEncoder implements Device.RotationListener {
    public static boolean reconnect = false;
    public static boolean videoMode = true;
    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final long PACKET_FLAG_CONFIG = 1L << 63;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 62;
    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final AtomicInteger mRotation = new AtomicInteger(0);
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    private int bitRate;
    private int maxFps;
    private int iFrameInterval;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    private int quality;
    private int scale;
    private boolean controlOnly;
    private Handler mHandler;
    private ImageReader mImageReader;
    private HandlerThread mHandlerThread;
    private ImageReader.OnImageAvailableListener imageAvailableListenerImpl;

    private Device device;
    private final Object rotationLock = new Object();
    private final Object imageReaderLock = new Object();
    private boolean bImageReaderDisable = true;//Segmentation fault
    private final List<CodecOption> codecOptions = CodecOption.parse("");
    private boolean alive = true;
    private boolean firstFrameSent;

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps, int iFrameInterval) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.iFrameInterval = iFrameInterval;
    }

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps) {
        this(sendFrameMeta, bitRate, maxFps, DEFAULT_I_FRAME_INTERVAL);
    }

    public ScreenEncoder(Options options, Device device/*int rotation*/) {
        this.quality = options.getQuality();
        this.maxFps = options.getMaxFps();
        this.scale = options.getScale();
        this.controlOnly = options.getControlOnly();
        this.bitRate = options.getBitRate();
        this.device = device;
        mRotation.set(device.getRotation());

        mHandlerThread = new HandlerThread("ScrcpyImageReaderHandlerThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Ln.i("hander message: " + msg);
                if (msg.what == 1) {//exit
                    setAlive(false);
                    synchronized (rotationLock) {
                        rotationLock.notify();
                    }
                }
            }
        };
    }

    public void ModifySettings(int scale, int maxFPS, int quality){
        this.quality = quality;
        this.maxFps = maxFPS;
        this.scale = scale;
        this.imageAvailableListenerImpl = null;
    }

    @Override
    public void onRotationChanged(int rotation) {
        Ln.i("rotation: " + rotation);
        mRotation.set(rotation);
        rotationChanged.set(true);
        synchronized (rotationLock) {
            rotationLock.notify();
        }
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    private class ImageAvailableListenerImpl implements ImageReader.OnImageAvailableListener {
        Handler handler;
        FileDescriptor fd;
        Device device;
        int type = 0;// 0:libjpeg-turbo 1:bitmap
        int quality;
        int framePeriodMs;

        int count = 0;
        long lastTime = System.currentTimeMillis();
        long timeA = lastTime;

        public ImageAvailableListenerImpl(Handler handler, Device device, FileDescriptor fd, int frameRate, int quality) {
            this.handler = handler;
            this.fd = fd;
            this.device = device;
            this.quality = quality;
            this.framePeriodMs = (int) (1000 / frameRate);
        }

        @Override
        public void onImageAvailable(ImageReader imageReader) {
            byte[] jpegData = null;
            byte[] jpegSize = null;
            Image image = null;

            synchronized (imageReaderLock) {
                try {
                    //change mode to video mode
                    if (videoMode){
                        synchronized (rotationLock) {
                            rotationLock.notify();
                        }
                    }

                    if (bImageReaderDisable) {
                        Ln.i("bImageReaderDisable !!!!!!!!!");
                        return;
                    }
                    image = imageReader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }

                    long currentTime = System.currentTimeMillis();
                    if (framePeriodMs > currentTime - lastTime) {
                        return;
                    }
                    lastTime = currentTime;

                    int width = image.getWidth();
                    int height = image.getHeight();
                    int format = image.getFormat();//RGBA_8888 0x00000001
                    final Image.Plane[] planes = image.getPlanes();
                    final ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;
                    int pitch = width + rowPadding / pixelStride;
//                    Ln.i("pitch: " + pitch + ", pixelStride: " + pixelStride + ", rowStride: " + rowStride + ", rowPadding: " + rowPadding);
                    if (type == 0) {
                        try{
                            jpegData = JpegEncoder.compress(buffer, width, pitch, height, quality);
                        }catch (UnsatisfiedLinkError e){
                            Log.i("scrcpy jpegData error", "convert to bitmap mode");
                            type = 1;
                        }

                    }
                    if (type == 1) {
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        Bitmap bitmap = Bitmap.createBitmap(pitch, height, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
                        jpegData = stream.toByteArray();
                        bitmap.recycle();
                    }
                    if (jpegData == null) {
                        Ln.e("jpegData is null");
                        return;
                    }
                    ByteBuffer b = ByteBuffer.allocate(4 + jpegData.length);
                    b.order(ByteOrder.LITTLE_ENDIAN);
                    b.putInt(jpegData.length);
                    b.put(jpegData);
                    jpegSize = b.array();
                    try {
                        IO.writeFully(fd, jpegSize, 0, jpegSize.length);// IOException
                    } catch (IOException e) {
                        Common.stopScrcpy(handler, "image");
                    }
                } catch (Exception e) {
                    Ln.e("onImageAvailable: " + e.getMessage());
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }

            count++;
            long timeB = System.currentTimeMillis();
            if (timeB - timeA >= 1000) {
                timeA = timeB;
                Ln.i("frame rate: " + count + ", jpeg size: " + jpegSize.length);
                count = 0;
            }
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    @SuppressLint("WrongConstant")
    public void streamScreen(Device device, FileDescriptor fd) throws IOException {
        Workarounds.prepareMainLooper();
        Workarounds.fillAppInfo();

        device.setRotationListener(this);
        MediaFormat format = createFormat(bitRate, maxFps, codecOptions);
        boolean alive = false;
        try {
            writeMinicapBanner(device, fd, scale);
            do {
                writeRotation(fd);
                if (controlOnly) {
                    synchronized (rotationLock) {
                        try {
                            rotationLock.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                } else {
                    if (!videoMode){
                        IBinder display = createDisplay();
                        Rect contentRect = device.getScreenInfo().getContentRect();
//                    Rect videoRect = device.getScreenInfo().getVideoSize().toRect();
                        Rect videoRect = getDesiredSize(contentRect, scale);

                        synchronized (imageReaderLock) {
                            mImageReader = ImageReader.newInstance(videoRect.width(), videoRect.height(), PixelFormat.RGBA_8888, 2);
                            bImageReaderDisable = false;
                        }
                        if (imageAvailableListenerImpl == null) {
                            if (maxFps > 30){
                                imageAvailableListenerImpl = new ImageAvailableListenerImpl(mHandler, device, fd, 30, quality);
                            }else{
                                imageAvailableListenerImpl = new ImageAvailableListenerImpl(mHandler, device, fd, maxFps, quality);
                            }
                        }
                        mImageReader.setOnImageAvailableListener(imageAvailableListenerImpl, mHandler);
                        Surface surface = mImageReader.getSurface();
                        setDisplaySurface(display, surface, contentRect, videoRect, 0);
                        // this lock is used for image cycle. It will not exist unless rotate or video mode
                        synchronized (rotationLock) {
                            try {
                                rotationLock.wait();
                            } catch (InterruptedException e) {
                                Ln.e("Rotate error " + e);
                            }
                        }
                        synchronized (imageReaderLock) {
                            if (mImageReader != null) {
                                bImageReaderDisable = true;
                                mImageReader.close();
                            }
                        }
                        destroyDisplay(display);
                        surface.release();
                        alive = getAlive();
                    }else{
                        MediaCodec codec = null;
                        try{
                            codec = createCodec(null);
                        }catch (Exception e){
                            Ln.e("step" + e);
                        }

                        IBinder display = createDisplay();
                        Rect contentRect = device.getScreenInfo().getContentRect();
                        Rect videoRect = getDesiredSize(contentRect, scale);
                        setSize(format, videoRect.width(), videoRect.height());
                        Surface surface = null;
                        try{
                            configure(codec, format);
                            surface = codec.createInputSurface();
                            setDisplaySurface(display, surface, contentRect, videoRect, mRotation.get());
                            codec.start();
                            alive = encode(codec, fd);
                            // do not call stop() on exception, it would trigger an IllegalStateException
                            codec.stop();
                        }catch (IllegalStateException | IllegalArgumentException e) {
                            Ln.e("Encoding error: " + e.getClass().getName() + ": " + e.getMessage());

                            int newMaxSize = chooseMaxSizeFallback(new Size(videoRect.width(), videoRect.height()) );
                            if (newMaxSize == 0) {
                                // Definitively fail
                                throw e;
                            }
                            // Retry with a smaller device size
                            Ln.i("Retrying with -m" + newMaxSize + "...");
//                        device.setMaxSize(newMaxSize);
                            alive = true;
                        }finally {
                            destroyDisplay(display);
                            codec.release();
                            if (surface != null)
                                surface.release();
                        }
                    }
                }
            } while (alive);
        } catch (Exception e) {
            e.printStackTrace();
            Ln.e("streamScreen: " + e.getMessage());
        } finally {
            if (mHandlerThread != null) {
                mHandlerThread.quit();
            }
            device.setRotationListener(null);
        }
    }
    private static int chooseMaxSizeFallback(Size failedSize) {
        int currentMaxSize = Math.max(failedSize.getWidth(), failedSize.getHeight());
        for (int value : MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                // We found a smaller value to reduce the video size
                return value;
            }
        }
        // No fallback, fail definitively
        return 0;
    }
    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean encode(MediaCodec codec, FileDescriptor fd) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange() || reconnect || !videoMode) {
                    reconnect = false;
                    // must restart encoding with new size
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    if (sendFrameMeta) {
                        writeFrameMeta(fd, bufferInfo, codecBuffer.remaining());
                    }

                    IO.writeFully(fd, codecBuffer);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        // If this is not a config packet, then it contains a frame
                        firstFrameSent = true;
                    }
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof;
    }
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static MediaCodec createCodec(String encoderName) throws IOException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                MediaCodecInfo[] encoders = listEncoders();
                throw new InvalidEncoderException(encoderName, encoders);
            }
        }
        MediaCodec codec = null;
        try{
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        }catch (Exception e){
            Ln.e("step " + e);
        }

        Ln.d("Using encoder: '" + codec.getName() + "'");
        return codec;
    }
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static MediaCodecInfo[] listEncoders() {
        List<MediaCodecInfo> result = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo codecInfo : list.getCodecInfos()) {
            if (codecInfo.isEncoder() && Arrays.asList(codecInfo.getSupportedTypes()).contains(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                result.add(codecInfo);
            }
        }
        return result.toArray(new MediaCodecInfo[result.size()]);
    }
    private void writeFrameMeta(FileDescriptor fd, MediaCodec.BufferInfo bufferInfo, int packetSize) throws IOException {
        headerBuffer.clear();

        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = PACKET_FLAG_CONFIG; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                pts |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }
    private static MediaFormat createFormat(int bitRate, int maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
//        format.setInteger("profile", 1); // Profile base
//        format.setInteger("level", 0x01); // Level 1
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                setCodecOption(format, option);
            }
        }

        return format;
    }
    private static void setCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        Object value = codecOption.getValue();

        if (value instanceof Integer) {
            format.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            format.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            format.setFloat(key, (Float) value);
        } else if (value instanceof String) {
            format.setString(key, (String) value);
        }

        Ln.d("Codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
    }

    private Rect getDesiredSize(Rect contentRect, int resolution) {
        int realWidth = contentRect.width();
        int realHeight = contentRect.height();

        int desiredWidth = realWidth;
        int desiredHeight = realHeight;
        int h = realHeight;
        if (realWidth < realHeight) {
            h = realWidth;
        }
        if (h > resolution) {
            desiredWidth = contentRect.width() * resolution / h;
            desiredHeight = contentRect.height() * resolution / h;
            desiredWidth = (desiredWidth + 4) & ~7;
            desiredHeight = (desiredHeight + 4) & ~7;
        } else {
            desiredWidth &= ~7;
            desiredHeight &= ~7;
        }

        if (desiredHeight < desiredWidth && videoMode){
            int tmp = desiredHeight;
            desiredHeight = desiredWidth;
            desiredWidth = tmp;
        }

        Ln.i("realWidth: " + realWidth + ", realHeight: " + realHeight + ", desiredWidth: " + desiredWidth + ", desiredHeight: " + desiredHeight);
        return new Rect(0, 0, desiredWidth, desiredHeight);
    }
    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }
    private void writeRotation(FileDescriptor fd) {
        ByteBuffer r = ByteBuffer.allocate(8);
        r.order(ByteOrder.LITTLE_ENDIAN);
        r.putInt(4);
        r.putInt(mRotation.get());
        byte[] rArray = r.array();
        try {
            IO.writeFully(fd, rArray, 0, rArray.length);// IOException
        } catch (IOException e) {
            Common.stopScrcpy(getHandler(), "rotation");
        }
    }

    private void writeMinicapBanner(Device device, FileDescriptor fd, int scale) throws IOException {
        final byte BANNER_SIZE = 24;
        final byte version = 1;
        final byte quirks = 2;
        int pid = Process.myPid();
        Rect contentRect = device.getScreenInfo().getContentRect();
        Rect videoRect = getDesiredSize(contentRect, scale);
        int realWidth = contentRect.width();
        int realHeight = contentRect.height();
        int desiredWidth = videoRect.width();
        int desiredHeight = videoRect.height();
        byte orientation = (byte) device.getRotation();

        ByteBuffer b = ByteBuffer.allocate(BANNER_SIZE);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) version);//version
        b.put(BANNER_SIZE);//banner size
        b.putInt(pid);//pid
        b.putInt(realWidth);//real width
        b.putInt(realHeight);//real height
        b.putInt(desiredWidth);//desired width
        b.putInt(desiredHeight);//desired height
        b.put((byte) orientation);//orientation
        b.put((byte) quirks);//quirks
        byte[] array = b.array();
        Ln.e(fd.toString());
        IO.writeFully(fd, array, 0, array.length);// IOException
        Ln.i("banner\n"
                + "{\n"
                + "    version: " + version + "\n"
                + "    size: " + BANNER_SIZE + "\n"
                + "    real width: " + realWidth + "\n"
                + "    real height: " + realHeight + "\n"
                + "    desired width: " + desiredWidth + "\n"
                + "    desired height: " + desiredHeight + "\n"
                + "    orientation: " + orientation + "\n"
                + "    quirks: " + quirks + "\n"
                + "}\n"
        );
    }

    private static IBinder createDisplay() {
        boolean secure = Build.VERSION.SDK_INT <= 30;
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int orientation) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            if (orientation > 0 && videoMode){
                displayRect = new Rect(0, 0, displayRect.height(), displayRect.width());
            }
//            Ln.i("set size realWidth: " + deviceRect.width() + ", realHeight: " + deviceRect.height() + ", desiredWidth: " + displayRect.width() + ", desiredHeight: " + displayRect.height());
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, 0);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }

    private synchronized boolean getAlive() {
        return alive;
    }

    private synchronized void setAlive(boolean b) {
        alive = b;
    }
}
