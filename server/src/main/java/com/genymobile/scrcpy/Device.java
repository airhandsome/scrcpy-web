package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;
import com.genymobile.scrcpy.wrappers.WindowManager;

import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.IRotationWatcher;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Device {

    public static final int POWER_MODE_OFF = SurfaceControl.POWER_MODE_OFF;
    public static final int POWER_MODE_NORMAL = SurfaceControl.POWER_MODE_NORMAL;

    public static final int INJECT_MODE_ASYNC = InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
    public static final int INJECT_MODE_WAIT_FOR_RESULT = InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT;
    public static final int INJECT_MODE_WAIT_FOR_FINISH = InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH;

    public static final int LOCK_VIDEO_ORIENTATION_UNLOCKED = -1;
    public static final int LOCK_VIDEO_ORIENTATION_INITIAL = -2;
    public interface RotationListener {
        void onRotationChanged(int rotation);
    }

    private ScreenInfo screenInfo;
    private RotationListener rotationListener;
    private ClipboardListener clipboardListener;
    private final AtomicBoolean isSettingClipboard = new AtomicBoolean();

    public Device(Options options) {
        screenInfo = computeScreenInfo(options.getCrop(), options.getMaxSize());
        registerRotationWatcher(new IRotationWatcher.Stub() {
            @Override
            public void onRotationChanged(int rotation) throws RemoteException {
                synchronized (Device.this) {
                    screenInfo = screenInfo.withRotation(rotation);

                    // notify
                    if (rotationListener != null) {
                        rotationListener.onRotationChanged(rotation);
                    }
                }
            }
        });
    }

    public interface ClipboardListener {
        void onClipboardTextChanged(String text);
    }

    public synchronized ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    private ScreenInfo computeScreenInfo(Rect crop, int maxSize) {
        //wait
        int displayId = 0;
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        boolean rotated = (displayInfo.getRotation() & 1) != 0;
        Size deviceSize = displayInfo.getSize();
        Rect contentRect = new Rect(0, 0, deviceSize.getWidth(), deviceSize.getHeight());
        if (crop != null) {
            if (rotated) {
                // the crop (provided by the user) is expressed in the natural orientation
                crop = flipRect(crop);
            }
            if (!contentRect.intersect(crop)) {
                // intersect() changes contentRect so that it is intersected with crop
                Ln.w("Crop rectangle (" + formatCrop(crop) + ") does not intersect device screen (" + formatCrop(deviceSize.toRect()) + ")");
                contentRect = new Rect(); // empty
            }
        }

        Size videoSize = computeVideoSize(contentRect.width(), contentRect.height(), maxSize);
        return new ScreenInfo(contentRect, videoSize, rotated);
    }

    private static String formatCrop(Rect rect) {
        return rect.width() + ":" + rect.height() + ":" + rect.left + ":" + rect.top;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static Size computeVideoSize(int w, int h, int maxSize) {
        // Compute the video size and the padding of the content inside this video.
        // Principle:
        // - scale down the great side of the screen to maxSize (if necessary);
        // - scale down the other side so that the aspect ratio is preserved;
        // - round this value to the nearest multiple of 8 (H.264 only accepts multiples of 8)
        w &= ~7; // in case it's not a multiple of 8
        h &= ~7;
        if (maxSize > 0) {
            if (BuildConfig.DEBUG && maxSize % 8 != 0) {
                throw new AssertionError("Max size must be a multiple of 8");
            }
            boolean portrait = h > w;
            int major = portrait ? h : w;
            int minor = portrait ? w : h;
            if (major > maxSize) {
                int minorExact = minor * maxSize / major;
                // +4 to round the value to the nearest multiple of 8
                minor = (minorExact + 4) & ~7;
                major = maxSize;
            }
            w = portrait ? minor : major;
            h = portrait ? major : minor;
        }
        return new Size(w, h);
    }

    public Point getPhysicalPoint(Position position) {
        // it hides the field on purpose, to read it with a lock
        @SuppressWarnings("checkstyle:HiddenField")
        ScreenInfo screenInfo = getScreenInfo(); // read with synchronization
        Size videoSize = screenInfo.getVideoSize();
        Size clientVideoSize = position.getScreenSize();
        if (!videoSize.equals(clientVideoSize)) {
            Ln.i("video width: " + videoSize.getWidth() + ", video height: " + videoSize.getHeight());
            Ln.i("client width: " + clientVideoSize.getWidth() + ", client height: " + clientVideoSize.getHeight());
            // The client sends a click relative to a video with wrong dimensions,
            // the device may have been rotated since the event was generated, so ignore the event
            return null;
        }
        Rect contentRect = screenInfo.getContentRect();
        Point point = position.getPoint();
        int scaledX = contentRect.left + point.getX() * contentRect.width() / videoSize.getWidth();
        int scaledY = contentRect.top + point.getY() * contentRect.height() / videoSize.getHeight();
        return new Point(scaledX, scaledY);
    }

    public static String getDeviceName() {
        return Build.MODEL;
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        return ServiceManager.getInputManager().injectInputEvent(inputEvent, mode);
    }


    public void registerRotationWatcher(IRotationWatcher rotationWatcher) {
        ServiceManager.getWindowManager().registerRotationWatcher(rotationWatcher);
    }

    public synchronized void setRotationListener(RotationListener rotationListener) {
        this.rotationListener = rotationListener;
    }

    public void expandNotificationPanel() {
        ServiceManager.getStatusBarManager().expandNotificationsPanel();
    }

    public void collapsePanels() {
        ServiceManager.getStatusBarManager().collapsePanels();
    }

    public String getClipboardText() {
        CharSequence s = ServiceManager.getClipboardManager().getText();
        if (s == null) {
            return null;
        }
        return s.toString();
    }

    public void setClipboardText(String text) {
        ServiceManager.getClipboardManager().setText(text);
        Ln.i("Device clipboard set");
    }

    /**
     * @param mode one of the {@code SCREEN_POWER_MODE_*} constants
     */
    public static void setScreenPowerMode(int mode) {
        IBinder d = SurfaceControl.getBuiltInDisplay();
        if (d == null) {
            Ln.e("Could not get built-in display");
            return;
        }
        SurfaceControl.setDisplayPowerMode(d, mode);
        Ln.i("Device screen turned " + (mode == Device.POWER_MODE_OFF ? "off" : "on"));
    }

    /**
     * Disable auto-rotation (if enabled), set the screen rotation and re-enable auto-rotation (if it was enabled).
     */
    public void rotateDevice() {
        WindowManager wm = ServiceManager.getWindowManager();

        boolean accelerometerRotation = !wm.isRotationFrozen();

        int currentRotation = wm.getRotation();
        int newRotation = (currentRotation & 1) ^ 1; // 0->1, 1->0, 2->1, 3->0
        String newRotationString = newRotation == 0 ? "portrait" : "landscape";

        Ln.i("Device rotation requested: " + newRotationString);
        wm.freezeRotation(newRotation);

        // restore auto-rotate if necessary
        if (accelerometerRotation) {
            wm.thawRotation();
        }
    }

    static Rect flipRect(Rect crop) {
        return new Rect(crop.top, crop.left, crop.bottom, crop.right);
    }

    public int getRotation() {
        return ServiceManager.getWindowManager().getRotation();
    }

    public static boolean isScreenOn() {
        return ServiceManager.getPowerManager().isScreenOn();
    }
    public static boolean pressReleaseKeycode(int keyCode, int displayId, int injectMode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0, displayId, injectMode)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0, displayId, injectMode);
    }
    public static boolean supportsInputEvents(int displayId) {
        return displayId == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }
    public static boolean injectEvent(InputEvent inputEvent, int displayId, int injectMode) {
        if (!supportsInputEvents(displayId)) {
            throw new AssertionError("Could not inject input event if !supportsInputEvents()");
        }

        if (displayId != 0 && !InputManager.setDisplayId(inputEvent, displayId)) {
            return false;
        }

        return ServiceManager.getInputManager().injectInputEvent(inputEvent, injectMode);
    }
    public static boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int injectMode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event, displayId, injectMode);
    }
    public static boolean powerOffScreen(int displayId) {
        if (!isScreenOn()) {
            return true;
        }
        return pressReleaseKeycode(KeyEvent.KEYCODE_POWER, displayId, Device.INJECT_MODE_ASYNC);
    }
    public synchronized void setClipboardListener(ClipboardListener clipboardListener) {
        this.clipboardListener = clipboardListener;
    }
}
