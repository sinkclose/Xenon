package zxc.iconic.xenon.osu;

/** danser-go spinner.go: screen overlays use a 768-high camera, separate from playfield sprites.
 * Reference revision 7cd903284017cb216fde596993dfa81dafca7af6; see danser-autoplay-GPL-3.0.txt.
 */
public final class OsuSpinnerLayout {
    private OsuSpinnerLayout(){}
    public static final float HEIGHT=768, RPM_HALF_WIDTH=139, RPM_TOP=HEIGHT-56,
            CLEAR_Y=256-16-8, SPIN_Y=608-12.8f-16, BACKGROUND_Y=46.5f+350.4f, METRE_TOP=47.5f;
    /** Translation and scale in the current playfield canvas, followed by logical screen width. */
    public static float[] overlay(float width,float height,float[] playfield){
        return new float[]{-playfield[1]/playfield[0],-playfield[2]/playfield[0],height/(HEIGHT*playfield[0]),width*HEIGHT/height};
    }
    public static float approachScale(double remaining){return .1f+1.8f*(float)Math.max(0,Math.min(1,remaining));}
}
