package com.genymobile.scrcpy;

import android.graphics.Rect;

public final class ScreenInfo {
    private final Rect contentRect; // device size, possibly cropped
    private final Size videoSize;
    private final Rect videoRect;
    private final boolean rotated;

    public ScreenInfo(Rect contentRect, Size videoSize, boolean rotated) {
        this.contentRect = contentRect;
        this.videoSize = videoSize;
        this.videoRect = new Rect(0, 0, videoSize.getWidth(), videoSize.getHeight());
        this.rotated = rotated;
    }

    public Rect getContentRect() {
        return contentRect;
    }

    public Rect getVideoRect(){return videoRect;}

    public Size getVideoSize() {
        return videoSize;
    }

    public ScreenInfo withRotation(int rotation) {
        boolean newRotated = (rotation & 1) != 0;
        if (rotated == newRotated) {
            return this;
        }
        return new ScreenInfo(Device.flipRect(contentRect), videoSize.rotate(), newRotated);
    }

}
