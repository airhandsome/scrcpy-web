package com.genymobile.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressLint("PrivateApi")
public final class ServiceManager {
    private final Method getServiceMethod;

    private static WindowManager windowManager;
    private static DisplayManager displayManager;
    private static InputManager inputManager;
    private static PowerManager powerManager;
    private static StatusBarManager statusBarManager;
    private static ClipboardManager clipboardManager;
    private static ActivityManager activityManager;

    public ServiceManager() {
        try {
            getServiceMethod = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) getServiceMethod.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = new WindowManager(getService("window", "android.view.IWindowManager"));
        }
        return windowManager;
    }

    public static DisplayManager getDisplayManager() {
        if (displayManager == null) {
            try {
                Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
                Method getInstanceMethod = clazz.getDeclaredMethod("getInstance");
                Object dmg = getInstanceMethod.invoke(null);
                displayManager = new DisplayManager(dmg);
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }
        return displayManager;
    }

    public InputManager getInputManager() {
        if (inputManager == null) {
            inputManager = new InputManager(getService("input", "android.hardware.input.IInputManager"));
        }
        return inputManager;
    }

    public PowerManager getPowerManager() {
        if (powerManager == null) {
            powerManager = new PowerManager(getService("power", "android.os.IPowerManager"));
        }
        return powerManager;
    }

    public StatusBarManager getStatusBarManager() {
        if (statusBarManager == null) {
            statusBarManager = new StatusBarManager(getService("statusbar", "com.android.internal.statusbar.IStatusBarService"));
        }
        return statusBarManager;
    }

    public ClipboardManager getClipboardManager() {
        if (clipboardManager == null) {
            clipboardManager = new ClipboardManager(getService("clipboard", "android.content.IClipboard"));
        }
        return clipboardManager;
    }
    public static ActivityManager getActivityManager() {
        if (activityManager == null) {
            try {
                // On old Android versions, the ActivityManager is not exposed via AIDL,
                // so use ActivityManagerNative.getDefault()
                Class<?> cls = Class.forName("android.app.ActivityManagerNative");
                Method getDefaultMethod = cls.getDeclaredMethod("getDefault");
                IInterface am = (IInterface) getDefaultMethod.invoke(null);
                activityManager = new ActivityManager(am);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        return activityManager;
    }
}
