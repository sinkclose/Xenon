package zxc.iconic.xenon.helpers;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * TL object viewer and internal WebApp labelling. Opening the TLV WebApp host was tied to a removed
 * helper-bot flow; {@link #openTLViewer} is a no-op until reimplemented without that dependency.
 */
public class WebAppHelper {
    public static void openTLViewer(BaseFragment fragment, TLObject object) {
    }

    public static class CleanSerializedData extends SerializedData {
        public CleanSerializedData(int size) {
            super(size);
        }
    }
}
