package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app logcat viewer. Works without root/adb: without READ_LOGS permission
 * Android only returns this app's own log lines, which is exactly what we need.
 */
public class NekoLogcatActivity extends BaseFragment {

    private static final int MAX_LINES = 3000;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private LogAdapter adapter;
    private EditText filterView;
    private TextView emptyView;

    private final List<String> allLines = new ArrayList<>();
    private String filterQuery = "";
    private volatile boolean destroyed;
    private int loadGeneration;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(true));
        actionBar.setTitle("Logcat");
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        filterView = new EditText(context);
        filterView.setHint("Filter, e.g. ProgFadeDBG");
        filterView.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        filterView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        filterView.setTextSize(16);
        filterView.setSingleLine(true);
        filterView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        int pad = AndroidUtilities.dp(12);
        filterView.setPadding(pad, pad, pad, pad);
        container.addView(filterView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        filterView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterQuery = s.toString().trim().toLowerCase();
                applyFilter();
            }
        });

        FrameLayout listWrap = new FrameLayout(context);
        listWrap.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        container.addView(listWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        adapter = new LogAdapter();
        listView.setAdapter(adapter);
        listWrap.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("No lines.\nOpen the screen to debug first,\nthen tap Refresh.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(14);
        listWrap.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        container.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        buttons.addView(makeButton(context, "Refresh", v -> reload()), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        buttons.addView(makeButton(context, "Clear", v -> clear()), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        buttons.addView(makeButton(context, "Copy", v -> copyVisible()), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        reload();
        return root;
    }

    private Button makeButton(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void reload() {
        final int generation = ++loadGeneration;
        new Thread(() -> {
            final List<String> lines = loadLogLines();
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || generation != loadGeneration) {
                    return;
                }
                allLines.clear();
                allLines.addAll(lines);
                applyFilter();
                if (!allLines.isEmpty()) {
                    Toast.makeText(getParentActivity(), "Loaded " + allLines.size() + " lines", Toast.LENGTH_SHORT).show();
                }
            });
        }, "neko-logcat").start();
    }

    private void clear() {
        final int generation = ++loadGeneration;
        new Thread(() -> {
            try {
                new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start().waitFor();
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || generation != loadGeneration) {
                    return;
                }
                allLines.clear();
                applyFilter();
            });
        }, "neko-logcat-clear").start();
    }

    private void copyVisible() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            sb.append(adapter.getLine(i)).append('\n');
            if (sb.length() > 400_000) {
                break;
            }
        }
        if (sb.length() == 0) {
            return;
        }
        AndroidUtilities.addToClipboard(sb.toString());
        Toast.makeText(getParentActivity(), "Copied", Toast.LENGTH_SHORT).show();
    }

    private void applyFilter() {
        adapter.setLines(filterLines());
        emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        if (adapter.getItemCount() > 0) {
            layoutManager.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private List<String> filterLines() {
        if (filterQuery.isEmpty()) {
            return new ArrayList<>(allLines);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < allLines.size(); i++) {
            String line = allLines.get(i);
            if (line.toLowerCase().contains(filterQuery)) {
                out.add(line);
            }
        }
        return out;
    }

    private List<String> loadLogLines() {
        List<String> out = new ArrayList<>();
        BufferedReader reader = null;
        try {
            Process process = new ProcessBuilder().command("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 8192);
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
            process.waitFor();
        } catch (Exception e) {
            FileLog.e(e);
            out.add("logcat failed: " + e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (out.size() > MAX_LINES) {
            out = new ArrayList<>(out.subList(out.size() - MAX_LINES, out.size()));
        }
        return out;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<String> lines = new ArrayList<>();

        void setLines(List<String> newLines) {
            lines.clear();
            lines.addAll(newLines);
            notifyDataSetChanged();
        }

        String getLine(int position) {
            return lines.get(position);
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView textView = new TextView(parent.getContext());
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextSize(11);
            textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            int pad = AndroidUtilities.dp(8);
            textView.setPadding(pad, AndroidUtilities.dp(2), pad, AndroidUtilities.dp(2));
            return new RecyclerListView.Holder(textView);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView).setText(lines.get(position));
        }
    }
}
