package zxc.iconic.xenon.settings;

import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;
import java.util.function.Consumer;

import zxc.iconic.xenon.helpers.ClientFontFiles;
import zxc.iconic.xenon.helpers.ClientFonts;

/** A virtualized font library, using the sheet's native nested scrolling and drag handling. */
final class ClientFontSheet extends BottomSheetWithRecyclerListView {
    private final List<ClientFontFiles.Entry> fonts;

    ClientFontSheet(BaseNekoSettingsActivity host, Consumer<String> select, Runnable importFile) {
        super(host, false, false, true, host.resourcesProvider);
        fonts = ClientFonts.list();
        topPadding = .25f;
        setShowHandle(true);
        recyclerListView.setVerticalScrollBarEnabled(true);
        recyclerListView.getAdapter().notifyDataSetChanged();
        recyclerListView.setOnItemClickListener((view, position) -> {
            // The sheet inserts its top padding as adapter position zero.
            int row = position - 1;
            if (row < 0 || row >= fonts.size() + 2) return;
            dismiss();
            if (row == fonts.size() + 1) importFile.run();
            else select.accept(row == 0 ? "" : fonts.get(row - 1).id);
        });
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ClientFont);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView list) {
        return new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }

            @Override
            public int getItemCount() { return (fonts == null ? 0 : fonts.size()) + 2; }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                TextSettingsCell cell = new TextSettingsCell(parent.getContext(), resourcesProvider);
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                boolean importRow = position == getItemCount() - 1;
                String label = importRow ? LocaleController.getString(R.string.ClientSelectFont)
                        : position == 0 ? LocaleController.getString(R.string.Default) : fonts.get(position - 1).name;
                boolean selected = !importRow && (position == 0 ? ClientFonts.selectedId().isEmpty()
                        : ClientFonts.selectedId().equals(fonts.get(position - 1).id));
                cell.setTextAndValue(label, selected ? "✓" : "", !importRow);
                cell.setTextColor(Theme.getColor(importRow ? Theme.key_windowBackgroundWhiteBlueText4 : Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                ClientFonts.applyToView(cell);
            }
        };
    }
}
