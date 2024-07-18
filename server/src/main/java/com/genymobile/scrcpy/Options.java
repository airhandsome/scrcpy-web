package com.genymobile.scrcpy;

import android.graphics.Rect;

import java.util.List;

public class Options {
    private int maxSize;
    private int bitRate;
    private int maxFps;
    private boolean tunnelForward;
    private Rect crop;
    private boolean sendFrameMeta; // send PTS so that the client may record properly
    private boolean control;
    private boolean audio;
    private int displayId;

    //wen add
    private int quality;
    private int scale;
    private boolean controlOnly;
    private boolean nalu;
    private boolean dumpHierarchy;

    private int audioBitRate = 128000;
    private String audioEncoder;
    private AudioCodec audioCodec = AudioCodec.RAW;
    private AudioSource audioSource = AudioSource.OUTPUT;
    private boolean sendCodecMeta = true; // write the codec metadata before the stream
    private List<CodecOption> audioCodecOptions;
    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setAudioBitRate(int bitRate){
        this.audioBitRate = bitRate;
    }
    public int getAudioBitRate() {
        return audioBitRate;
    }
    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }
    public String getAudioEncoder() {
        return audioEncoder;
    }
    public void setAudioEncoder(String encoder){
        this.audioEncoder = encoder;
    }

    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    public void setAudioCodec(AudioCodec codec){
        this.audioCodec = codec;
    }

    public AudioSource getAudioSource(){
        return audioSource;
    }

    public void setAudioSource(AudioSource audioSource){
        this.audioSource = audioSource;
    }
    public boolean getSendCodecMeta() {
        return sendCodecMeta;
    }
    public void setSendCodecMeta(boolean sendCodecMeta){
        this.sendCodecMeta = sendCodecMeta;
    }
    public List<CodecOption> getAudioCodecOptions() {
        return audioCodecOptions;
    }

    public void setAudioCodecOptions(List<CodecOption> audioCodecOptions){
        this.audioCodecOptions = audioCodecOptions;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = maxFps;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public void setTunnelForward(boolean tunnelForward) {
        this.tunnelForward = tunnelForward;
    }

    public Rect getCrop() {
        return crop;
    }

    public void setCrop(Rect crop) {
        this.crop = crop;
    }

    public boolean getSendFrameMeta() {
        return sendFrameMeta;
    }

    public void setSendFrameMeta(boolean sendFrameMeta) {
        this.sendFrameMeta = sendFrameMeta;
    }

    public boolean getControl() {
        return control;
    }

    public void setControl(boolean control) {
        this.control = control;
    }

    public boolean getAudio() {
        return audio;
    }

    public void setAudio(boolean audio) { this.audio = audio; }
    public int getQuality() {
        return quality;
    }

    public void setQuality(int quality) {
        this.quality = quality;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public boolean getControlOnly() {
        return controlOnly;
    }

    public void setControlOnly(boolean controlOnly) {
        this.controlOnly = controlOnly;
    }

    public boolean getNALU() {
        return nalu;
    }

    public void setNALU(boolean nalu) {
        this.nalu = nalu;
    }

    public boolean getDumpHierarchy() {
        return dumpHierarchy;
    }

    public void setDumpHierarchy(boolean dumpHierarchy) {
        this.dumpHierarchy = dumpHierarchy;
    }
    public int getDisplayId() {
        return displayId;
    }


}
