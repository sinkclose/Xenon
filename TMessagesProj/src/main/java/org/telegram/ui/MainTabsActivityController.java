package org.telegram.ui;

public interface MainTabsActivityController {
    void setTabsVisible(boolean visible);
    default void invalidateTabsBackground() {}
    default void attachIosBottomSearch(DialogsActivity owner, org.telegram.ui.Components.FragmentSearchField field, Runnable open, Runnable close) {}
    default void setIosBottomSearchActive(boolean active, boolean animated) {}
    default void detachIosBottomSearch(org.telegram.ui.Components.FragmentSearchField field) {}
}
