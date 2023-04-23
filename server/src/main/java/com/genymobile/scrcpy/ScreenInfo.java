package com.genymobile.scrcpy;

import android.graphics.Rect;

public final class ScreenInfo {
    private final Rect contentRect; // device size, possibly cropped

    /**
     * Video size, possibly smaller than the device size, already taking the device rotation and crop into account.
     * <p>
     * However, it does not include the locked video orientation.
     */
    private final Size unlockedVideoSize;
    /**
     * Device rotation, related to the natural device orientation (0, 1, 2 or 3)
     */
    private final int deviceRotation;

    /**
     * The locked video orientation (-1: disabled, 0: normal, 1: 90° CCW, 2: 180°, 3: 90° CW)
     */
    private final int lockedVideoOrientation;


    public ScreenInfo(Rect contentRect, Size unlockedVideoSize, int deviceRotation, int lockedVideoOrientation) {
        this.contentRect = contentRect;
        this.unlockedVideoSize = unlockedVideoSize;
        this.deviceRotation = deviceRotation;
        this.lockedVideoOrientation = lockedVideoOrientation;
    }

    public Rect getContentRect() {
        return contentRect;
    }


    public Size getUnlockedVideoSize() {
        return unlockedVideoSize;
    }
    private static Rect flipRect(Rect crop) {
        return new Rect(crop.top, crop.left, crop.bottom, crop.right);
    }

    /**
     * Return the rotation to apply to the device rotation to get the requested locked video orientation
     *
     * @return the rotation offset
     */
    public int getVideoRotation() {
        if (lockedVideoOrientation == -1) {
            // no offset
            return 0;
        }
        return (deviceRotation + 4 - lockedVideoOrientation) % 4;
    }

    /**
     * Return the rotation to apply to the requested locked video orientation to get the device rotation
     *
     * @return the (reverse) rotation offset
     */
    public int getReverseVideoRotation() {
        if (lockedVideoOrientation == -1) {
            // no offset
            return 0;
        }
        return (lockedVideoOrientation + 4 - deviceRotation) % 4;
    }
    public ScreenInfo withDeviceRotation(int newDeviceRotation) {
        if (newDeviceRotation == deviceRotation) {
            return this;
        }
        // true if changed between portrait and landscape
        boolean orientationChanged = (deviceRotation + newDeviceRotation) % 2 != 0;
        Rect newContentRect;
        Size newUnlockedVideoSize;
        if (orientationChanged) {
            newContentRect = flipRect(contentRect);
            newUnlockedVideoSize = unlockedVideoSize.rotate();
        } else {
            newContentRect = contentRect;
            newUnlockedVideoSize = unlockedVideoSize;
        }
        return new ScreenInfo(newContentRect, newUnlockedVideoSize, newDeviceRotation, lockedVideoOrientation);
    }
}
