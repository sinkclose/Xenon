package zxc.iconic.xenon.helpers;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.bots.WebViewRequestProps;

/**
 * TL object viewer and internal WebApp labelling. Opening the TLV WebApp host was tied to a removed
 * helper-bot flow; {@link #openTLViewer} is a no-op until reimplemented without that dependency.
 */
public class WebAppHelper {
    public static final int INTERNAL_BOT_TLV = 1;

    public static boolean isInternalBot(WebViewRequestProps props) {
        return props.internalType > 0;
    }

    public static String getInternalBotName(WebViewRequestProps props) {
        switch (props.internalType) {
            case INTERNAL_BOT_TLV:
                return LocaleController.getString(R.string.ViewAsJson);
            default:
                return "";
        }
    }

    public static void openTLViewer(BaseFragment fragment, TLObject object) {
    }

    public static class CleanSerializedData extends SerializedData {
        public CleanSerializedData(int size) {
            super(size);
        }
    }
}
