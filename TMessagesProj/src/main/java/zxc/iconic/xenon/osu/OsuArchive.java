package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports maps and their referenced audio, background and video. */
public final class OsuArchive {
    public static final class MissingAudio extends IOException {
        MissingAudio() { super("Beatmap audio not found"); }
    }
    public static final class Track {
        public final OsuBeatmap map;
        public final File mapFile, audioFile;
        Track(OsuBeatmap map, File mapFile, File audioFile) { this.map = map; this.mapFile = mapFile; this.audioFile = audioFile; }
    }
    /** Prefer Kiai, then the hardest local difficulty; preserve input order for exact ties. */
    public static Track preferredTrack(List<Track> tracks) {
        Track best = null;
        for (Track track : tracks) {
            if (best == null || (track.map.hasKiai && !best.map.hasKiai)
                    || (track.map.hasKiai == best.map.hasKiai && track.map.compareDifficultyTo(best.map) > 0)) best = track;
        }
        return best;
    }
    public static File resolve(File root, String name) throws IOException {
        name = name.replace('\\', '/');
        if (name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Invalid archive path");
        File target = new File(root, name).getCanonicalFile();
        if (!target.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Archive path escapes directory");
        return target;
    }
    private static OsuBeatmap read(File file) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { return OsuBeatmap.read(reader); }
    }
    private static void copy(InputStream input, File file, long limit) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create import directory");
        try (OutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[32768];
            long size = 0;
            for (int n; (n = input.read(buffer)) >= 0;) {
                size += n;
                if (size > limit) throw new IOException("Beatmap resource too large");
                output.write(buffer, 0, n);
            }
        }
    }
    /** Upgrade older audio-only imports using their original archive, without replacing maps/audio. */
    public static void restoreMedia(File source,File root)throws IOException{
        root=root.getCanonicalFile();
        File marker=new File(root,".background-media-v1");if(marker.isFile())return;
        try(ZipFile zip=new ZipFile(source)){
            Map<String,ZipEntry> entries=new HashMap<>();Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()){ZipEntry entry=all.nextElement();if(entries.size()>=4096)throw new IOException("Too many archive entries");entries.put(entry.getName().replace('\\','/').toLowerCase(Locale.ROOT),entry);}
            long total=0;
            for(Track track:load(root))for(String name:new String[]{track.map.background,track.map.video})if(!name.isEmpty()){
                String relative=root.toURI().relativize(track.mapFile.getParentFile().toURI()).getPath()+name;File target=resolve(root,relative);
                if(target.isFile())continue;ZipEntry entry=entries.get(root.toURI().relativize(target.toURI()).getPath().toLowerCase(Locale.ROOT));if(entry==null)continue;
                File temporary=File.createTempFile("background-", ".tmp",root);
                try{try(InputStream in=zip.getInputStream(entry)){copy(in,temporary,256L*1024*1024);}total+=temporary.length();if(total>512L*1024*1024)throw new IOException("Background resources too large");
                    File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create media directory");java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }finally{temporary.delete();}
            }
        }
        try(OutputStream markerFile=new FileOutputStream(marker)){markerFile.write(1);}
    }
    public static List<Track> load(File root) throws IOException {
        root = root.getCanonicalFile();
        ArrayList<Track> result = new ArrayList<>();
        scan(root, root, result, 0);
        return result;
    }
    private static void scan(File root, File folder, List<Track> result, int depth) throws IOException {
        if (depth > 16) throw new IOException("Too many nested directories");
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isDirectory()) scan(root, file, result, depth + 1);
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".osu")) {
                OsuBeatmap map = read(file);
                String relative = root.toURI().relativize(file.getParentFile().toURI()).getPath() + map.audioFilename;
                File audio = resolve(root, relative);
                if (audio.isFile()) {
                    map.starRating = OsuDifficulty.calculate(file);
                    result.add(new Track(map, file, audio));
                }
            }
        }
    }
    public static List<Track> importFile(File source, String name, File destination) throws IOException {
        return importFile(source, name, destination, null);
    }
    public static List<Track> importFile(File source, String name, File destination, File suppliedAudio) throws IOException {
        destination = destination.getCanonicalFile();
        if (!destination.mkdirs()) throw new IOException("Import directory already exists");
        try {
            if (name.toLowerCase(Locale.ROOT).endsWith(".osz")) {
                try (ZipFile zip = new ZipFile(source)) {
                    Map<String, ZipEntry> entries = new LinkedHashMap<>();
                    Enumeration<? extends ZipEntry> iterator = zip.entries();
                    while (iterator.hasMoreElements()) {
                        ZipEntry entry = iterator.nextElement();
                        String normalized = destination.toURI().relativize(resolve(destination, entry.getName()).toURI()).getPath();
                        if (entries.put(normalized.toLowerCase(Locale.ROOT), entry) != null) throw new IOException("Duplicate archive path");
                        if (entries.size() > 4096) throw new IOException("Too many archive entries");
                    }
                    long total = 0;
                    for (ZipEntry entry : entries.values()) {
                        if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".osu")) continue;
                        File mapFile = resolve(destination, entry.getName());
                        try (InputStream input = zip.getInputStream(entry)) { copy(input, mapFile, 8 * 1024 * 1024); }
                        total += mapFile.length();
                        OsuBeatmap map = read(mapFile);
                        String audioName = destination.toURI().relativize(mapFile.getParentFile().toURI()).getPath() + map.audioFilename;
                        File audioFile = resolve(destination, audioName);
                        String key = destination.toURI().relativize(audioFile.toURI()).getPath().toLowerCase(Locale.ROOT);
                        ZipEntry audioEntry = entries.get(key);
                        if (audioEntry != null && !audioFile.isFile()) {
                            try (InputStream input = zip.getInputStream(audioEntry)) { copy(input, audioFile, 64 * 1024 * 1024); }
                            total += audioFile.length();
                        }
                        for(String resource:new String[]{map.background,map.video})if(!resource.isEmpty()){
                            String relative=destination.toURI().relativize(mapFile.getParentFile().toURI()).getPath()+resource;
                            File target=resolve(destination,relative);ZipEntry media=entries.get(destination.toURI().relativize(target.toURI()).getPath().toLowerCase(Locale.ROOT));
                            if(media!=null&&!target.isFile()){try(InputStream in=zip.getInputStream(media)){copy(in,target,256L*1024*1024);}total+=target.length();}
                        }
                        if (total > 512L * 1024 * 1024) throw new IOException("Beatmap set too large");
                    }
                }
            } else {
                File mapFile = resolve(destination, "map.osu");
                try (InputStream input = new FileInputStream(source)) { copy(input, mapFile, 8 * 1024 * 1024); }
                OsuBeatmap map = read(mapFile);
                File audioSource = suppliedAudio != null ? suppliedAudio : resolve(source.getParentFile(), map.audioFilename);
                if (audioSource.isFile()) try (InputStream input = new FileInputStream(audioSource)) {
                    copy(input, resolve(destination, map.audioFilename), 64 * 1024 * 1024);
                }
                else throw new MissingAudio();
                for(String resource:new String[]{map.background,map.video})if(!resource.isEmpty()){
                    File local=resolve(source.getParentFile(),resource);if(local.isFile())try(InputStream in=new FileInputStream(local)){copy(in,resolve(destination,resource),256L*1024*1024);}
                }
            }
            List<Track> tracks = load(destination);
            if (tracks.isEmpty()) throw new IOException("No beatmap audio found");
            return tracks;
        } catch (IOException | RuntimeException error) {
            remove(destination);
            throw error;
        }
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
