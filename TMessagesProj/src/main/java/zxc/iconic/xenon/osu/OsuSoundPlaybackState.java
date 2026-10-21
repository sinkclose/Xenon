package zxc.iconic.xenon.osu;

/** UI owns transitions; the PCM thread only reads whether beats should be mixed. */
public final class OsuSoundPlaybackState {
    private volatile boolean enabled, visible;

    public boolean toggle() { enabled = !enabled; return enabled; }
    public void setVisible(boolean visible, boolean keepPlaying) {
        this.visible = visible;
        if (!visible && !keepPlaying) enabled = false;
    }
    public void keepPlayingChanged(boolean keepPlaying) {
        if (!visible && !keepPlaying) enabled = false;
    }
    public boolean beatsEnabled(boolean keepPlaying) { return enabled && (visible || keepPlaying); }
}
