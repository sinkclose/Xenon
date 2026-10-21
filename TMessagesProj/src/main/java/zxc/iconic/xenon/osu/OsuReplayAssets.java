package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Only the exact replay difficulty and its referenced audio/background are unpacked. */
public final class OsuReplayAssets {
    public File map, audio, background, video;
    static OsuReplayAssets localAutoplay(File mapFile,File audio)throws IOException{
        if(!mapFile.isFile()||!audio.isFile())throw new IOException("Autoplay resources missing");
        OsuPlaybackMap parsed=OsuPlaybackMap.read(mapFile);OsuReplayAssets assets=new OsuReplayAssets();assets.map=mapFile;assets.audio=audio;
        if(!parsed.background.isEmpty()){File bg=safe(mapFile.getParentFile(),parsed.background);if(bg.isFile())assets.background=bg;}
        if(!parsed.video.isEmpty()){File video=safe(mapFile.getParentFile(),parsed.video);if(video.isFile())assets.video=video;}
        return assets;
    }
    private static File safe(File directory,String name) throws IOException {
        name=name.replace('\\','/');
        File result=new File(directory,name).getCanonicalFile();
        if(name.startsWith("/")||name.contains(":")||!result.getPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IOException("Invalid map resource path");
        return result;
    }
    private static OsuReplayAssets local(File mapFile) throws IOException {
        OsuPlaybackMap map=OsuPlaybackMap.read(mapFile);
        OsuReplayAssets assets=new OsuReplayAssets();assets.map=mapFile;
        assets.audio=safe(mapFile.getParentFile(),map.audio);
        if(!map.background.isEmpty()){File bg=safe(mapFile.getParentFile(),map.background);if(bg.isFile())assets.background=bg;}
        if(!map.video.isEmpty()){File video=safe(mapFile.getParentFile(),map.video);if(video.isFile())assets.video=video;}
        return assets.audio.isFile()?assets:null;
    }
    public static OsuReplayAssets prepare(File mapFile,String checksum) throws Exception {
        return prepare(mapFile,checksum,null);
    }
    public static OsuReplayAssets prepare(File mapFile,String checksum,OsuLocalCache.Progress progress) throws Exception {return prepare(mapFile,checksum,progress,true);}
    public static OsuReplayAssets prepare(File mapFile,String checksum,OsuLocalCache.Progress progress,boolean videos) throws Exception {
        synchronized(OsuLocalCache.LOCK) {
            OsuPlaybackMap parsed=OsuPlaybackMap.read(mapFile);boolean wantVideo=videos&&!parsed.video.isEmpty();
            OsuReplayAssets assets=local(mapFile);
            if(assets!=null && (assets.background!=null || parsed.background.isEmpty())&&(!wantVideo||assets.video!=null))return assets;
            File directory=OsuLocalCache.file(wantVideo?"media-video":"media",checksum), extracted=new File(directory,"map.osu");
            if(extracted.isFile()){OsuReplayAssets cached=local(extracted);if(cached!=null&&(!wantVideo||cached.video!=null))return cached;}
            OsuPlaybackMap map=OsuPlaybackMap.read(mapFile);
            if(map.setId<=0){if(assets!=null)return assets;throw new IOException("Map set unavailable");}
            File archive=null;
            try{archive=OsuLocalCache.fetch(wantVideo?"sets-video":"sets",map.setId+".osz","https://txy1.sayobot.cn/beatmaps/download/"+(wantVideo?"full/":"novideo/")+map.setId,512*1024*1024,null,progress);return extract(archive,directory,checksum,videos);}
            catch(Exception error){if(archive!=null)archive.delete();if(wantVideo){if(assets!=null)return assets;return prepare(mapFile,checksum,progress,false);}throw error;}
        }
    }
    public static OsuReplayAssets extract(File archive,File destination,String checksum) throws Exception {return extract(archive,destination,checksum,true);}
    public static OsuReplayAssets extract(File archive,File destination,String checksum,boolean videos) throws Exception {
        try(ZipFile zip=new ZipFile(archive)) {
            HashMap<String,ZipEntry> entries=new HashMap<>();
            for(Enumeration<? extends ZipEntry> iterator=zip.entries();iterator.hasMoreElements();) {
                ZipEntry entry=iterator.nextElement();safe(destination,entry.getName());
                if(entries.size()>=4096)throw new IOException("Too many map resources");
                if(entries.put(entry.getName().replace('\\','/').toLowerCase(Locale.ROOT),entry)!=null)throw new IOException("Duplicate map resource");
            }
            byte[] matching=null;String mapName=null;long scanned=0;
            for(Map.Entry<String,ZipEntry> entry:entries.entrySet())if(entry.getKey().endsWith(".osu")) {
                byte[] bytes;try(InputStream in=zip.getInputStream(entry.getValue())){bytes=read(in,8*1024*1024);}
                scanned+=bytes.length;if(scanned>32*1024*1024)throw new IOException("Too many map bytes");
                StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest(bytes))hash.append(String.format(Locale.US,"%02x",b&255));
                if(hash.toString().equals(checksum)){matching=bytes;mapName=entry.getKey();break;}
            }
            if(matching==null)throw new IOException("The map version in this set does not match the replay");
            if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("Cannot create replay resources");
            // Parse a temporary map; publish map.osu last so interrupted extraction is retried.
            File temporary=File.createTempFile("map-",".osu",destination);
            try {
                Files.write(temporary.toPath(),matching);
                OsuPlaybackMap map=OsuPlaybackMap.read(temporary);
                String prefix=mapName.contains("/")?mapName.substring(0,mapName.lastIndexOf('/')+1):"";
                copy(zip,entries,prefix+map.audio,safe(destination,map.audio),64*1024*1024,true);
                if(!map.background.isEmpty())copy(zip,entries,prefix+map.background,safe(destination,map.background),16*1024*1024,false);
                if(videos&&!map.video.isEmpty())copy(zip,entries,prefix+map.video,safe(destination,map.video),256*1024*1024,false);
                Files.move(temporary.toPath(),new File(destination,"map.osu").toPath(),StandardCopyOption.REPLACE_EXISTING);
                return local(new File(destination,"map.osu"));
            } finally {temporary.delete();}
        }
    }
    private static void copy(ZipFile zip,Map<String,ZipEntry> entries,String name,File target,int limit,boolean required) throws IOException {
        ZipEntry entry=entries.get(name.replace('\\','/').toLowerCase(Locale.ROOT));
        if(entry==null){if(required)throw new IOException("Map audio missing");return;}
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create resource directory");
        File temporary=File.createTempFile("resource-", ".tmp",parent);
        try {
            try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(temporary)) {
                byte[] buffer=new byte[8192];long total=0;
                for(int n;(n=in.read(buffer))>=0;){total+=n;if(total>limit)throw new IOException("Map resource too large");out.write(buffer,0,n);}
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        } finally {temporary.delete();}
    }
    private static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        for(int n;(n=in.read(buffer))>=0;){if(out.size()+n>limit)throw new IOException("Map resource too large");out.write(buffer,0,n);}return out.toByteArray();
    }
}
