package zxc.iconic.xenon.osu;

import org.telegram.messenger.ApplicationLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Imported skins are user data, separate from the disposable replay download cache. */
public final class OsuSkinStore {
    private OsuSkinStore(){}
    public static File root(){return new File(ApplicationLoader.getFilesDirFixed(),"osu-skins");}
    public static File directory(String key){return key!=null&&key.matches("skin-[0-9a-f]{32}")?new File(root(),key):null;}
    public static List<File> list(){File[] files=root().listFiles(f->f.isDirectory()&&directory(f.getName())!=null);if(files==null)return Collections.emptyList();Arrays.sort(files,Comparator.comparing(OsuSkinStore::name));return Arrays.asList(files);}
    public static String readInfo(File file)throws IOException{
        if(file.length()>256*1024)throw new IOException("Skin metadata too large");return decodeInfo(Files.readAllBytes(file.toPath()));
    }
    private static String decodeInfo(byte[] bytes)throws IOException{
        if(bytes.length>256*1024)throw new IOException("Skin metadata too large");
        if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
        if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
        return new String(bytes,StandardCharsets.UTF_8).replace("\ufeff","");
    }
    public static String name(File dir){
        try(BufferedReader in=new BufferedReader(new StringReader(readInfo(new File(dir,"skin.ini"))))){String line;while((line=in.readLine())!=null){int split=line.indexOf(':');if(split>0&&line.substring(0,split).trim().equalsIgnoreCase("Name"))return line.substring(split+1).trim();}}catch(IOException ignored){}
        return dir.getName();
    }
    public static synchronized String importSkin(InputStream input) throws Exception {
        File base=root();if(!base.isDirectory()&&!base.mkdirs())throw new IOException("Cannot create skins directory");
        File archive=File.createTempFile("skin-",".tmp",base),staging=null;
        try {
            MessageDigest hash=MessageDigest.getInstance("MD5");
            try(OutputStream out=new FileOutputStream(archive)){byte[] buffer=new byte[8192];long total=0;for(int n;(n=input.read(buffer))>=0;){total+=n;if(total>128*1024*1024)throw new IOException("Skin archive too large");hash.update(buffer,0,n);out.write(buffer,0,n);}}
            StringBuilder key=new StringBuilder("skin-");for(byte b:hash.digest())key.append(String.format(Locale.US,"%02x",b&255));
            File target=directory(key.toString());if(new File(target,".format-v3").isFile())return key.toString();
            staging=Files.createTempDirectory(base.toPath(),"import-").toFile();
            try(ZipFile zip=new ZipFile(archive)){
                String prefix=null;ZipEntry metadata=null;int scanned=0;
                for(Enumeration<? extends ZipEntry> it=zip.entries();it.hasMoreElements();){
                    ZipEntry entry=it.nextElement();if(++scanned>8192)throw new IOException("Too many skin entries");
                    String path=entry.getName().replace('\\','/');
                    if(!entry.isDirectory()&&path.substring(path.lastIndexOf('/')+1).equalsIgnoreCase("skin.ini")){
                        String candidate=path.substring(0,path.length()-8);
                        if(prefix==null||candidate.length()<prefix.length()){prefix=candidate;metadata=entry;}
                    }
                }
                if(prefix==null)prefix="";
                HashSet<String> fontPrefixes=new HashSet<>();
                if(metadata!=null)try(InputStream in=zip.getInputStream(metadata)){
                    for(String line:decodeInfo(in.readNBytes(256*1024+1)).split("\\R")){
                        int colon=line.indexOf(':');if(colon<0)continue;
                        String property=line.substring(0,colon).trim().toLowerCase(Locale.ROOT);
                        if(!property.matches("hitcircleprefix|scoreprefix|comboprefix"))continue;
                        String value=line.substring(colon+1).split("//",2)[0].trim().replace('\\','/').toLowerCase(Locale.ROOT);
                        if(!value.startsWith("/")&&!value.contains(":")&&!Arrays.asList(value.split("/")).contains("..")&&value.split("/").length<=7)fontPrefixes.add(value);
                    }
                }
                int entries=0,accepted=0;long total=0;HashSet<String> names=new HashSet<>();
                for(Enumeration<? extends ZipEntry> it=zip.entries();it.hasMoreElements();){
                    ZipEntry entry=it.nextElement();if(++entries>8192)throw new IOException("Too many skin entries");
                    String path=entry.getName().replace('\\','/');
                    if(path.startsWith("/")||path.contains(":")||Arrays.asList(path.split("/")).contains(".."))throw new IOException("Invalid skin path");
                    if(entry.isDirectory()||!path.startsWith(prefix))continue;
                    String name=path.substring(prefix.length()).toLowerCase(Locale.ROOT);
                    if(name.contains("/")){
                        String logical=OsuSkinAssets.name(name);boolean font=false;
                        for(String fontPrefix:fontPrefixes)if(OsuSkinAssets.fontAsset(logical,fontPrefix)){font=true;break;}
                        if(!font||!name.endsWith(".png"))continue;
                    }else if(!allowed(name))continue;
                    if(!names.add(name))throw new IOException("Duplicate skin asset");
                    File file=safeFile(staging,name);Files.createDirectories(file.toPath().getParent());long bytes=0;
                    try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>=0;){bytes+=n;total+=n;if(bytes>(name.endsWith(".ini")?256*1024:8*1024*1024)||total>128*1024*1024)throw new IOException("Skin data too large");out.write(buffer,0,n);}}
                    if(!name.endsWith(".ini"))accepted++;
                }
                if(accepted==0)throw new IOException("No standard skin assets found");
            }
            if(target.isDirectory()){
                try(var paths=Files.walk(staging.toPath())){for(Path file:paths.filter(Files::isRegularFile).toList()){
                    File destination=safeFile(target,staging.toPath().relativize(file).toString());Files.createDirectories(destination.toPath().getParent());
                    Files.move(file,destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
                }}
                remove(staging);
            }else Files.move(staging.toPath(),target.toPath());
            Files.writeString(new File(target,".format-v3").toPath(),"3",StandardCharsets.UTF_8);
            staging=null;return key.toString();
        } finally {archive.delete();if(staging!=null)remove(staging);}
    }
    private static File safeFile(File base,String name)throws IOException{
        Path root=base.toPath().toAbsolutePath().normalize(),file=root.resolve(name).normalize();
        if(file.equals(root)||!file.startsWith(root))throw new IOException("Invalid skin path");return file.toFile();
    }
    private static boolean allowed(String name){
        return name.equals("skin.ini")||name.matches("(?:(?:normal|soft|drum)-(?:hitnormal|hitclap|hitwhistle|hitfinish|slidertick)|combobreak|failsound|spinnerbonus(?:-max)?)\\.(?:wav|ogg|mp3)")
            ||name.matches("[\\p{L}\\p{N} _().!+@-]{1,120}\\.png");
    }
    private static void remove(File directory)throws IOException{Path base=root().toPath().toAbsolutePath().normalize(),target=directory.toPath().toAbsolutePath().normalize();if(target.equals(base)||!target.startsWith(base))throw new IOException("Invalid skin cleanup path");try(var paths=Files.walk(target)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
}
