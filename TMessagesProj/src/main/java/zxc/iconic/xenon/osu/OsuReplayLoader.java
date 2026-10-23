package zxc.iconic.xenon.osu;

import org.json.*;
import org.telegram.messenger.ApplicationLoader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Resolves public beatmap metadata by checksum; replay frames never leave the device. */
final class OsuReplayLoader {
    static OsuReplay read(File file) throws Exception {
        OsuReplay replay = OsuReplay.read(file);
        if (replay.lazer && replay.scoreInfo == null) replay.performanceSupported = false;
        if (replay.scoreInfo != null) {
            String text = OsuDifficulty.replayJson(replay.scoreInfo);
            if (text == null) throw new IOException("Invalid compressed replay metadata");
            JSONObject info = new JSONObject(text);
            replay.userId = Math.max(0, info.optLong("user_id"));
            replay.onlineId = info.optLong("online_id", replay.onlineId);
            String rank = info.optString("rank", "");
            if (rank.matches("XH?|SH?|A|B|C|D|F")) replay.rank = rank;
            readCounts(info.optJSONObject("statistics"), replay.statistics);
            readCounts(info.optJSONObject("maximum_statistics"), replay.maximum);
            replay.great = replay.count("great"); replay.ok = replay.count("ok"); replay.meh = replay.count("meh"); replay.miss = replay.count("miss");
            replay.geki = replay.count("perfect"); replay.katu = replay.count("good");
            if (replay.mode == 2) { replay.ok = replay.count("large_tick_hit"); replay.meh = replay.count("small_tick_hit"); replay.katu = replay.count("small_tick_miss"); }
            JSONArray mods = info.optJSONArray("mods");
            if (mods != null) {
                StringBuilder label = new StringBuilder(); replay.mods = 0;
                String[] names = {"NF","EZ","TD","HD","HR","SD","DT","RX","HT","NC","FL","AT","SO","AP","PF","4K","5K","6K","7K","8K","FI","RD","CN","TP","9K","CO","1K","3K","2K","V2","MR"};
                for (int i = 0; i < mods.length(); i++) {
                    JSONObject mod = mods.getJSONObject(i); String acronym = mod.optString("acronym");
                    if (label.length() > 0) label.append(' '); label.append(acronym);
                    int bit = Arrays.asList(names).indexOf(acronym);if(acronym.equals("DC")){bit=8;replay.adjustPitch=true;}
                    if (bit < 0) replay.performanceSupported = false; else replay.mods |= 1L << bit;
                    JSONObject settings = mod.optJSONObject("settings");
                    if(acronym.equals("NC"))replay.adjustPitch=true;
                    if(settings!=null){
                        if(acronym.equals("HD"))replay.hiddenOnlyApproach=settings.optBoolean("only_fade_approach_circles",false);
                        if(acronym.equals("FL")){replay.flashlightDelay=Math.max(0,Math.min(1200,settings.optDouble("follow_delay",120)));replay.flashlightSize=Math.max(.1,Math.min(10,settings.optDouble("size_multiplier",1)));replay.flashlightCombo=settings.optBoolean("combo_based_size",true);}
                        if(acronym.equals("MR"))replay.mirrorReflection=Math.max(0,Math.min(2,settings.optInt("reflection",0)));
                        if(acronym.equals("DT")||acronym.equals("NC")||acronym.equals("HT")||acronym.equals("DC")){
                            replay.adjustPitch=settings.optBoolean("adjust_pitch",replay.adjustPitch);
                            double speed=settings.optDouble("speed_change",acronym.equals("DT")||acronym.equals("NC")?1.5:.75);
                            if(Double.isFinite(speed)&&speed>=.1&&speed<=3)replay.clockRate=speed;
                        }
                    }
                    if(acronym.equals("DA")&&settings!=null){
                        replay.overrideCS=settings.optDouble("circle_size",Double.NaN);replay.overrideAR=settings.optDouble("approach_rate",Double.NaN);
                        replay.overrideOD=settings.optDouble("overall_difficulty",Double.NaN);replay.overrideHP=settings.optDouble("drain_rate",Double.NaN);
                    }
                    if (settings != null && settings.length() > 0) {
                        if ((acronym.equals("DT") || acronym.equals("NC") || acronym.equals("HT")) && settings.length() == 1 && settings.has("speed_change")) {
                            double rate = settings.optDouble("speed_change", 0);
                            if (rate >= .1 && rate <= 3) replay.clockRate = rate; else replay.performanceSupported = false;
                        } else replay.performanceSupported = false;
                    }
                }
                replay.modsLabel = label.length() == 0 ? "NM" : label.toString();
            }
        }
        return replay;
    }
    private static void readCounts(JSONObject object, Map<String, Integer> target) throws JSONException {
        if (object == null) return;
        target.clear();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next(); long count = object.getLong(key);
            if (count < 0 || count > 10000000) throw new JSONException("Invalid judgement count");
            target.put(key, (int) count);
        }
    }
    static final class Metadata {
        File mapFile;
        String title = "", artist = "", difficulty = "", mapper = "";
        double stars = Double.NaN, pp = Double.NaN;
        int maxCombo;
        void applyTo(OsuReplay replay) {
            if (!title.isEmpty()) replay.title=title;
            if (!artist.isEmpty()) replay.artist=artist;
            if (!difficulty.isEmpty()) replay.difficulty=difficulty;
            if (!mapper.isEmpty()) replay.mapper=mapper;
            if (Double.isFinite(stars)) replay.stars=stars;
            if (Double.isFinite(pp)) replay.pp=pp;
            if (maxCombo>0) replay.maxCombo=maxCombo;
        }
    }
    // This small, map-only record survives independently of the .osu download and
    // never stores score/mod-dependent PP. Read it without waiting for network work.
    static Metadata cachedMetadata(OsuReplay replay) throws Exception {
        File details=OsuLocalCache.file("metadata",replay.beatmapHash+".details.json");
        if(details.isFile())try {
            if(details.length()>64*1024)throw new IOException("Invalid cached metadata size");
            JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(details.toPath()),StandardCharsets.UTF_8));
            if(!replay.beatmapHash.equals(json.getString("checksum")))throw new IOException("Cached metadata checksum mismatch");
            Metadata result=new Metadata();result.title=json.getString("title");result.artist=json.optString("artist");
            result.difficulty=json.getString("version");result.mapper=json.optString("creator");
            if(!result.title.isEmpty()&&!result.difficulty.isEmpty())return result;
        } catch(Exception invalid) { details.delete(); }
        for(File map:new File[]{OsuLocalCache.file("maps",replay.beatmapHash+".osu"),
                new File(new File(ApplicationLoader.getFilesDirFixed(),"osu-replay-maps"),replay.beatmapHash+".osu"),
                new File(OsuLocalCache.file("media",replay.beatmapHash),"map.osu"),
                new File(OsuLocalCache.file("media-video",replay.beatmapHash),"map.osu")}) {
            if(map.isFile()&&map.length()<=8*1024*1024&&replay.beatmapHash.equals(md5(map))) {
                Metadata result=mapMetadata(map);saveMetadata(replay,result);return result;
            }
        }
        // Older API responses sometimes include full beatmapset metadata.
        File api=OsuLocalCache.file("metadata",replay.beatmapHash+".json");
        if(api.isFile()&&api.length()<=512*1024)try {
            JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(api.toPath()),StandardCharsets.UTF_8));
            if(replay.beatmapHash.equalsIgnoreCase(json.optString("checksum"))) {
                Metadata result=new Metadata();result.difficulty=json.optString("version");
                JSONObject set=json.optJSONObject("beatmapset");if(set!=null){result.title=set.optString("title");result.artist=set.optString("artist");result.mapper=set.optString("creator");}
                return result;
            }
        } catch(JSONException ignored) { }
        return null;
    }
    private static void saveMetadata(OsuReplay replay,Metadata result) throws Exception {
        if(result.title.isEmpty()||result.difficulty.isEmpty())return;
        File target=OsuLocalCache.file("metadata",replay.beatmapHash+".details.json");
        if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Cannot create metadata cache");
        JSONObject json=new JSONObject();json.put("checksum",replay.beatmapHash);json.put("title",result.title);
        json.put("artist",result.artist);json.put("version",result.difficulty);json.put("creator",result.mapper);
        File temp=File.createTempFile("details-",".tmp",target.getParentFile());
        try {java.nio.file.Files.write(temp.toPath(),json.toString().getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {temp.delete();}
    }
    static Metadata resolve(OsuReplay replay) throws Exception {
        return resolve(replay,null);
    }
    static Metadata resolve(OsuReplay replay,java.util.function.Consumer<Metadata> ready) throws Exception {
        Metadata cached=cachedMetadata(replay);if(cached!=null&&ready!=null)ready.accept(cached);
        synchronized (OsuLocalCache.LOCK) { return resolveCached(replay,ready); }
    }
    private static Metadata resolveCached(OsuReplay replay,java.util.function.Consumer<Metadata> ready) throws Exception {
        File root = new File(ApplicationLoader.getFilesDirFixed(), "osu-beatmaps");
        File map = find(root, replay.beatmapHash, 0, new int[]{0});
        if (map == null) {
            File downloaded = OsuLocalCache.file("maps", replay.beatmapHash + ".osu");
            if (downloaded.isFile() && replay.beatmapHash.equals(md5(downloaded))) map = downloaded;
        }
        if(map==null)for(String group:new String[]{"media","media-video"}) {
            File extracted=new File(OsuLocalCache.file(group,replay.beatmapHash),"map.osu");
            if(extracted.isFile()&&extracted.length()<=8*1024*1024&&replay.beatmapHash.equals(md5(extracted))){map=extracted;break;}
        }
        if (map == null) {
            File cache = new File(ApplicationLoader.getFilesDirFixed(), "osu-replay-maps");
            cache.mkdirs(); File cached = new File(cache, replay.beatmapHash + ".osu");
            if (cached.isFile() && replay.beatmapHash.equals(md5(cached))) map = cached;
            else {
                File metadata = OsuLocalCache.fetch("metadata", replay.beatmapHash + ".json", "https://osu.direct/api/v2/md5/" + replay.beatmapHash, 512 * 1024, null);
                JSONObject info;
                long id;
                try {
                    info = new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
                    if (!replay.beatmapHash.equalsIgnoreCase(info.optString("checksum"))) throw new IOException("Beatmap checksum mismatch");
                    id = info.getLong("id"); if (id <= 0) throw new IOException("Invalid beatmap id");
                } catch (Exception invalidMetadata) {
                    metadata.delete(); // Retry must be able to replace an invalid server response.
                    throw invalidMetadata;
                }
                Metadata available=new Metadata();available.difficulty=info.optString("version");
                JSONObject set=info.optJSONObject("beatmapset");
                if(set!=null){available.title=set.optString("title");available.artist=set.optString("artist");available.mapper=set.optString("creator");}
                if(ready!=null)ready.accept(available);
                if(!available.title.isEmpty()&&!available.difficulty.isEmpty())saveMetadata(replay,available);
                map = OsuLocalCache.fetch("maps", replay.beatmapHash + ".osu", "https://osu.ppy.sh/osu/" + id, 8 * 1024 * 1024, replay.beatmapHash);
            }
        }
        Metadata result=mapMetadata(map);
        // Publish textual information before native calculation; a failed PP
        // calculation must not hide a successfully parsed map.
        if(ready!=null)ready.accept(result);
        saveMetadata(replay,result);
        if (replay.performanceSupported) {
            double[] performance = OsuDifficulty.replayPerformance(map, replay.mods, replay.performanceCounts(), replay.lazer, replay.clockRate);
            if (performance != null) { result.stars = performance[0]; result.pp = performance[1]; result.maxCombo = (int) performance[2]; }
        }
        return result;
    }
    private static Metadata mapMetadata(File map) throws Exception {
        Metadata result=new Metadata();result.mapFile = map;
        // Only textual metadata is needed here. Broken/unsupported gameplay data
        // and native performance calculation cannot prevent showing this section.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(map), StandardCharsets.UTF_8))) {
            String line;boolean metadataSection=false;while ((line = reader.readLine()) != null) {
                line=line.trim();
                if(line.startsWith("[")){metadataSection=line.equals("[Metadata]");continue;}
                if(!metadataSection)continue;
                if(line.startsWith("Title:")&&!line.substring(6).trim().isEmpty())result.title=line.substring(6).trim();
                else if(line.startsWith("Artist:")&&!line.substring(7).trim().isEmpty())result.artist=line.substring(7).trim();
                else if(line.startsWith("TitleUnicode:")&&result.title.isEmpty())result.title=line.substring(13).trim();
                else if(line.startsWith("ArtistUnicode:")&&result.artist.isEmpty())result.artist=line.substring(14).trim();
                else if(line.startsWith("Version:"))result.difficulty=line.substring(8).trim();
                else if(line.startsWith("Creator:"))result.mapper=line.substring(8).trim();
            }
        }
        return result;
    }
    private static File find(File directory, String hash, int depth, int[] count) throws Exception {
        if (depth > 16 || count[0] > 4096) return null;
        File[] files = directory.listFiles(); if (files == null) return null;
        for (File file : files) {
            if (count[0]++ > 4096) break;
            if (file.isDirectory()) { File found = find(file, hash, depth + 1, count); if (found != null) return found; }
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".osu") && file.length() <= 8 * 1024 * 1024 && hash.equals(md5(file))) return file;
        }
        return null;
    }
    private static String md5(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(file)) { byte[] block = new byte[8192]; for (int n; (n = in.read(block)) >= 0;) hash.update(block, 0, n); }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte b : bytes) text.append(String.format(Locale.US, "%02x", b & 255)); return text.toString(); }
}
