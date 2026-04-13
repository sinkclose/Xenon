package zxc.iconic.xenon.settings;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoCameraSettingsActivity extends BaseNekoSettingsActivity {

    private final int useCamera2ApiRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.CameraSettings)));
        items.add(UItem.asCheck(useCamera2ApiRow, LocaleController.getString(R.string.UseCamera2Api), LocaleController.getString(R.string.UseCamera2ApiDesc)).slug("useCamera2Api").setChecked(NekoConfig.useCamera2Api));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == useCamera2ApiRow) {
            NekoConfig.toggleUseCamera2Api();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useCamera2Api);
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.CameraSettings);
    }

    @Override
    protected String getKey() {
        return "camera";
    }
}
