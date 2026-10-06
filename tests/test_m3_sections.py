"""Verify section boundaries using production Java methods without Android SDK."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class M3SectionsTest(unittest.TestCase):
    def test_adapter_boundaries_during_scroll_and_animation(self):
        helper = (ROOT / "zxc/iconic/xenon/helpers/M3SectionsHelper.java").read_text()
        recycler = (ROOT / "org/telegram/ui/Components/RecyclerListView.java").read_text()
        methods = "\n".join(method(helper, signature) for signature in (
            "private static float[] computeRadii(",
            "private static boolean isSectionRow(",
            "private static float[] m3Radii(",
            "public static boolean isHeaderViewType(",
        ))
        position_method = method(recycler, "public boolean isSectionPosition(")
        harness = r'''
import java.util.*;
public class M3SectionsHarness {
    interface Callback<T, R> { R run(T value); }
    static class View { int position; boolean section = true, mergedPrev, mergedNext; }
    static class HeaderCell extends View {}
    static class RecyclerView {
        static final int NO_POSITION = -1;
        static class Adapter<T> {
            int[] types;
            Adapter(int... types) { this.types = types; }
            int getItemCount() { return types.length; }
            int getItemViewType(int position) { return types[position]; }
        }
    }
    static class UniversalAdapter extends RecyclerView.Adapter<Object> {
        static final int VIEW_TYPE_HEADER = 0, VIEW_TYPE_BLACK_HEADER = 1,
            VIEW_TYPE_LARGE_HEADER = 26, VIEW_TYPE_ANIMATED_HEADER = 42;
        UniversalAdapter(int... types) { super(types); }
        static boolean isShadow(int type) { return type == 9; }
    }
    static class Decoration { Callback<View, Boolean> isSectionItem = view -> view.section; }
    static class RecyclerListView extends RecyclerView {
        Adapter<?> adapter;
        Decoration sectionsItemDecoration = new Decoration();
        Callback<Integer, Boolean> isViewTypeSection = type -> type != 9;
        Map<Integer, View> attached = new HashMap<>();
        Adapter<?> getAdapter() { return adapter; }
        View findViewByPosition(int position) { return attached.get(position); }
        int getChildAdapterPosition(View view) { return view.position; }
        POSITION_METHOD
    }
    static float outerRForChild(View view) { return 20; }
    static float getInnerR() { return 4; }
    static boolean isMergedWithPrev(View view) { return view.mergedPrev; }
    static boolean isMergedWithNext(View view) { return view.mergedNext; }
    HELPER_METHODS
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static View row(RecyclerListView list, int position) {
        View view = new View(); view.position = position;
        list.attached.put(position, view); return view;
    }
    static void radii(RecyclerListView list, View view, float top, float bottom) {
        float[] r = computeRadii(list, view);
        check(r[0] == top && r[1] == bottom);
    }
    public static void main(String[] args) {
        RecyclerListView list = new RecyclerListView();
        // Header, two rows, shadow, header, two rows, header, final row.
        list.adapter = new UniversalAdapter(0, 5, 5, 9, 26, 5, 5, 42, 5);
        // Only one visible row: offscreen neighbours still determine corners.
        View first = row(list, 1); radii(list, first, 20, 4);
        list.attached.clear();
        View last = row(list, 2); radii(list, last, 4, 20);
        list.attached.clear();
        View middle = row(list, 5); radii(list, middle, 20, 4);
        row(list, 8); row(list, 1); // unrelated children in arbitrary order
        radii(list, middle, 20, 4);
        View beforeHeader = row(list, 6); radii(list, beforeHeader, 4, 20);
        View single = row(list, 8); radii(list, single, 20, 20);
        single.position = RecyclerView.NO_POSITION;
        radii(list, single, 20, 20); // disappearing row, no invalid adapter lookup
        middle.mergedPrev = middle.mergedNext = true;
        radii(list, middle, 0, 0);
        // Custom attached rows use the decoration predicate.
        list.adapter = new RecyclerView.Adapter<>(5, 5, 5);
        list.attached.clear();
        View center = row(list, 1), separator = row(list, 2);
        separator.section = false;
        radii(list, center, 4, 20);
        HeaderCell header = new HeaderCell(); header.position = 0;
        list.attached.put(0, header);
        radii(list, center, 20, 20);
        list.sectionsItemDecoration = null;
        check(!list.isSectionPosition(0));
        list.adapter = null;
        radii(list, center, 20, 20);
    }
}
'''.replace("POSITION_METHOD", position_method).replace("HELPER_METHODS", methods)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "M3SectionsHarness.java"
            source.write_text(harness)
            compiled = subprocess.run(
                ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(source)],
                capture_output=True, text=True,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "M3SectionsHarness"], capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
