"""Run the V2 loader and lifecycle with fake MediaStore cursors (JDK 17+, no SDK)."""
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from test_glass_blur import method

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java'


def nested_source(path, declaration):
    source = path.read_text()
    source = re.sub(r'^(package|import) .*;\n', '', source, flags=re.M)
    return source.replace(declaration, 'static ' + declaration, 1)


COMMON = r'''
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static class Manifest {
        static class permission {
            static final String READ_EXTERNAL_STORAGE="storage", READ_MEDIA_IMAGES="images",
                READ_MEDIA_VIDEO="video", READ_MEDIA_VISUAL_USER_SELECTED="selected";
        }
    }
    static class PackageManager { static final int PERMISSION_GRANTED=0; }
    static class Build { static class VERSION { static int SDK_INT=34; } }
    static class Uri { final String name; Uri(String name) { this.name=name; } }
    static class MediaStore {
        static class MediaColumns {
            static final String _ID="_id", DATA="_data", DATE_MODIFIED="modified", WIDTH="width", HEIGHT="height", SIZE="size";
        }
        static class Images { static class Media extends MediaColumns {
            static final String BUCKET_ID="bucket", BUCKET_DISPLAY_NAME="bucket_name", DATE_TAKEN="taken", ORIENTATION="rotation";
            static final Uri EXTERNAL_CONTENT_URI=new Uri("images");
        } }
        static class Video { static class Media extends Images.Media {
            static final String DURATION="duration";
            static final Uri EXTERNAL_CONTENT_URI=new Uri("video");
        } }
    }
    static class Environment {
        static final String DIRECTORY_DCIM="DCIM";
        static java.io.File getExternalStoragePublicDirectory(String dir) { return new java.io.File("/storage/"+dir); }
    }
    static class CancellationSignal {
        volatile boolean cancelled;
        void cancel() { cancelled=true; }
        boolean isCanceled() { return cancelled; }
        void throwIfCanceled() { if(cancelled) throw new OperationCanceledException(); }
    }
    static class OperationCanceledException extends RuntimeException {}
    static class TextUtils {
        static boolean isEmpty(String s) { return s==null || s.isEmpty(); }
        static boolean equals(String a,String b) { return Objects.equals(a,b); }
    }
    static class SparseArray<T> {
        final TreeMap<Integer,T> values=new TreeMap<>();
        void put(int key, T value) { values.put(key,value); }
        void append(int key,T value) {
            check(values.isEmpty() || key>=values.lastKey(), "index must be appended in ascending order");
            values.put(key,value);
        }
        T get(int key) { return values.get(key); }
        int size() { return values.size(); }
    }
    static class R { static class string { static final int AllMedia=1,AllPhotos=2,AllVideos=3; } }
    static class LocaleController { static String getString(int id) { return "album"+id; } }
    static class FileLog { static void e(Exception e) {} }
    static class MediaController {
        static class PhotoEntry {
            int imageId,bucketId,orientation,duration,width,height; long dateTaken,size;
            String path,caption; boolean isVideo;
            PhotoEntry(int bucket,int id,long date,String path,int orientation,int duration,boolean video,int width,int height,long size) {
                bucketId=bucket; imageId=id; dateTaken=date; this.path=path; this.orientation=orientation;
                this.duration=duration; isVideo=video; this.width=width; this.height=height; this.size=size;
            }
            Boolean discardLivePhoto;
            int invert,gradientTopColor,gradientBottomColor; boolean isMuted,isAttachSpoilerRevealed;
            Object emojiMarkup,coverPhoto,coverPhotoParentObject; long coverSavedPosition,effectId;
            void copyFrom(PhotoEntry other) { caption=other.caption; }
            boolean parsedXmp,isLivePhoto;
            long livePhotoVideoOffset,livePhotoTimestampUs;
            static int metadataReads;
            boolean isLivePhoto() {
                if(isVideo || parsedXmp) return isLivePhoto;
                metadataReads++; parsedXmp=true;
                isLivePhoto=path.contains("live");
                if(isLivePhoto) { isVideo=true; livePhotoVideoOffset=500; livePhotoTimestampUs=123; }
                return isLivePhoto;
            }
            HAS_METADATA
            APPLY_METADATA
        }
        static class AlbumEntry {
            int bucketId; String bucketName; PhotoEntry coverPhoto; boolean videoOnly;
            ArrayList<PhotoEntry> photos=new ArrayList<>(); SparseArray<PhotoEntry> photosByIds=new SparseArray<>();
            AlbumEntry(int bucket,String name,PhotoEntry cover) { bucketId=bucket; bucketName=name; coverPhoto=cover; }
        }
    }
    static class Cursor implements AutoCloseable {
        final Object[][] rows; int position=-1, advances; boolean closed,fail;
        Cursor(Object[][] rows) { this.rows=rows; }
        boolean moveToNext() { advances++; if(fail) throw new IllegalStateException("cursor failed"); return ++position<rows.length; }
        String getString(int c) { return (String)rows[position][c]; }
        int getInt(int c) { return ((Number)rows[position][c]).intValue(); }
        long getLong(int c) { return ((Number)rows[position][c]).longValue(); }
        public void close() { closed=true; }
    }
    static class ContentObserver {
        ContentObserver(Object handler) {}
        public void onChange(boolean selfChange) {}
        public void onChange(boolean selfChange, Uri uri) {}
    }
    static class Resolver {
        Cursor images=new Cursor(new Object[0][]),videos=new Cursor(new Object[0][]);
        int queries, registrations; boolean failVideos;
        Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort,CancellationSignal signal) {
            signal.throwIfCanceled(); queries++;
            check(sort.endsWith(" DESC, _id DESC"),"deterministic date/id order");
            if(uri.name.equals("video") && failVideos) throw new IllegalStateException("query failed");
            return uri.name.equals("images") ? images : videos;
        }
        void registerContentObserver(Uri uri,boolean descendants,ContentObserver observer) { registrations++; }
        void unregisterContentObserver(ContentObserver observer) { registrations=0; }
    }
    static class Context {
        final Resolver resolver=new Resolver();
        final Set<String> grants=new HashSet<>(List.of("images","video","storage"));
        int checkSelfPermission(String permission) { return grants.contains(permission)?0:-1; }
        Resolver getContentResolver() { return resolver; }
        Context getApplicationContext() { return this; }
    }
'''


_metadata_source = (JAVA / 'org/telegram/messenger/MediaController.java').read_text()
COMMON = COMMON.replace('HAS_METADATA', method(_metadata_source, 'public boolean hasLivePhotoMetadata()'))
COMMON = COMMON.replace('APPLY_METADATA', method(_metadata_source, 'public void applyLivePhotoMetadata(PhotoEntry probe)'))


class MediaPickerV2Test(unittest.TestCase):
    def run_java(self, body):
        self.assertIsNotNone(shutil.which('javac'), 'Put JDK 17+ on PATH')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'MediaPickerHarness.java'
            path.write_text('import java.util.*;\npublic class MediaPickerHarness {\n' + COMMON + body + '\n}')
            compiled = subprocess.run(['javac', str(path)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', directory, 'MediaPickerHarness'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('OK', result.stdout)

    def test_streaming_order_indexes_snapshots_cancellation_and_access(self):
        loader = nested_source(JAVA / 'org/telegram/messenger/MediaPickerGalleryLoader.java',
                               'public final class MediaPickerGalleryLoader')
        self.run_java(loader + r'''
    static Object[][] rows(int count,boolean video) {
        Object[][] result=new Object[count][];
        for(int i=0;i<count;i++) {
            int id=2*(count-i)+(video?1:0), bucket=i%3+1;
            result[i]=new Object[]{id,bucket,"folder"+bucket,
                (bucket==1?"/storage/DCIM/Camera/":"/storage/other/")+id,100000L-i*2-(video?1:0),
                video?5000:90,1920,1080,123456L};
        }
        return result;
    }
    static Context library(int count) {
        Context context=new Context();
        context.resolver.images=new Cursor(rows(count,false));
        context.resolver.videos=new Cursor(rows(count,true));
        return context;
    }
    static void verifyAlbum(MediaController.AlbumEntry album) {
        check(album.photos.size()==album.photosByIds.size(),"complete id index");
        long previous=Long.MAX_VALUE;
        for(var photo:album.photos) {
            check(previous>=photo.dateTaken,"chronological album order"); previous=photo.dateTaken;
            check(album.photosByIds.get(photo.imageId)==photo,"id lookup preserves entry identity");
            check(photo.width==1920 && photo.height==1080 && photo.size==123456L,"metadata");
            check(photo.isVideo && !photo.isLivePhoto ? photo.duration==5 : photo.orientation==90,"rotation/duration");
        }
    }
    public static void main(String[] args) {
        Context context=library(120);
        ArrayList<MediaPickerGalleryLoader.Gallery> results=new ArrayList<>();
        MediaPickerGalleryLoader.load(context,new CancellationSignal(),(gallery,complete)->{
            check(gallery!=null,"successful query"); results.add(gallery);
            if(!complete) {
                int expected=results.size()==1?24:96;
                check(gallery.allMedia.photos.size()==expected,"24-item first screen, then 96-item preview");
                check(context.resolver.images.advances+context.resolver.videos.advances<=expected+2,"preview before scanning library");
                var edited=gallery.allMedia.photos.get(0);
                edited.caption="edited";
                edited.isVideo=true; edited.isLivePhoto=true; // detection finishes after first publish

            }
        });
        check(results.size()==3,"first screen, larger preview, full library");
        var preview=results.get(0); var full=results.get(2);
        check(preview.allMedia.photos.size()==24 && preview.allMedia.photosByIds.size()==24,"published first screen stays immutable");
        check(results.get(1).allMedia.photos.size()==96,"published second preview stays immutable");
        check(MediaController.PhotoEntry.metadataReads==0,"no EXIF reads on gallery's first-screen path");
        check(full.allPhotos.photosByIds.get(preview.allMedia.photos.get(0).imageId)!=null,"Live Photos stay in photo albums");
        check(full.allMedia.photos.size()==240 && full.allPhotos.photos.size()==120,"all media and photo counts");
        check(preview.allMedia.photos.get(0)==full.allMedia.photos.get(0),"entries shared across preview/final");
        check(full.allMedia.photos.get(0).caption.equals("edited"),"selection edits survive completion");
        for(var album:full.mediaAlbums) verifyAlbum(album);
        for(var album:full.photoAlbums) verifyAlbum(album);
        check(full.mediaAlbums.get(1)==full.allPhotos,"photo filter is available alongside videos");
        check(full.mediaAlbums.get(3).bucketId==1,"camera folder first after synthetic albums");
        check(context.resolver.images.closed && context.resolver.videos.closed,"close successful cursors");

        Context large=library(20000);
        MediaPickerGalleryLoader.load(large,new CancellationSignal(),(gallery,complete)->{
            if(complete) { check(gallery.allMedia.photos.size()==40000,"large library"); verifyAlbum(gallery.allMedia); }
        });
        Context cancelled=library(120); CancellationSignal signal=new CancellationSignal(); int[] callbacks={0};
        MediaPickerGalleryLoader.load(cancelled,signal,(gallery,complete)->{ callbacks[0]++; signal.cancel(); });
        check(callbacks[0]==1 && cancelled.resolver.images.closed && cancelled.resolver.videos.closed,"cancel after preview closes both cursors");

        Context failed=library(120); failed.resolver.failVideos=true;
        MediaPickerGalleryLoader.load(failed,new CancellationSignal(),(gallery,complete)->check(gallery==null && complete,"report query failure"));
        check(failed.resolver.images.closed,"close first cursor if second query fails");
        Context failedAdvance=library(120); failedAdvance.resolver.images.fail=true;
        MediaPickerGalleryLoader.load(failedAdvance,new CancellationSignal(),(gallery,complete)->check(gallery==null && complete,"report cursor failure"));
        check(failedAdvance.resolver.images.closed,"close cursor if initial advance fails");

        Context denied=library(120); denied.grants.clear();
        MediaPickerGalleryLoader.load(denied,new CancellationSignal(),(gallery,complete)->check(gallery.allMedia==null && complete,"empty inaccessible library"));
        check(denied.resolver.queries==0,"no queries without permission");
        Context photoOnly=library(120); photoOnly.grants.remove("video");
        MediaPickerGalleryLoader.load(photoOnly,new CancellationSignal(),(gallery,complete)->{
            if(complete) check(gallery.allMedia.photos.size()==120,"only authorized photos");
        });
        check(photoOnly.resolver.queries==1,"skip unauthorized videos");
        denied.grants.add("selected");
        check(MediaPickerGalleryLoader.canRead(denied,false) && MediaPickerGalleryLoader.canRead(denied,true),"Android 14 selected media access");
        Build.VERSION.SDK_INT=33;
        check(!MediaPickerGalleryLoader.canRead(denied,false),"selected permission requires Android 14");
        Build.VERSION.SDK_INT=28;
        check(MediaPickerGalleryLoader.canRead(photoOnly,false),"legacy storage access");
        Context emptyPath=library(1); emptyPath.resolver.images.rows[0][3]="";
        MediaPickerGalleryLoader.load(emptyPath,new CancellationSignal(),(gallery,complete)->check(gallery.allPhotos==null,"skip empty paths"));
        System.out.println("OK");
    }
''')

    def test_query_lifecycle_and_deferred_viewer_updates(self):
        layout = nested_source(JAVA / 'org/telegram/ui/Components/ChatAttachAlertPhotoLayoutV2.java',
                               'public class ChatAttachAlertPhotoLayoutV2')
        self.run_java(r'''
    static class MediaPickerGalleryLoader {
        interface Callback { void onLoaded(Gallery gallery,boolean complete); }
        static class Gallery {
            MediaController.AlbumEntry allMedia=new MediaController.AlbumEntry(0,"all",null),allPhotos=allMedia;
            ArrayList<MediaController.AlbumEntry> mediaAlbums=new ArrayList<>(List.of(allMedia)),photoAlbums=mediaAlbums;
        }
        record Job(CancellationSignal signal,Callback callback) {}
        static final List<Job> jobs=Collections.synchronizedList(new ArrayList<>());
        static boolean canRead(Context context,boolean video) { return context.grants.contains(video?"video":"images") || context.grants.contains("selected"); }
        static int getAccessSignature(Context context) {
            return (context.grants.contains("images")?1:0) | (context.grants.contains("video")?2:0)
                | (context.grants.contains("selected")?4:0);
        }
        static void load(Context context,CancellationSignal signal,Callback callback) { jobs.add(new Job(signal,callback)); }
    }
    static class ApplicationLoader {
        static final Object applicationHandler=new Object();
    }
    static class AndroidUtilities {
        static final List<Runnable> immediate=new ArrayList<>(),delayed=new ArrayList<>();
        static synchronized void runOnUIThread(Runnable r) { immediate.add(r); }
        static synchronized void runOnUIThread(Runnable r,long delay) { delayed.add(r); }
        static synchronized void cancelRunOnUIThread(Runnable r) { immediate.removeIf(x->x==r); delayed.removeIf(x->x==r); }
        static void drain() {
            for(;;) { Runnable r; synchronized(AndroidUtilities.class) {
                if(immediate.isEmpty()) return; r=immediate.remove(0);
            } r.run(); }
        }
        static void advanceTime() {
            ArrayList<Runnable> due;
            synchronized(AndroidUtilities.class) { due=new ArrayList<>(delayed); delayed.clear(); }
            for(Runnable r:due) r.run(); drain();
        }
    }
    static class Theme { static class ResourcesProvider {} }
    static class NotificationCenter {
        static final int albumsDidLoad=1;
        static final NotificationCenter instance=new NotificationCenter();
        static NotificationCenter getGlobalInstance() { return instance; }
        void removeObserver(Object o,int id) {}
    }
    static class PhotoViewer {
        static final PhotoViewer instance=new PhotoViewer(); boolean visible;
        static PhotoViewer getInstance() { return instance; }
        boolean isVisible() { return visible; }
    }
    static class ChatAttachAlert {
        boolean showing=true;
        boolean isShowing() { return showing; }
        boolean isDismissed() { return !showing; }
    }
    static class Grid {
        void setItemViewCacheSize(int count) {}
        Grid getRecycledViewPool() { return this; }
        void setMaxRecycledViews(int type,int count) {}
    }
    static class ChatAttachAlertPhotoLayout {
        final ChatAttachAlert parentAlert; final Context context; final Grid gridView=new Grid();
        int changes,loading,destroyCalls; boolean dismissConsumed;
        ChatAttachAlertPhotoLayout(ChatAttachAlert alert,Context context,boolean dark,boolean camera,Theme.ResourcesProvider provider,boolean incremental) {
            parentAlert=alert; this.context=context; check(incremental,"V2 grid path");
        }
        Context getContext() { return context; }
        boolean shouldLoadAllMedia() { return true; }
        protected boolean isNoGalleryPermissions() { return false; }
        protected MediaController.AlbumEntry getGalleryAlbumEntry(boolean all) { return null; }
        protected ArrayList<MediaController.AlbumEntry> getGalleryAlbums(boolean all) { return null; }
        protected void onGalleryLoaded() { changes++; }
        protected void onGalleryLoading() { loading++; }
        public void loadGalleryPhotos() {}
        public void onInit(boolean video,boolean photo,boolean document) {}
        public void onResume() {}
        public void onPause() {}
        public boolean onDismiss() { return dismissConsumed; }
        public void onDismissWithButtonClick(int item) {}
        public void onDestroy() { destroyCalls++; }
    }
''' + layout + r'''
    static MediaPickerGalleryLoader.Job job(int index) throws Exception {
        long timeout=System.nanoTime()+2_000_000_000L;
        while(MediaPickerGalleryLoader.jobs.size()<=index && System.nanoTime()<timeout) Thread.sleep(1);
        check(MediaPickerGalleryLoader.jobs.size()>index,"worker started");
        return MediaPickerGalleryLoader.jobs.get(index);
    }
    public static void main(String[] args) throws Exception {
        Context context=new Context(); ChatAttachAlert parent=new ChatAttachAlert();
        var picker=new ChatAttachAlertPhotoLayoutV2(parent,context,false,true,null);
        check(context.resolver.registrations==2,"observe both collections");
        picker.onInit(true,true,true); var first=job(0);
        picker.loadGalleryPhotos(); check(MediaPickerGalleryLoader.jobs.size()==1,"coalesce duplicate loads");
        var preview=new MediaPickerGalleryLoader.Gallery();
        first.callback().onLoaded(preview,false); AndroidUtilities.drain();
        check(picker.getGalleryAlbumEntry(true)==preview.allMedia,"apply preview");
        PhotoViewer.instance.visible=true;
        var full=new MediaPickerGalleryLoader.Gallery();
        first.callback().onLoaded(full,true); AndroidUtilities.drain();
        check(picker.getGalleryAlbumEntry(true)==preview.allMedia,"viewer keeps its album");
        AndroidUtilities.advanceTime();
        check(picker.getGalleryAlbumEntry(true)==preview.allMedia,"wait until viewer closes");
        PhotoViewer.instance.visible=false; AndroidUtilities.advanceTime();
        check(picker.getGalleryAlbumEntry(true)==full.allMedia,"apply latest snapshot after viewer closes");
        picker.onPause(); picker.onResume();
        picker.loadGalleryPhotos(); check(MediaPickerGalleryLoader.jobs.size()==1,"full-access resume reuses completed gallery without querying");

        picker.galleryObserver.onChange(false); check(first.signal().isCanceled(),"invalidate old query");
        AndroidUtilities.advanceTime(); var second=job(1);
        first.callback().onLoaded(preview,true); AndroidUtilities.drain();
        check(picker.getGalleryAlbumEntry(true)==full.allMedia,"ignore stale callback");
        PhotoViewer.instance.visible=true;
        second.callback().onLoaded(preview,true); AndroidUtilities.drain();
        picker.onDismiss(); check(second.signal().isCanceled(),"dismiss cancels query");
        PhotoViewer.instance.visible=false; AndroidUtilities.advanceTime();
        check(picker.getGalleryAlbumEntry(true)==full.allMedia,"dismiss cancels pending viewer update");
        picker.loadGalleryPhotos(); var third=job(2);
        check(third!=second,"discarded pending result is retryable");

        context.grants.clear(); picker.loadGalleryPhotos(); var revoked=job(3);
        check(third.signal().isCanceled() && picker.getGalleryAlbumEntry(true)==null,"drop inaccessible cached media");
        check(picker.isNoGalleryPermissions(),"permission state refreshes");
        context.grants.add("selected"); picker.loadGalleryPhotos(); var partial=job(4);
        partial.callback().onLoaded(full,true); AndroidUtilities.drain();
        picker.onPause(); picker.onResume(); var refreshedPartial=job(5);
        check(refreshedPartial!=partial,"refresh selected-media access after backgrounding");
        context.grants.add("images"); context.grants.add("video");
        picker.loadGalleryPhotos(); var fullAccess=job(6);
        check(refreshedPartial.signal().isCanceled(),"distinguish full access from selected access even when both allow images/videos");
        int changes=picker.changes;
        picker.onDestroy(); picker.onDestroy();
        fullAccess.callback().onLoaded(full,true); AndroidUtilities.drain();
        check(fullAccess.signal().isCanceled() && picker.changes==changes,"ignore callbacks after destroy");
        check(context.resolver.registrations==0 && picker.destroyCalls==1,"release observers exactly once");
        System.out.println("OK");
    }
''')

    def test_snapshot_keeps_selected_album_and_edit_state(self):
        source = (JAVA / 'org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java').read_text()
        apply_snapshot = method(source, 'protected void onGalleryLoaded()')
        loading = method(source, 'protected void onGalleryLoading()')
        prefix = method(source, 'private boolean isGalleryPrefix(')
        append = method(source, 'private void notifyGalleryAppended(')
        self.run_java(r'''
    static class ChatGalleryResources { static final int ChatGallery=1; }
    static class Progress {
        int starts,finishes;
        void showProgress() { starts++; }
        void showTextView() { finishes++; }
    }
    static class Text { void setText(String text) {} }
    static class Parent { boolean isStickerMode; }
    static class SnapshotLayout {
        boolean noGalleryPermissions,denied,mediaEnabled=true;
        boolean incrementalGallery=true,loading;
        MediaController.AlbumEntry selectedAlbumEntry,galleryAlbumEntry,nextGallery;
        final ArrayList<MediaController.AlbumEntry> albums=new ArrayList<>();
        final Map<Object,Object> selectedPhotos=new HashMap<>();
        final ArrayList<Object> selectedPhotosOrder=new ArrayList<>();
        final Parent parentAlert=new Parent();
        final Progress progressView=new Progress(); final Text dropDown=new Text();
        int notifications,menus;
        final ArrayList<String> inserts=new ArrayList<>();
        Object expected;
        class Adapter {
            boolean hasCameraSpaceRow;
            int getItemCount() {
                int count=selectedAlbumEntry==null?0:selectedAlbumEntry.photos.size();
                return count+(this==adapter?1:0);
            }
            void notifyItemRangeInserted(int position,int count) { inserts.add((this==adapter?"grid":"strip")+":"+position+":"+count); }
            void notifyItemChanged(int position) {}
            void updateEmptyView() {}
            APPEND
            void notifyDataSetChanged() {
                notifications++;
                check(selectedPhotos.get(5)==expected,"restore selection before rebinding cells");
            }
        }
        Adapter adapter=new Adapter(),cameraAttachAdapter=new Adapter();
        boolean isNoGalleryPermissions() { return denied; }
        boolean shouldLoadAllMedia() { return true; }
        MediaController.AlbumEntry getGalleryAlbumEntry(boolean all) { return nextGallery; }
        ArrayList<MediaController.AlbumEntry> getGalleryAlbums(boolean all) { return albums; }
        void updateAlbumsDropDown() { menus++; }
        APPLY_SNAPSHOT
        LOADING
        PREFIX
    }
    public static void main(String[] args) {
        var layout=new SnapshotLayout();
        var oldGallery=new MediaController.AlbumEntry(0,"all",null);
        var oldFolder=new MediaController.AlbumEntry(7,"folder",null);
        var newFolder=new MediaController.AlbumEntry(7,"folder",null);
        var newGallery=new MediaController.AlbumEntry(0,"all",null);
        layout.galleryAlbumEntry=oldGallery; layout.selectedAlbumEntry=oldFolder;
        layout.nextGallery=newGallery; layout.albums.add(newGallery); layout.albums.add(newFolder);
        var edited=new MediaController.PhotoEntry(7,5,0,"photo",90,0,false,1,1,1);
        edited.caption="caption"; edited.discardLivePhoto=true; edited.isMuted=true;
        edited.effectId=99; edited.coverSavedPosition=123;
        var fresh=new MediaController.PhotoEntry(7,5,0,"photo",0,0,false,1,1,1);
        newGallery.photosByIds.put(5,fresh);
        layout.selectedPhotos.put(5,edited); layout.selectedPhotosOrder.add(5);
        layout.selectedPhotos.put(-1,"camera"); layout.selectedPhotosOrder.add(-1);
        layout.expected=fresh; layout.onGalleryLoaded();
        check(layout.selectedAlbumEntry==newFolder,"keep folder across preview/full replacement");
        check(fresh.caption.equals("caption") && fresh.discardLivePhoto && fresh.isMuted,"retain edit state");
        check(fresh.effectId==99 && fresh.coverSavedPosition==123 && fresh.orientation==90,"retain media options");
        check(layout.selectedPhotos.get(-1).equals("camera"),"keep camera selection absent from MediaStore");
        layout.albums.remove(newFolder); layout.onGalleryLoaded();
        check(layout.selectedAlbumEntry==newGallery,"deleted album falls back to gallery");
        layout.selectedAlbumEntry=oldFolder; layout.parentAlert.isStickerMode=true;
        layout.albums.add(newFolder); layout.onGalleryLoaded();
        check(layout.selectedAlbumEntry==newGallery,"sticker picker resets to gallery");
        check(layout.notifications==6 && layout.menus==3,"refresh both adapters and albums");
        var appended=new SnapshotLayout();
        var firstScreen=new MediaController.AlbumEntry(0,"all",null);
        var nextScreen=new MediaController.AlbumEntry(0,"all",null);
        for(int i=0;i<96;i++) {
            var photo=new MediaController.PhotoEntry(1,i,100-i,"photo"+i,0,0,false,1,1,1);
            nextScreen.photos.add(photo);
            if(i<24) firstScreen.photos.add(photo);
        }
        appended.galleryAlbumEntry=appended.selectedAlbumEntry=firstScreen;
        appended.nextGallery=nextScreen; appended.onGalleryLoaded();
        check(appended.notifications==0,"dosing gallery does not rebind visible thumbnails");
        check(appended.inserts.equals(List.of("grid:24:72","strip:24:72")),"append before footer and at strip end");
        check(!appended.isGalleryPrefix(firstScreen,newGallery),"replacement is not an append");
        nextScreen.photos.set(0,fresh);
        check(!appended.isGalleryPrefix(firstScreen,nextScreen),"reordered prefix forces refresh");
        var allPhotos=new MediaController.AlbumEntry(0,"photos",null);
        layout.parentAlert.isStickerMode=false;
        layout.selectedAlbumEntry=new MediaController.AlbumEntry(0,"photos",null);
        layout.albums.add(allPhotos); layout.onGalleryLoaded();
        check(layout.selectedAlbumEntry==allPhotos,"photo filter stays selected across refreshes");
        layout.selectedAlbumEntry=null; layout.onGalleryLoading();
        check(layout.loading && layout.progressView.starts==1,"show loading for empty gallery");
        layout.mediaEnabled=false; layout.onGalleryLoading();
        check(layout.progressView.starts==1,"keep restricted-media message visible");
        System.out.println("OK");
    }
'''.replace('APPLY_SNAPSHOT', apply_snapshot.replace('R.string.ChatGallery', 'ChatGalleryResources.ChatGallery'))
            .replace('LOADING', loading).replace('PREFIX', prefix).replace('APPEND', append))

    def test_live_photo_metadata_never_reads_disk_during_drawing(self):
        cell = (JAVA / 'org/telegram/ui/Cells/PhotoAttachPhotoCell.java').read_text()
        helpers = '\n'.join(method(cell, signature) for signature in (
            'public void setAsyncLivePhotos(boolean enabled)',
            'private boolean isEntryLivePhoto()',
            'private void cancelLivePhotoTask()',
            'private void loadLivePhotoMetadata()',
            'public void onFastScrollFinished()',
            'public void onVisibilityAggregated(boolean isVisible)'))
        helpers = helpers.replace('android.os.Process.THREAD_PRIORITY_BACKGROUND', '10')
        self.run_java(r'''
    static class AndroidUtilities {
        static final ArrayList<Runnable> ui=new ArrayList<>();
        static void runOnUIThread(Runnable runnable) { ui.add(runnable); }
        static void drain() { var due=new ArrayList<>(ui); ui.clear(); due.forEach(Runnable::run); }
    }
    static class DispatchQueue {
        final ArrayList<Runnable> work=new ArrayList<>();
        DispatchQueue(String name,boolean start,int priority) {}
        void postRunnable(Runnable runnable) { work.add(runnable); }
        void cancelRunnable(Runnable runnable) { work.remove(runnable); }
        void runOne() { work.remove(0).run(); }
    }
    static class Image { int invalidations; void invalidate() { invalidations++; } }
    static class VisibleView {
        boolean shown=true;
        public void onVisibilityAggregated(boolean visible) { shown=visible; }
    }
    static class Cell extends VisibleView {
        boolean asyncLivePhotos,allowLivePhotos=true,attached=true,fast;
        static DispatchQueue livePhotoQueue;
        Runnable livePhotoTask;
        int livePhotoGeneration;
        MediaController.PhotoEntry photoEntry;
        final Image imageView=new Image();
        boolean isAttachedToWindow() { return attached; }
        boolean isShown() { return shown && attached; }
        boolean isParentDoFastScroll() { return fast; }
        void invalidate() {}
        void bind(MediaController.PhotoEntry entry) { cancelLivePhotoTask(); photoEntry=entry; loadLivePhotoMetadata(); }
        HELPERS
    }
    static MediaController.PhotoEntry image(String path) {
        return new MediaController.PhotoEntry(1,1,123,path,90,0,false,100,100,1000);
    }
    public static void main(String[] args) {
        Cell cell=new Cell(); cell.setAsyncLivePhotos(true);
        var live=image("live.jpg"); cell.bind(live);
        check(!cell.isEntryLivePhoto() && MediaController.PhotoEntry.metadataReads==0,"bind/draw/accessibility do not read EXIF on UI");
        Cell.livePhotoQueue.runOne();
        check(!live.parsedXmp && !live.isVideo,"worker probes a different object");
        live.caption="edited while loading"; live.discardLivePhoto=true;
        AndroidUtilities.drain();
        check(live.isLivePhoto && live.isVideo && live.parsedXmp && cell.isEntryLivePhoto(),"publish live metadata on UI");
        check(live.livePhotoVideoOffset==500 && live.livePhotoTimestampUs==123,"preserve motion-photo offsets");
        check(live.caption.equals("edited while loading") && live.discardLivePhoto,"metadata must not overwrite edits");
        check(cell.imageView.invalidations==1,"redraw when metadata is ready");
        cell.bind(live); check(Cell.livePhotoQueue.work.isEmpty(),"cached metadata is not reread on reuse");

        var stale=image("live-old.jpg"); cell.bind(stale); Cell.livePhotoQueue.runOne();
        var replacement=image("plain.jpg"); cell.bind(replacement); AndroidUtilities.drain();
        check(!stale.parsedXmp && !replacement.parsedXmp,"ignore a stale result after recycling");
        cell.attached=false; cell.cancelLivePhotoTask();
        check(Cell.livePhotoQueue.work.isEmpty(),"detach removes pending disk work");
        cell.attached=true; cell.fast=true; cell.bind(replacement);
        check(Cell.livePhotoQueue.work.isEmpty(),"skip EXIF during fast scroll");
        cell.fast=false; cell.onFastScrollFinished(); Cell.livePhotoQueue.runOne(); AndroidUtilities.drain();
        check(replacement.parsedXmp && !replacement.isLivePhoto,"remember negative metadata results");
        int reads=MediaController.PhotoEntry.metadataReads;
        cell.bind(replacement); check(MediaController.PhotoEntry.metadataReads==reads && Cell.livePhotoQueue.work.isEmpty(),"negative cache avoids repeated file reads");
        cell.onVisibilityAggregated(false);
        cell.bind(image("live-hidden.jpg"));
        check(Cell.livePhotoQueue.work.isEmpty(),"hidden cells do not read files");
        cell.onVisibilityAggregated(true);
        check(Cell.livePhotoQueue.work.size()==1,"resume metadata only when cell becomes visible");
        cell.onVisibilityAggregated(false);
        check(Cell.livePhotoQueue.work.isEmpty(),"hide cancels queued metadata");
        cell.photoEntry=null; cell.onVisibilityAggregated(true);
        var selected=image("live-selected.jpg"); cell.bind(selected); Cell.livePhotoQueue.runOne();
        selected.parsedXmp=true; selected.livePhotoVideoOffset=999;
        AndroidUtilities.drain();
        check(selected.livePhotoVideoOffset==999,"do not overwrite metadata loaded by PhotoViewer");
        Cell legacy=new Cell(); legacy.photoEntry=image("live-legacy.jpg");
        check(legacy.isEntryLivePhoto(),"V1 retains its synchronous behavior");
        System.out.println("OK");
    }
'''.replace('HELPERS', helpers))

    def test_v2_camera_is_external_and_never_creates_a_preview(self):
        source = (JAVA / 'org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java').read_text()
        self.assertIn('this.needCamera = needCamera && !incrementalGallery;', source)
        self.assertIn('new PhotoAttachAdapter(context, this.needCamera &&', source)
        self.assertIn('cameraViewItemDecoration = this.needCamera ? new CameraViewItemDecoration(gridView) : null;', source)
        show = method(source, 'public void showCamera()')
        self.assertLess(show.index('!needCamera'), show.index('new CameraViewInternal'))
        check_camera = method(source, 'public void checkCamera(boolean request)')
        self.assertLess(check_camera.index('!needCamera'), check_camera.index('CameraController.getInstance()'))
        permission = method(source, 'private void openCameraWithPermissionCheck()')
        self.assertLess(permission.index('if (incrementalGallery)'), permission.index('ContextCompat.checkSelfPermission'))
        open_camera = method(source, 'private void openCameraByClick()')
        self.run_java(r'''
    static class SharedConfig { static boolean inappCamera=true; }
    static class NekoConfig { static boolean hideCameraInMediaPicker; }
    static class Delegate {
        int launches;
        void didPressedButton(int button,boolean arg,boolean notify,int date,int repeat,long effect,boolean above,boolean document,long stars) {
            check(button==0,"existing system-camera delegate"); launches++;
        }
    }
    static class Parent {
        final Delegate delegate=new Delegate();
        boolean isCaptionAbove() { return false; }
    }
    static class Picker {
        boolean incrementalGallery,externalCamera,mediaEnabled=true;
        final Parent parentAlert=new Parent(); int previews;
        void openCamera(boolean animated) { previews++; }
        OPEN_CAMERA
    }
    public static void main(String[] args) {
        Picker v2=new Picker(); v2.incrementalGallery=true; v2.externalCamera=true;
        check(v2.previews==0 && v2.parentAlert.delegate.launches==0,"no camera work before a tap");
        v2.openCameraByClick();
        check(v2.previews==0 && v2.parentAlert.delegate.launches==1,"V2 opens system camera even when in-app camera is enabled");
        v2.mediaEnabled=false; v2.openCameraByClick();
        check(v2.parentAlert.delegate.launches==1,"respect chat media restrictions");
        v2.mediaEnabled=true; NekoConfig.hideCameraInMediaPicker=true; v2.openCameraByClick();
        check(v2.parentAlert.delegate.launches==1,"respect hidden camera option");
        NekoConfig.hideCameraInMediaPicker=false; v2.externalCamera=false; v2.openCameraByClick();
        check(v2.parentAlert.delegate.launches==1,"respect constructor camera capability");
        Picker legacy=new Picker(); legacy.openCameraByClick();
        check(legacy.previews==1 && legacy.parentAlert.delegate.launches==0,"V1 camera is unchanged");
        System.out.println("OK");
    }
'''.replace('OPEN_CAMERA', open_camera))

    def test_localized_toggle_and_legacy_route(self):
        for directory in ('values', 'values-ru'):
            resources = ET.parse(ROOT / 'TMessagesProj/src/main/res' / directory / 'strings_neko.xml')
            names = [node.attrib.get('name') for node in resources.getroot()]
            for key in ('ExperimentalMediaPicker', 'ExperimentalMediaPickerHint'):
                self.assertEqual(names.count(key), 1)
        config = (JAVA / 'zxc/iconic/xenon/NekoConfig.java').read_text()
        self.assertIn('experimentalMediaPicker = preferences.getBoolean("experimentalMediaPicker", false)', config)
        attach = (JAVA / 'org/telegram/ui/Components/ChatAttachAlert.java').read_text()
        self.assertRegex(attach, r'NekoConfig.experimentalMediaPicker\s*\? new ChatAttachAlertPhotoLayoutV2\(')
        self.assertIn(': new ChatAttachAlertPhotoLayout(', attach)


if __name__ == '__main__':
    unittest.main()
