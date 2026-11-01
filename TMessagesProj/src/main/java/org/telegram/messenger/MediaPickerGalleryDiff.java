package org.telegram.messenger;

import android.os.CancellationSignal;
import android.text.TextUtils;

import androidx.recyclerview.widget.DiffUtil;

/** Structural changes between immutable album lists. Run on a worker, never on UI. */
public final class MediaPickerGalleryDiff {
    private MediaPickerGalleryDiff() {}

    public static boolean sameAlbum(MediaController.AlbumEntry previous, MediaController.AlbumEntry next) {
        return previous != null && next != null && previous.bucketId == next.bucketId
                && previous.videoOnly == next.videoOnly && TextUtils.equals(previous.bucketName, next.bucketName);
    }

    public static DiffUtil.DiffResult calculate(MediaController.AlbumEntry previous, MediaController.AlbumEntry next,
                                               CancellationSignal cancellation) {
        cancellation.throwIfCanceled();
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() { return previous.photos.size(); }

            @Override
            public int getNewListSize() { return next.photos.size(); }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                cancellation.throwIfCanceled();
                // Only read ids: edit state and Live Photo metadata can change on UI.
                return previous.photos.get(oldPosition).imageId == next.photos.get(newPosition).imageId;
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                // The adapter checks visible content on UI through a lightweight payload.
                return true;
            }
        }, false);
    }
}
