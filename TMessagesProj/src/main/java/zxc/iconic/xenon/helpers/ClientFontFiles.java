package zxc.iconic.xenon.helpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded font import. Archive paths are never used as destination paths. */
public final class ClientFontFiles {
    public static final long MAX_FONT_BYTES = 32L * 1024 * 1024;
    public static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;

    public interface Validator { void validate(File file) throws Exception; }

    public static final class Entry {
        public final String id;
        public final String name;
        public Entry(String id, String name) { this.id = id; this.name = name; }
        public File file(File directory) { return new File(directory, id + ".font"); }
    }

    private ClientFontFiles() {}

    public static List<Entry> importFonts(File directory, InputStream input, String filename, boolean zip, Validator validator) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create font directory");
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        ArrayList<File> created = new ArrayList<>();
        try {
            if (zip) {
                long[] total = {0};
                int count = 0;
                try (ZipInputStream archive = new ZipInputStream(input)) {
                    ZipEntry item;
                    while ((item = archive.getNextEntry()) != null) {
                        if (++count > MAX_ENTRIES) throw new IOException("Too many archive entries");
                        boolean candidate = !item.isDirectory() && isFontName(item.getName());
                        if (candidate) {
                            Entry entry = readFont(directory, archive, item.getName(), total, validator, created, true);
                            if (entry != null) entries.put(entry.id, entry);
                        } else {
                            drain(archive, total);
                        }
                        archive.closeEntry();
                    }
                }
            } else {
                Entry entry = readFont(directory, input, filename, new long[]{0}, validator, created, false);
                entries.put(entry.id, entry);
            }
            if (entries.isEmpty()) throw new IOException("No supported fonts found");
            return new ArrayList<>(entries.values());
        } catch (IOException | RuntimeException error) {
            for (File file : created) file.delete();
            throw error;
        }
    }

    private static boolean isFontName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith("__macosx/") && !lower.substring(lower.lastIndexOf('/') + 1).startsWith("._")
                && (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc"));
    }

    private static void drain(InputStream input, long[] total) throws IOException {
        byte[] buffer = new byte[16384];
        int size;
        while ((size = input.read(buffer)) != -1) {
            total[0] += size;
            if (total[0] > MAX_ARCHIVE_BYTES) throw new IOException("Archive is too large");
        }
    }

    private static Entry readFont(File directory, InputStream input, String filename, long[] total, Validator validator,
                                  List<File> created, boolean skipInvalid) throws IOException {
        File pending = new File(directory, ".import-" + UUID.randomUUID());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = 0;
            try (FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[16384];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    length += size;
                    total[0] += size;
                    if (length > MAX_FONT_BYTES || total[0] > MAX_ARCHIVE_BYTES) throw new IOException("Font import is too large");
                    output.write(buffer, 0, size);
                    digest.update(buffer, 0, size);
                }
            }
            String name;
            try {
                name = readName(pending, filename);
                validator.validate(pending);
            } catch (Exception invalid) {
                if (skipInvalid) return null;
                throw new IOException("Invalid font", invalid);
            }
            StringBuilder id = new StringBuilder(64);
            for (byte value : digest.digest()) id.append(String.format(Locale.ROOT, "%02x", value & 255));
            Entry entry = new Entry(id.toString(), name);
            File destination = entry.file(directory);
            if (!destination.exists()) {
                if (!pending.renameTo(destination)) throw new IOException("Cannot store font");
                created.add(destination);
            }
            return entry;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        } finally {
            pending.delete();
        }
    }

    /** Read the OpenType full name, falling back to the family name or source filename. */
    static String readName(File file, String filename) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 12) throw new IOException("Font header is truncated");
            int signature = input.readInt();
            long offset = 0;
            if (signature == 0x74746366) {
                input.readInt();
                long count = Integer.toUnsignedLong(input.readInt());
                if (count == 0 || input.length() < 16) throw new IOException("Invalid font collection");
                offset = Integer.toUnsignedLong(input.readInt());
                if (offset > input.length() - 12) throw new IOException("Invalid font offset");
                input.seek(offset);
                signature = input.readInt();
            }
            if (signature != 0x00010000 && signature != 0x4f54544f && signature != 0x74727565) throw new IOException("Unsupported font format");
            int tables = input.readUnsignedShort();
            if (tables > 4096 || offset + 12L + tables * 16L > input.length()) throw new IOException("Invalid font tables");
            input.seek(offset + 12);
            long nameOffset = 0, nameLength = 0;
            for (int i = 0; i < tables; i++) {
                int tag = input.readInt(); input.readInt();
                long start = Integer.toUnsignedLong(input.readInt());
                long length = Integer.toUnsignedLong(input.readInt());
                if (tag == 0x6e616d65) { nameOffset = start; nameLength = length; }
            }
            if (nameLength >= 6 && nameLength <= 1024 * 1024 && nameOffset <= input.length() - nameLength) {
                input.seek(nameOffset);
                input.readUnsignedShort();
                int count = input.readUnsignedShort();
                int strings = input.readUnsignedShort();
                if (6L + count * 12L <= nameLength && strings <= nameLength) {
                    String best = null;
                    int bestScore = -1;
                    for (int i = 0; i < count; i++) {
                        input.seek(nameOffset + 6L + i * 12L);
                        int platform = input.readUnsignedShort(); input.readUnsignedShort();
                        int language = input.readUnsignedShort();
                        int nameId = input.readUnsignedShort();
                        int length = input.readUnsignedShort(), start = input.readUnsignedShort();
                        if (nameId != 4 && nameId != 16 && nameId != 1 || length == 0 || length > 4096 || strings + (long) start + length > nameLength) continue;
                        int score = (nameId == 4 ? 30 : nameId == 16 ? 20 : 10) + (platform == 3 ? 3 : platform == 0 ? 2 : 0) + (language == 0x409 || language == 0 ? 5 : 0);
                        if (score <= bestScore) continue;
                        byte[] data = new byte[length];
                        input.seek(nameOffset + strings + start); input.readFully(data);
                        String value = new String(data, platform == 0 || platform == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1).replaceAll("[\\p{Cntrl}]", "").trim();
                        if (!value.isEmpty()) { best = value; bestScore = score; }
                    }
                    if (best != null) return best.substring(0, Math.min(best.length(), 120));
                }
            }
        }
        String clean = filename.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceFirst("(?i)\\.(ttf|otf|ttc)$", "").replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.isEmpty() ? "Font" : clean.substring(0, Math.min(clean.length(), 120));
    }
}
