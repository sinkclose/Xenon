package zxc.iconic.xenon.osu;

import java.io.*;
import java.util.UUID;

/** Prepared on the sheet's worker queue; the viewer receives only ready local resources. */
public final class OsuReplaySession {
    public OsuBeatmap effects;
    public OsuReplay replay;
    public OsuReplayFrames frames;
    public OsuPlaybackMap map;
    public OsuReplayAssets assets;
    public OsuReplayJudgements judgements;
    public OsuReplaySoundEvents sounds;
    public OsuReplayHealth health;
    public OsuReplaySpinners spinners;
    public OsuReplayHud hud;
    public OsuReplayModEffects.Flashlight flashlight;
    public interface Progress { void update(boolean downloading,long bytes,long total); }
    private static OsuReplaySession ready;
    private static String readyToken;
    public static synchronized String offer(OsuReplaySession session){ready=session;return readyToken=UUID.randomUUID().toString();}
    public static synchronized OsuReplaySession take(String token){if(token==null||!token.equals(readyToken))return null;OsuReplaySession result=ready;ready=null;readyToken=null;return result;}
    public static OsuReplaySession autoplay(File mapFile,File audio)throws Exception{
        OsuReplaySession session=new OsuReplaySession();session.map=OsuPlaybackMap.read(mapFile);
        session.assets=OsuReplayAssets.localAutoplay(mapFile,audio);session.effects=readEffects(mapFile);
        session.frames=OsuAutoplay.generate(session.map);session.replay=new OsuReplay();
        OsuReplay r=session.replay;r.autoplay=true;r.lazer=true;r.version=30000019;r.mods=2048;r.modsLabel="AT";r.player="Autoplay";r.title=session.map.title;r.artist=session.map.artist;r.difficulty=session.map.difficulty;r.mapper=session.map.mapper;r.playedAt=System.currentTimeMillis();r.performanceSupported=false;r.rank="SS";r.perfect=true;
        session.judgements=OsuReplayJudgements.calculate(session.map,session.frames,r);
        for(OsuReplayJudgements.Result result:session.judgements.results){if(result.value==300)r.great++;else if(result.value==100)r.ok++;else if(result.value==50)r.meh++;else r.miss++;}
        r.statistics.put("great",r.great);r.statistics.put("ok",r.ok);r.statistics.put("meh",r.meh);r.statistics.put("miss",r.miss);
        if(r.miss>0){r.rank="B";r.perfect=false;}else if(r.ok+r.meh>0){r.rank="A";r.perfect=false;}
        session.health=new OsuReplayHealth(session.map,session.frames,r,session.judgements);
        session.spinners=new OsuReplaySpinners(session.map,session.frames,r);
        session.hud=new OsuReplayHud(session.map,session.frames,r,session.judgements,session.spinners);
        r.score=session.hud.score(session.map.duration()+1);r.combo=session.hud.combo(session.map.duration()+1);r.maxCombo=r.combo;
        session.sounds=new OsuReplaySoundEvents(session.map,session.frames,r,session.judgements,session.hud,session.spinners);
        return session;
    }
    private static OsuBeatmap readEffects(File file)throws IOException{try(Reader reader=new InputStreamReader(new FileInputStream(file),java.nio.charset.StandardCharsets.UTF_8)){return OsuBeatmap.read(reader);}}
    public static OsuReplaySession prepare(File source,Progress progress) throws Exception{return prepare(source,progress,true);}
    public static OsuReplaySession prepare(File source,Progress progress,boolean video) throws Exception{
        OsuReplaySession session=new OsuReplaySession();
        if(progress!=null)progress.update(false,0,0);
        OsuReplay header=OsuReplay.read(source,true);if(header.mode!=0)throw new IOException("Only osu!standard is supported");
        session.replay=OsuReplayLoader.read(source);
        session.frames=OsuReplayFrames.parse(OsuDifficulty.replayFrames(header.compressedFrames));
        OsuReplayLoader.Metadata metadata=OsuReplayLoader.resolve(session.replay);
        session.assets=OsuReplayAssets.prepare(metadata.mapFile,session.replay.beatmapHash,(bytes,total)->{if(progress!=null)progress.update(true,bytes,total);},video);
        if(progress!=null)progress.update(false,0,0);
        session.map=OsuPlaybackMap.read(session.assets.map);session.effects=readEffects(session.assets.map);
        session.map.applyOverrides(session.replay);
        session.frames=OsuReplayModEffects.autopilot(session.map,session.frames,session.replay);
        session.replay.title=metadata.title;session.replay.artist=metadata.artist;session.replay.difficulty=metadata.difficulty;session.replay.mapper=metadata.mapper;session.replay.stars=metadata.stars;session.replay.pp=metadata.pp;session.replay.maxCombo=metadata.maxCombo;
        session.judgements=OsuReplayJudgements.calculate(session.map,session.frames,session.replay);
        session.health=new OsuReplayHealth(session.map,session.frames,session.replay,session.judgements);
        session.spinners=new OsuReplaySpinners(session.map,session.frames,session.replay);
        session.hud=new OsuReplayHud(session.map,session.frames,session.replay,session.judgements,session.spinners);
        if((session.replay.mods&1024)!=0)session.flashlight=new OsuReplayModEffects.Flashlight(session.map,session.frames,session.replay,session.hud);
        session.sounds=new OsuReplaySoundEvents(session.map,session.frames,session.replay,session.judgements,session.hud,session.spinners);
        return session;
    }
}
