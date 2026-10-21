package zxc.iconic.xenon.osu;

import org.telegram.messenger.ApplicationLoader;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Persistent public osu! downloads. Imported maps and original Telegram files are separate. */
public final class OsuLocalCache {
    private OsuLocalCache() { }
    public static final Object LOCK = new Object();
    public interface Progress { void update(long downloaded, long total); }
    private static File root() { return new File(ApplicationLoader.getFilesDirFixed(), "osu-cache"); }
    public static File file(String group, String key) {
        if (!group.matches("maps|metadata|avatars|sets|media|sets-video|media-video") || !key.matches("[a-zA-Z0-9_.-]+") || key.contains("..")) throw new IllegalArgumentException("Invalid cache key");
        return new File(new File(root(), group), key);
    }
    public static File fetch(String group, String key, String address, int limit, String expectedMd5) throws IOException {
        return fetch(group,key,address,limit,expectedMd5,null);
    }
    public static File fetch(String group, String key, String address, int limit, String expectedMd5, Progress progress) throws IOException {
        synchronized (LOCK) {
            File target = file(group, key);
            if (target.isFile() && target.length() > 0 && target.length() <= limit && (expectedMd5 == null || expectedMd5.equals(md5(target)))) return target;
            File temporary = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "Xenon osu replay viewer");
                if (connection.getResponseCode() != 200) throw new IOException("osu! download unavailable");
                long contentLength=connection.getContentLengthLong(), lastReport=0;
                if(progress!=null)progress.update(0,contentLength);
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create osu! cache");
                temporary = File.createTempFile("download-", ".tmp", target.getParentFile());
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temporary)) {
                    byte[] block = new byte[8192]; int total = 0;
                    for (int n; (n = in.read(block)) >= 0;) {
                        total += n; if (total > limit) throw new IOException("osu! download too large");
                        out.write(block, 0, n);
                        long now=System.nanoTime();
                        if(progress!=null&&now-lastReport>=100_000_000){progress.update(total,contentLength);lastReport=now;}
                    }
                    if (total == 0) throw new IOException("Empty osu! download");
                    if(progress!=null)progress.update(total,contentLength);
                }
                if (expectedMd5 != null && !expectedMd5.equals(md5(temporary))) throw new IOException("Beatmap checksum mismatch");
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return target;
            } finally {
                if (connection != null) connection.disconnect();
                if (temporary != null) temporary.delete();
            }
        }
    }
    private static String md5(File file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("MD5");
            try (InputStream in = new FileInputStream(file)) { byte[] bytes = new byte[8192]; for (int n; (n = in.read(bytes)) >= 0;) hash.update(bytes, 0, n); }
            StringBuilder text = new StringBuilder(); for (byte b : hash.digest()) text.append(String.format(Locale.US, "%02x", b & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    private static File[] roots() { return new File[]{root(), new File(ApplicationLoader.getFilesDirFixed(), "osu-replay-maps")}; }
    public static long size() throws IOException {
        synchronized (LOCK) {
            final long[] bytes = {0};
            for (File directory : roots()) if (directory.exists()) Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) { if (attrs.isRegularFile()) bytes[0] += attrs.size(); return FileVisitResult.CONTINUE; }
            });
            return bytes[0];
        }
    }
    public static void clear() throws IOException {
        synchronized (LOCK) {
            // walkFileTree does not follow symbolic links; only these two app-owned roots are removed.
            for (File directory : roots()) if (directory.exists()) Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException { Files.delete(path); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException { if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE; }
            });
        }
    }
}
