package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Exact set archive, shared with the replay cache. No extraction and no prefetch. */
public final class OsuBeatmapArchive {
    private OsuBeatmapArchive() { }
    public static File fetch(long setId,BooleanSupplier cancelled,OsuLocalCache.Progress progress)throws IOException {
        if(setId<=0)throw new IOException("Invalid beatmapset id");
        synchronized(OsuLocalCache.LOCK) {
            check(cancelled);
            for(String group:new String[]{"sets-video","sets"}) {
                File cached=OsuLocalCache.file(group,setId+".osz");
                if(cached.isFile())try{validate(cached,setId);check(cancelled);return cached;}catch(InterruptedIOException error){throw error;}catch(IOException invalid){cached.delete();}
            }
            File archive=null;
            try {
                archive=OsuLocalCache.fetch("sets-video",setId+".osz",
                        "https://mirror.hinamizawa.ai/api/v1/hinai/d/"+setId,512*1024*1024,null,(bytes,total)->{
                            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
                            if(progress!=null)progress.update(bytes,total);
                        });
                check(cancelled);validate(archive,setId);check(cancelled);return archive;
            }catch(CancellationException error){throw new InterruptedIOException("Download cancelled");}
            catch(IOException error){if(archive!=null)archive.delete();throw error;}
        }
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Download cancelled");
    }
    /** Stage a separate upload copy; Telegram rejects private /data/data paths. */
    public static File forSending(File archive,File directory,long setId,String heading,BooleanSupplier cancelled)throws IOException {
        synchronized(OsuLocalCache.LOCK) {
            check(cancelled);
            if(directory==null)throw new IOException("Upload cache unavailable");
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create upload cache");
            String name=heading.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
            name=name.substring(0,Math.min(100,name.length()));
            File target=new File(directory,setId+(name.isEmpty()?"":" "+name)+".osz");
            if(target.isFile()&&target.length()==archive.length())try{validate(target,setId);return target;}catch(IOException invalid){target.delete();}
            File temporary=File.createTempFile("osu-upload-",".part",directory);
            try {
                try(InputStream in=new FileInputStream(archive);OutputStream out=new FileOutputStream(temporary)) {
                    byte[] block=new byte[65536];for(int n;(n=in.read(block))!=-1;){check(cancelled);out.write(block,0,n);}
                }
                check(cancelled);validate(temporary,setId);
                java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target;
            }finally{temporary.delete();}
        }
    }
    public static void validate(File archive,long setId)throws IOException {
        if(!archive.isFile()||archive.length()==0||archive.length()>512L*1024*1024)throw new IOException("Invalid archive size");
        boolean matching=false;int count=0;
        try(ZipFile zip=new ZipFile(archive)) {
            for(Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();) {
                ZipEntry entry=entries.nextElement();if(++count>4096)throw new IOException("Too many archive entries");
                String name=entry.getName().replace('\\','/');
                if(name.startsWith("/")||name.contains(":")||Arrays.asList(name.split("/")).contains(".."))throw new IOException("Unsafe archive path");
                if(entry.isDirectory()||!name.toLowerCase(Locale.ROOT).endsWith(".osu"))continue;
                try(InputStream raw=zip.getInputStream(entry);
                    InputStream limited=new FilterInputStream(raw){int remaining=1024*1024;
                        @Override public int read(byte[] bytes,int offset,int length)throws IOException{if(remaining<=0)throw new IOException("Map metadata too large");int n=super.read(bytes,offset,Math.min(length,remaining));if(n>0)remaining-=n;return n;}
                        @Override public int read()throws IOException{if(remaining--<=0)throw new IOException("Map metadata too large");return super.read();}};
                    BufferedReader reader=new BufferedReader(new InputStreamReader(limited,StandardCharsets.UTF_8))) {
                    String line;boolean metadata=false;
                    while((line=reader.readLine())!=null) {
                        line=line.trim();
                        if(line.startsWith("[")){if(metadata)break;metadata=line.equals("[Metadata]");}
                        if(metadata&&line.startsWith("BeatmapSetID:"))try{long id=Long.parseLong(line.substring(13).trim());if(id>0&&id!=setId)throw new IOException("Beatmapset mismatch");matching|=id==setId;}catch(NumberFormatException invalid){throw new IOException("Invalid beatmapset id",invalid);}
                    }
                }
            }
        }
        if(!matching)throw new IOException("Archive has no matching beatmap");
    }
}
