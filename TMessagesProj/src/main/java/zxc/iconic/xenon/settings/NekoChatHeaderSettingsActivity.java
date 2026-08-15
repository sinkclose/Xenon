package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.NonIslandHelper;

public class NekoChatHeaderSettingsActivity extends BaseNekoSettingsActivity {

    private final int removeHeaderPillRow = rowId++;
    private final int centerHeaderRow = rowId++;
    private final int avatarPlacementRow = rowId++;
    private final int biggerAvatarRow = rowId++;
    private final int blurredFadeViewRow = rowId++;
    private final int progressiveFadeBlurRow = rowId++;
    private final int progressiveFadeBlurSamplesRow = rowId++;
    private final int progressiveFadeBlurRefreshRateRow = rowId++;
    private final int progressiveFadeBlurOtherActivitiesRow = rowId++;
    private final int blurredFadeBlurAmountRow = rowId++;
    private final int blurredFadePixelationRow = rowId++;
    private final int blurredFadeDimmingRow = rowId++;
    private final int blurredFadeDimStrengthRow = rowId++;

    private ActionBar previewActionBar;
    private ChatAvatarContainer previewAvatar;
    private FrameLayout previewContainer;
    private boolean previewM3 = false;
    private boolean previewCenter = false;
    private ActionBarMenuItem previewMoreItem;
    private BlurredBackgroundSource wallpaperSource;
    private BlurredBackgroundSourceRenderNode wallpaperRenderSource;
    private BlurredBackgroundSourceBitmap wallpaperBitmapSource;
    private boolean wallpaperCaptured;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Preview"));
        items.add(UItem.asCustom(getOrCreatePreviewContainer()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Header"));
        items.add(UItem.asCheck(removeHeaderPillRow, "Remove header pill").setChecked(NekoConfig.material3ChatHeaders).slug("removeHeaderPill"));
        items.add(UItem.asCheck(centerHeaderRow, LocaleController.getString(R.string.CenterChatHeader)).setChecked(NekoConfig.centerChatHeader).slug("centerHeader"));
        items.add(UItem.asCheck(biggerAvatarRow, "Bigger avatar").setChecked(NekoConfig.biggerAvatar).slug("biggerAvatar"));
        items.add(UItem.asCheck(blurredFadeViewRow, LocaleController.getString(R.string.BlurredFadeView)).setChecked(NekoConfig.blurredFadeView).slug("blurredFadeView"));
        items.add(UItem.asCheck(progressiveFadeBlurOtherActivitiesRow,
                LocaleController.getString(R.string.ProgressiveFadeBlurOtherActivities),
                LocaleController.getString(R.string.ProgressiveFadeBlurOtherActivitiesInfo))
                .setChecked(NekoConfig.progressiveFadeBlurOtherActivities).slug("progressiveFadeBlurOtherActivities"));
        if (NekoConfig.blurredFadeView) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                items.add(UItem.asCheck(progressiveFadeBlurRow, LocaleController.getString(R.string.ProgressiveFadeBlur)).setChecked(NekoConfig.progressiveFadeBlur).slug("progressiveFadeBlur"));
                if (NekoConfig.progressiveFadeBlur) {
                    items.add(SeekbarCellFactory.of(progressiveFadeBlurSamplesRow,
                            new SeekbarConfig(LocaleController.getString(R.string.ProgressiveFadeBlurSamples),
                                    "3", "25", 3, 25, 2,
                                    progress -> {
                                        int v = Math.max(3, Math.min(25, Math.round(progress)));
                                        if (v != NekoConfig.progressiveFadeBlurSamples) {
                                            NekoConfig.setProgressiveFadeBlurSamples(v);
                                        }
                                    }),
                            NekoConfig.progressiveFadeBlurSamples).slug("progressiveFadeBlurSamples"));
                    items.add(SeekbarCellFactory.of(progressiveFadeBlurRefreshRateRow,
                            new SeekbarConfig(LocaleController.getString(R.string.ProgressiveFadeBlurRefreshRate),
                                    "15", "120", 15, 120, 1,
                                    progress -> {
                                        int v = Math.max(15, Math.min(120, Math.round(progress)));
                                        if (v != NekoConfig.progressiveFadeBlurRefreshRate) {
                                            NekoConfig.setProgressiveFadeBlurRefreshRate(v);
                                        }
                                    }),
                            NekoConfig.progressiveFadeBlurRefreshRate).slug("progressiveFadeBlurRefreshRate"));
                }
            }
            items.add(SeekbarCellFactory.of(blurredFadeBlurAmountRow,
                    new SeekbarConfig(LocaleController.getString(R.string.BlurredFadeBlurAmount),
                            "0", "40", 0, 40,
                            progress -> {
                                int v = Math.max(0, Math.min(40, Math.round(progress)));
                                if (v != NekoConfig.blurredFadeBlurStrength || v != NekoConfig.progressiveFadeBlurMaxRadius) {
                                    NekoConfig.setBlurredFadeBlurStrength(v);
                                    NekoConfig.setProgressiveFadeBlurMaxRadius(v);
                                }
                            }),
                    NekoConfig.progressiveFadeBlur ? NekoConfig.progressiveFadeBlurMaxRadius : NekoConfig.blurredFadeBlurStrength).slug("blurredFadeBlurAmount"));
            items.add(SeekbarCellFactory.of(blurredFadePixelationRow,
                    new SeekbarConfig(LocaleController.getString(R.string.BlurredFadePixelation),
                            "1", "16", 1, 16,
                            progress -> {
                                int v = Math.max(1, Math.min(16, Math.round(progress)));
                                if (v != NekoConfig.blurredFadePixelation) {
                                    NekoConfig.setBlurredFadePixelation(v);
                                }
                            }),
                    NekoConfig.blurredFadePixelation).slug("blurredFadePixelation"));
        }
        if (NekoConfig.blurredFadeView) {
            items.add(UItem.asCheck(blurredFadeDimmingRow, LocaleController.getString(R.string.BlurredFadeDimming)).setChecked(NekoConfig.blurredFadeDimming).slug("blurredFadeDimming"));
            if (NekoConfig.blurredFadeDimming) {
                items.add(SeekbarCellFactory.of(blurredFadeDimStrengthRow,
                        new SeekbarConfig(LocaleController.getString(R.string.BlurredFadeDimStrength),
                                "0", "100", 0, 100,
                                progress -> {
                                    int v = Math.max(0, Math.min(100, Math.round(progress)));
                                    if (v != NekoConfig.blurredFadeDimStrength) {
                                        NekoConfig.setBlurredFadeDimStrength(v);
                                    }
                                }),
                        NekoConfig.blurredFadeDimStrength).slug("blurredFadeDimStrength"));
            }
        }
        items.add(TextSettingsCellFactory.of(avatarPlacementRow, LocaleController.getString(R.string.AvatarPlacement), placementName(effectivePlacement())));
        items.add(UItem.asShadow(null));
    }

    private int effectivePlacement() {
        if (NekoConfig.avatarPlacement == NekoConfig.AVATAR_PLACEMENT_CENTER && !NekoConfig.centerChatHeader) {
            return NekoConfig.AVATAR_PLACEMENT_LEFT;
        }
        return NekoConfig.avatarPlacement;
    }

    private String placementName(int placement) {
        if (placement == NekoConfig.AVATAR_PLACEMENT_CENTER) {
            return LocaleController.getString(R.string.AvatarPlacementCenter);
        } else if (placement == NekoConfig.AVATAR_PLACEMENT_RIGHT) {
            return LocaleController.getString(R.string.AvatarPlacementRight);
        }
        return LocaleController.getString(R.string.AvatarPlacementLeft);
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == removeHeaderPillRow) {
            NekoConfig.toggleMaterial3ChatHeaders();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.material3ChatHeaders);
            }
            updatePreview();
        } else if (id == centerHeaderRow) {
            NekoConfig.toggleCenterChatHeader();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.centerChatHeader);
            }
            if (!NekoConfig.centerChatHeader && NekoConfig.avatarPlacement == NekoConfig.AVATAR_PLACEMENT_CENTER) {
                NekoConfig.setAvatarPlacement(NekoConfig.AVATAR_PLACEMENT_LEFT);
            }
            notifyItemChanged(avatarPlacementRow);
            updatePreview();
        } else if (id == biggerAvatarRow) {
            NekoConfig.toggleBiggerAvatar();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.biggerAvatar);
            }
            updatePreview();
        } else if (id == blurredFadeViewRow) {
            NekoConfig.toggleBlurredFadeView();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurredFadeView);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        } else if (id == progressiveFadeBlurRow) {
            NekoConfig.toggleProgressiveFadeBlur();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.progressiveFadeBlur);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            notifyItemChanged(blurredFadeBlurAmountRow);
        } else if (id == progressiveFadeBlurOtherActivitiesRow) {
            NekoConfig.toggleProgressiveFadeBlurOtherActivities();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.progressiveFadeBlurOtherActivities);
            }
            showRestartBulletin();
        } else if (id == blurredFadeDimmingRow) {
            NekoConfig.toggleBlurredFadeDimming();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurredFadeDimming);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        } else if (id == avatarPlacementRow) {
            int current = effectivePlacement();
            ItemOptions options = ItemOptions.makeOptions(this, view).setMinWidth(190);
            options.addText(LocaleController.getString(R.string.AvatarPlacement), 14);
            options.addChecked(current == NekoConfig.AVATAR_PLACEMENT_LEFT, LocaleController.getString(R.string.AvatarPlacementLeft), () -> setPlacement(NekoConfig.AVATAR_PLACEMENT_LEFT));
            options.addCheckedIf(NekoConfig.centerChatHeader, current == NekoConfig.AVATAR_PLACEMENT_CENTER, LocaleController.getString(R.string.AvatarPlacementCenter), () -> setPlacement(NekoConfig.AVATAR_PLACEMENT_CENTER));
            options.addChecked(current == NekoConfig.AVATAR_PLACEMENT_RIGHT, LocaleController.getString(R.string.AvatarPlacementRight), () -> setPlacement(NekoConfig.AVATAR_PLACEMENT_RIGHT));
            options.show();
        }
    }

    private void setPlacement(int placement) {
        NekoConfig.setAvatarPlacement(placement);
        notifyItemChanged(avatarPlacementRow);
        updatePreview();
    }

    private FrameLayout getOrCreatePreviewContainer() {
        if (previewContainer != null) return previewContainer;
        if (getContext() == null) return new FrameLayout(getContext() == null ? null : getContext());

        int currentAccount = UserConfig.selectedAccount;
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).clientUserId);
        if (user == null) user = UserConfig.getInstance(currentAccount).getCurrentUser();
        String userName = user != null ? UserObject.getUserName(user) : "User";
        String onlineText = LocaleController.getString(R.string.Online);

        previewContainer = new FrameLayout(getContext()) {
            private Paint scrimPaint = new Paint();

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                captureWallpaperSource();
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
                if (wallpaper == null) wallpaper = Theme.getCachedWallpaper();
                if (wallpaper != null) {
                    wallpaper.setBounds(0, 0, getWidth(), getHeight());
                    wallpaper.draw(canvas);
                } else {
                    canvas.drawColor(Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider));
                }
                scrimPaint.setColor(0x33000000);
                canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
                if (!wallpaperCaptured) {
                    captureWallpaperSource();
                }
                super.dispatchDraw(canvas);
            }

            private boolean drawWallpaperInto(Canvas canvas) {
                Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
                if (wallpaper == null) wallpaper = Theme.getCachedWallpaper();
                if (wallpaper == null) return false;
                wallpaper.setAlpha(255);
                wallpaper.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                wallpaper.draw(canvas);
                return true;
            }

            private void captureWallpaperSource() {
                if (wallpaperSource == null || previewActionBar == null || getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0) return;
                boolean recorded = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wallpaperRenderSource != null) {
                    if (wallpaperRenderSource.inRecording()) return;
                    Canvas c = wallpaperRenderSource.beginRecording(getMeasuredWidth(), getMeasuredHeight());
                    recorded = drawWallpaperInto(c);
                    wallpaperRenderSource.endRecording();
                    wallpaperRenderSource.setBlur(AndroidUtilities.dpf2(8f));
                } else if (wallpaperBitmapSource != null) {
                    Canvas c = wallpaperBitmapSource.beginRecording(getMeasuredWidth(), getMeasuredHeight());
                    recorded = drawWallpaperInto(c);
                    wallpaperBitmapSource.endRecording();
                    wallpaperBitmapSource.setParentSize(getMeasuredWidth(), getMeasuredHeight(), 0);
                }
                if (!recorded) return;
                wallpaperCaptured = true;
                previewActionBar.invalidate();
            }
        };
        previewContainer.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.displaySize.y / 2 + AndroidUtilities.dp(80)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wallpaperRenderSource = new BlurredBackgroundSourceRenderNode(null);
            wallpaperSource = wallpaperRenderSource;
        } else {
            wallpaperBitmapSource = new BlurredBackgroundSourceBitmap();
            wallpaperSource = wallpaperBitmapSource;
        }
        BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(wallpaperSource);
        factory.setLiquidGlassEffectAllowed(!NonIslandHelper.chatElements() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        BlurredBackgroundProvider colorProvider = BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider);

        previewActionBar = new ActionBar(getContext(), resourcesProvider);
        previewActionBar.setOccupyStatusBar(false);
        previewActionBar.setTitle("");
        previewM3 = NekoConfig.material3ChatHeaders;
        previewCenter = NekoConfig.centerChatHeader;
        previewActionBar.inu_m3ChatHeader = previewM3;
        previewActionBar.setupGlass(factory, colorProvider, false);

        previewAvatar = new ChatAvatarContainer(getContext(), null, false, resourcesProvider) {
            @Override
            public int getVisualWidth() {
                return previewCenter ? super.getVisualWidth() : Integer.MAX_VALUE;
            }
        };
        previewAvatar.setOccupyStatusBar(false);
        previewAvatar.setUserAvatar(user, true);
        previewAvatar.setTitle(userName, false, false, false, false, null, false);
        previewAvatar.setSubtitle(onlineText);
        previewAvatar.setGlassMode();
        previewAvatar.setM3HeaderMode(previewM3);
        previewAvatar.setBiggerAvatar(NekoConfig.biggerAvatar);

        BackDrawable backDrawable = new BackDrawable(false);
        backDrawable.setColor(0xFFFFFFFF);
        previewActionBar.setBackButtonDrawable(backDrawable);
        backDrawable.setColor(0xFFFFFFFF);
        previewActionBar.addView(previewAvatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        ActionBarMenu menu = previewActionBar.createMenu();
        previewMoreItem = menu.addItem(999, R.drawable.ic_ab_other);
        menu.setGlassMode(true);
        menu.setTranslationX(-AndroidUtilities.dp(10));
        previewActionBar.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));

        previewContainer.addView(previewActionBar);
        previewContainer.post(() -> previewActionBar.checkAvatarContainerWidth(false));
        applyPreviewPlacement();
        return previewContainer;
    }

    private void applyPreviewPlacement() {
        if (previewActionBar == null || previewAvatar == null) return;
        int placement = effectivePlacement();
        boolean textOnlyPill = previewCenter && placement != NekoConfig.AVATAR_PLACEMENT_CENTER;
        boolean avatarRight = placement == NekoConfig.AVATAR_PLACEMENT_RIGHT;
        previewActionBar.inu_centerChatHeader = previewCenter && placement == NekoConfig.AVATAR_PLACEMENT_CENTER;
        previewActionBar.inu_textOnlyPill = textOnlyPill;
        previewAvatar.setAvatarPlacement(placement);
        previewAvatar.setTextOnlyPill(textOnlyPill);
        previewActionBar.setChatAvatarContainer(previewCenter ? previewAvatar : null);
        if (previewAvatar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewAvatar.getLayoutParams();
            lp.rightMargin = avatarRight ? AndroidUtilities.dp(6) : AndroidUtilities.dp(52);
            previewAvatar.setLayoutParams(lp);
        }
        if (avatarRight) {
            previewAvatar.setRightTextInset(AndroidUtilities.dp(140));
            previewAvatar.setRightAnchorView(previewMoreItem);
        } else if (textOnlyPill) {
            previewAvatar.setRightTextInset(AndroidUtilities.dp(92));
            previewAvatar.setRightAnchorView(null);
        } else {
            previewAvatar.setRightTextInset(0);
            previewAvatar.setRightAnchorView(null);
        }
        if (previewMoreItem != null && previewMoreItem.getIconView() != null) {
            previewMoreItem.getIconView().setVisibility(avatarRight ? View.INVISIBLE : View.VISIBLE);
        }
        if (previewMoreItem != null) {
            previewActionBar.inu_avatarRightBigger = avatarRight && NekoConfig.biggerAvatar;
        }
        previewAvatar.setTranslationX(0);
        if (!previewCenter) {
            previewAvatar.setAvatarOffset(0);
        }
        previewActionBar.requestLayout();
        previewAvatar.requestLayout();
        previewContainer.post(() -> {
            previewActionBar.checkAvatarContainerWidth(false);
            previewAvatar.requestLayout();
            previewContainer.invalidate();
            previewActionBar.invalidate();
            previewAvatar.invalidate();
        });
    }

    private void updatePreview() {
        if (previewActionBar == null || previewAvatar == null || previewContainer == null) return;
        previewM3 = NekoConfig.material3ChatHeaders;
        previewCenter = NekoConfig.centerChatHeader;
        previewActionBar.inu_m3ChatHeader = previewM3;
        previewAvatar.setM3HeaderMode(previewM3);
        previewAvatar.setBiggerAvatar(NekoConfig.biggerAvatar);
        applyPreviewPlacement();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ChatHeaderSettings);
    }

    @Override
    protected String getKey() {
        return "ch";
    }
}
