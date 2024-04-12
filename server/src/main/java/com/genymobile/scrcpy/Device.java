package com.genymobile.scrcpy;

import android.content.IOnPrimaryClipChangedListener;
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

import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;
import com.genymobile.scrcpy.wrappers.WindowManager;

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
    private final AtomicBoolean isSettingClipboard = new AtomicBoolean();
    private final int displayId;
    private ScreenInfo screenInfo;
    private RotationListener rotationListener;
    private ClipboardListener clipboardListener;
    private final boolean supportsInputEvents;
    private FoldListener foldListener;
    private Size deviceSize;
    public Device(Options options) {
        displayId = options.getDisplayId();

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


        if (true) {
            // If control and autosync are enabled, synchronize Android clipboard to the computer automatically
            ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(new IOnPrimaryClipChangedListener.Stub() {
                    @Override
                    public void dispatchPrimaryClipChanged() {
                        if (isSettingClipboard.get()) {
                            // This is a notification for the change we are currently applying, ignore it
                            return;
                        }
                        synchronized (Device.this) {
                            if (clipboardListener != null) {
                                String text = getClipboardText();
                                if (text != null) {
                                    clipboardListener.onClipboardTextChanged(text);
                                }
                            }
                        }
                    }
                });
            } else {
                Ln.w("No clipboard manager, copy-paste between device and computer will not work");
            }
        }

        supportsInputEvents = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (!supportsInputEvents) {
            Ln.w("Input events are not supported for secondary displays before Android 10");
        }
    }
    public interface ClipboardListener {
        void onClipboardTextChanged(String text);
    }
    public synchronized ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    private ScreenInfo computeScreenInfo(Rect crop, int maxSize) {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(0);
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
    public synchronized void setClipboardListener(ClipboardListener clipboardListener) {
        this.clipboardListener = clipboardListener;
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
    public boolean supportsInputEvents() {
        return supportsInputEvents;
    }
    public boolean isScreenOn() {
        return ServiceManager.getPowerManager().isScreenOn();
    }

    public void registerRotationWatcher(IRotationWatcher rotationWatcher) {
        ServiceManager.getWindowManager().registerRotationWatcher(rotationWatcher, 0);
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
    public void setScreenPowerMode(int mode) {
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

        boolean accelerometerRotation = !wm.isRotationFrozen(0);

        int currentRotation = wm.getRotation();
        int newRotation = (currentRotation & 1) ^ 1; // 0->1, 1->0, 2->1, 3->0
        String newRotationString = newRotation == 0 ? "portrait" : "landscape";

        Ln.i("Device rotation requested: " + newRotationString);
        wm.freezeRotation(0, newRotation);

        // restore auto-rotate if necessary
        if (accelerometerRotation) {
            wm.thawRotation(0);
        }
    }

    static Rect flipRect(Rect crop) {
        return new Rect(crop.top, crop.left, crop.bottom, crop.right);
    }

    public int getRotation() {
        return ServiceManager.getWindowManager().getRotation();
    }

    public boolean injectTextPaste(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }

        // On Android >= 7, we can inject UTF-8 text as follow:
        // - set the clipboard
        // - inject the PASTE key event
        // - restore the clipboard

        String clipboardBackup = getClipboardText();
        isSettingClipboard.set(true);

        setClipboardText(text);
        boolean ok = injectKeycode(KeyEvent.KEYCODE_PASTE, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT);
        setClipboardText(clipboardBackup);

        isSettingClipboard.set(false);
        return ok;
    }

    private boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event);
    }
    public boolean injectKeycode(int keyCode, int mode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0) && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0, mode);
    }
    private boolean injectKeycode(int keyCode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0) && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0);
    }
    public boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int mode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event, mode);
    }

    private boolean injectEvent(InputEvent event) {
        return injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public boolean injectEvent(InputEvent inputEvent, int mode) {
        if (!supportsInputEvents()) {
            Ln.e("Could not inject input event if !supportsInputEvents()");
//            throw new AssertionError("Could not inject input event if !supportsInputEvents()");
        }

        return ServiceManager.getInputManager().injectInputEvent(inputEvent, mode);
    }
    public interface FoldListener {
        void onFoldChanged(int displayId, boolean folded);
    }
    public synchronized void setFoldListener(FoldListener foldlistener) {
        this.foldListener = foldlistener;
    }
}
