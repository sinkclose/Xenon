package zxc.iconic.xenon.osu;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

/** Decode selected WAV/OGG/MP3 samples on the preparation worker, never on the audio thread. */
final class OsuReplaySamples {
    static Map<String,OsuWave> load(Context context,String key){
        HashMap<String,OsuWave> samples=new HashMap<>();File directory=OsuSkinStore.directory(key);
        ArrayList<String> names=new ArrayList<>();
        for(String bank:new String[]{"normal","soft","drum"})for(String sound:new String[]{"hitnormal","hitwhistle","hitfinish","hitclap","slidertick"})names.add(bank+"-"+sound);
        Collections.addAll(names,"combobreak","spinnerbonus","spinnerbonus-max");
        long used=0;
        for(String name:names){
            OsuWave wave=null;
            if(directory!=null)for(String alias:name.equals("spinnerbonus-max")?new String[]{name,"spinnerbonus"}:new String[]{name}){
                for(String ext:new String[]{"wav","ogg","mp3"}){
                    File file=new File(directory,alias+"."+ext);if(!file.isFile())continue;
                    try{wave=decode(file);break;}catch(Exception error){FileLog.e(error);}
                }
                if(wave!=null)break;
            }
            if(wave!=null&&used+wave.durationMs()*wave.sampleRate*wave.channels/500>16L*1024*1024)wave=null;
            if(wave==null)try(InputStream in=context.getAssets().open((OsuSkinStore.isArgonPro(key)?"osu/argon-pro/sounds/":"osu/sounds/")+name+".wav")){wave=OsuWave.read(in);}catch(Exception error){FileLog.e(error);}
            if(wave!=null){samples.put(name,wave);used+=(long)(wave.durationMs()*wave.sampleRate*wave.channels/500);}
        }
        int[] ids={R.raw.osu_fountain_start,R.raw.osu_fountain_loop};String[] fountains={"fountain-shoot","fountain-loop"};
        for(int i=0;i<ids.length;i++)try(InputStream in=OsuSkinStore.isArgonPro(key)?context.getAssets().open("osu/argon-pro/sounds/"+fountains[i]+".wav"):context.getResources().openRawResource(ids[i])){samples.put(fountains[i],OsuWave.read(in));}catch(Exception error){FileLog.e(error);}
        return Collections.unmodifiableMap(samples);
    }
    private static OsuWave decode(File file)throws Exception{
        try(InputStream in=new FileInputStream(file)){return OsuWave.read(in);}catch(IOException notPcmWave){/* Compressed skins use MediaCodec. */}
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(file.getAbsolutePath());MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){format=f;extractor.selectTrack(i);break;}}
            if(format==null)throw new IOException("No sample audio");
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            int channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),encoding=AudioFormat.ENCODING_PCM_16BIT;
            ByteArrayOutputStream pcm=new ByteArrayOutputStream();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputEnded=false,outputEnded=false;
            long deadline=android.os.SystemClock.elapsedRealtime()+10000;
            while(!outputEnded){
                if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("Sample decode timed out");
                if(!inputEnded){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);int size=extractor.readSampleData(buffer,0);if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;}else{codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}}}
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;if(channels<1||channels>2||rate<8000||rate>192000||(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("Unsupported sample PCM");}
                else if(index>=0){
                    ByteBuffer data=codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);data.position(info.offset);data.limit(info.offset+info.size);
                    while(data.remaining()>=(encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2)){
                        int sample=encoding==AudioFormat.ENCODING_PCM_FLOAT?Math.max(-32768,Math.min(32767,Math.round(data.getFloat()*32768))):data.getShort();
                        pcm.write(sample&255);pcm.write((sample>>>8)&255);if(pcm.size()>8*1024*1024)throw new IOException("Decoded sample too large");
                    }
                    outputEnded=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);
                }
            }
            byte[] data=pcm.toByteArray();short[] result=new short[data.length/2];ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result);return new OsuWave(rate,channels,result);
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
    }
}
