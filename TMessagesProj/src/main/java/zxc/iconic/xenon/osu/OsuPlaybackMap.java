package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Basic standard playfield geometry; independent of Android and rendering state. */
public final class OsuPlaybackMap {
    public final ArrayList<Hit> objects = new ArrayList<>();
    public final ArrayList<double[]> breaks = new ArrayList<>();
    public final ArrayList<Integer> comboColours = new ArrayList<>();
    public String title = "", artist = "", difficulty = "", mapper = "", audio = "", background = "";
    public String video="";
    public double videoOffset;
    public long setId;
    public double ar = 5, cs = 5, od = 5, hp = 5;
    public int mode;
    private boolean adjustedAR,adjustedCS,adjustedOD,adjustedHP;
    public static final class Hit {
        public double x, y, time, end, length, tickInterval = 500;
        public int type, number, combo, repeats = 1;
        public int hitSound, normalSet=1, additionSet=1, volume=100;
        public int[] edgeSounds;
        public double[] px, py, distances;
        public void position(double progress, double[] out) {
            if (px == null || px.length == 0) { out[0] = x; out[1] = y; return; }
            double target = Math.max(0, Math.min(1, progress)) * distances[distances.length - 1];
            int i = Arrays.binarySearch(distances, target); if (i < 0) i = -i - 1;
            i = Math.max(1, Math.min(distances.length - 1, i));
            double f = (target - distances[i-1]) / Math.max(.0001, distances[i] - distances[i-1]);
            out[0] = px[i-1] + (px[i] - px[i-1]) * f; out[1] = py[i-1] + (py[i] - py[i-1]) * f;
        }
    }
    public static OsuPlaybackMap read(File file) throws IOException {
        if (file.length() > 8 * 1024 * 1024) throw new IOException("Map too large");
        OsuPlaybackMap map = new OsuPlaybackMap();
        ArrayList<double[]> timing = new ArrayList<>(); ArrayList<String> objects = new ArrayList<>();
        double multiplier = 1.4, tickRate=1; boolean hasAr = false; double od = 5; int defaultSet=1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String section = "", line; int chars = 0;
            while ((line = reader.readLine()) != null) {
                chars += line.length(); if (line.length() > 65536 || chars > 8 * 1024 * 1024) throw new IOException("Map text too large");
                line = line.replace("\ufeff", "").trim(); if (line.isEmpty() || line.startsWith("//")) continue;
                if (line.startsWith("[")) { section = line; continue; }
                int colon = line.indexOf(':');
                if (section.equals("[HitObjects]")) { if (objects.size() >= 50000) throw new IOException("Too many hit objects"); objects.add(line); }
                else if (section.equals("[TimingPoints]")) {
                    String[] f = line.split(","); if (f.length >= 2) timing.add(new double[]{number(f[0]), number(f[1]), f.length < 7 || f[6].trim().equals("1") ? 1 : 0,f.length>3?number(f[3]):0,f.length>5?number(f[5]):100});
                } else if(section.equals("[Events]")&&(line.startsWith("2,")||line.startsWith("Break,"))){
                    String[] f=line.split(",");if(f.length>=3){double start=number(f[1]),end=number(f[2]);if(start>=0&&end>start&&end<=86400000)map.breaks.add(new double[]{start,end});}
                } else if(section.equals("[Events]")){
                    OsuBackgroundEvent event=OsuBackgroundEvent.parse(line);if(event!=null){if(event.video){map.video=event.file;map.videoOffset=event.offset;}else map.background=event.file;}
                } else if (colon >= 0) {
                    String key = line.substring(0, colon).trim(), value = line.substring(colon+1).trim();
                    if(section.equals("[Colours]")&&key.matches("Combo[1-8]")){
                        String[] rgb=value.split(",");if(rgb.length>=3){int colour=0xff000000;for(int component=0;component<3;component++)colour|=((int)Math.max(0,Math.min(255,number(rgb[component]))))<<(16-component*8);map.comboColours.add(colour);}continue;
                    }
                    switch (key) {
                        case "Title": if (map.title.isEmpty()) map.title=value; break;
                        case "Artist": if(map.artist.isEmpty())map.artist=value;break;
                        case "ArtistUnicode": if(!value.isEmpty())map.artist=value;break;
                        case "Version": map.difficulty=value;break;
                        case "Creator": map.mapper=value;break;
                        case "TitleUnicode": if (!value.isEmpty()) map.title=value; break;
                        case "AudioFilename": map.audio=value; break;
                        case "BeatmapSetID": map.setId=(long)number(value); break;
                        case "Mode": if(section.equals("[General]")){double mode=number(value);if(mode!=Math.rint(mode)||mode<0||mode>3)throw new IOException("Invalid beatmap mode");map.mode=(int)mode;} break;
                        case "ApproachRate": map.ar=number(value); hasAr=true; break;
                        case "OverallDifficulty": od=number(value); break;
                        case "CircleSize": map.cs=number(value); break;
                        case "HPDrainRate": map.hp=number(value); break;
                        case "SliderMultiplier": multiplier=number(value); break;
                        case "SliderTickRate": tickRate=number(value); break;
                        case "SampleSet": defaultSet=value.equalsIgnoreCase("Soft")?2:value.equalsIgnoreCase("Drum")?3:1; break;
                    }
                }
            }
        }
        if (!hasAr) map.ar=od;
        map.od=od;
        if (multiplier <= 0 || multiplier > 100) throw new IOException("Invalid slider multiplier");
        timing.sort(Comparator.comparingDouble(p -> p[0]));
        int color=-1, comboNumber=0, point=0, totalPoints=0, sampleSet=defaultSet, volume=100; double beat=500, velocity=1;
        try {
            for (String line : objects) {
                String[] f=line.split(","); if (f.length < 5) throw new IOException("Invalid hit object");
                Hit hit=new Hit(); hit.x=number(f[0]);hit.y=number(f[1]);hit.time=number(f[2]);hit.end=hit.time;
                if (Math.abs(hit.x)>4096||Math.abs(hit.y)>4096||hit.time<0||hit.time>86400000) throw new IOException("Invalid hit object position");
                int flags=(int)number(f[3]); hit.type=(flags&1)!=0?1:(flags&2)!=0?2:(flags&8)!=0?8:0;
                if (hit.type==0) continue;
                if (color<0 || (flags&4)!=0) { color+=(1+((flags>>4)&7));comboNumber=0; }
                hit.combo=color;hit.number=++comboNumber;
                while (point<timing.size() && timing.get(point)[0]<=hit.time) {
                    double[] t=timing.get(point++);
                    if(t[2]==1&&t[1]>0) {beat=t[1];velocity=1;} else if(t[2]==0&&t[1]<0) velocity=Math.max(.1,Math.min(10,-100/t[1]));
                    sampleSet=t[3]>0?(int)t[3]:defaultSet;volume=Math.max(0,Math.min(100,(int)t[4]));
                }
                hit.hitSound=(int)number(f[4]);hit.normalSet=sampleSet;hit.additionSet=sampleSet;hit.volume=volume;
                String extra=f[f.length-1];if(extra.indexOf(':')>=0&&extra.indexOf('|')<0){String[] parts=extra.split(":",-1);if(parts.length>=4){int normal=(int)number(parts[0]),addition=(int)number(parts[1]),gain=(int)number(parts[3]);if(normal>0)hit.normalSet=Math.min(3,normal);hit.additionSet=addition>0?Math.min(3,addition):hit.normalSet;if(gain>0)hit.volume=Math.min(100,gain);}}
                if (hit.type==8) { if(f.length<6)throw new IOException("Invalid spinner"); hit.end=number(f[5]); }
                if (hit.type==2) {
                    if(f.length<8)throw new IOException("Invalid slider");
                    hit.repeats=(int)number(f[6]);hit.length=number(f[7]);
                    if(hit.repeats<1||hit.repeats>10000||hit.length>100000000)throw new IOException("Invalid slider length");
                    if(f.length>8&&!f[8].isEmpty()){String[] edges=f[8].split("\\|");hit.edgeSounds=new int[Math.min(edges.length,hit.repeats+1)];for(int edge=0;edge<hit.edgeSounds.length;edge++)hit.edgeSounds[edge]=(int)number(edges[edge]);}
                    hit.tickInterval=beat/Math.max(.1,Math.min(16,tickRate));
                    curve(hit,f[5]);
                    // Lazer's legacy converter removes repeats from zero-distance Aspire sliders.
                    if(hit.length<.0001)hit.repeats=1;
                    hit.end=hit.time+beat*hit.length*hit.repeats/(multiplier*100*velocity);
                    totalPoints+=hit.px.length;
                    if(totalPoints>500000)throw new IOException("Too many slider points");
                }
                if(hit.end<hit.time||hit.end>86400000)throw new IOException("Invalid object duration");
                map.objects.add(hit);
            }
        } catch (IndexOutOfBoundsException error) { throw new IOException("Invalid beatmap",error); }
        map.objects.sort(Comparator.comparingDouble(h -> h.time));
        if(map.objects.isEmpty())throw new IOException("Empty beatmap");
        return map;
    }
    private static double number(String value) throws IOException {
        try { double n=Double.parseDouble(value.trim()); if(!Double.isFinite(n))throw new IOException("Non-finite map number"); return n; }
        catch(NumberFormatException e){throw new IOException("Invalid map number",e);}
    }
    private static void curve(Hit hit,String data) throws IOException {
        String[] parts=data.split("\\|"); if(parts.length>16384)throw new IOException("Too many slider controls");
        ArrayList<double[]> controls=new ArrayList<>(), path=new ArrayList<>(); controls.add(new double[]{hit.x,hit.y});
        for(int i=1;i<parts.length;i++){String[] xy=parts[i].split(":");if(xy.length!=2)throw new IOException("Invalid slider point");double x=number(xy[0]),y=number(xy[1]);if(Math.abs(x)>100000000||Math.abs(y)>100000000)throw new IOException("Invalid slider control");controls.add(new double[]{x,y});}
        if(parts[0].equals("P")&&controls.size()==3&&arc(controls,path)) { /* circular arc */ }
        else if(parts[0].equals("L")) path.addAll(controls);
        else if(parts[0].equals("C")) {
            for(int i=0;i<controls.size()-1;i++) {
                double[] a=controls.get(Math.max(0,i-1)),b=controls.get(i),c=controls.get(i+1),d=controls.get(Math.min(controls.size()-1,i+2));
                for(int j=0;j<24;j++){double t=j/24.0,t2=t*t,t3=t2*t;double[] p=new double[2];for(int k=0;k<2;k++)p[k]=.5*((2*b[k])+(-a[k]+c[k])*t+(2*a[k]-5*b[k]+4*c[k]-d[k])*t2+(-a[k]+3*b[k]-3*c[k]+d[k])*t3);path.add(p);}
            }path.add(controls.get(controls.size()-1));
        } else {
            ArrayList<double[]> segment=new ArrayList<>();
            for(int i=0;i<controls.size();i++) {
                double[] p=controls.get(i);segment.add(p);
                if(i==controls.size()-1||(i+1<controls.size()&&Arrays.equals(p,controls.get(i+1)))) {bezier(segment,path);segment.clear();}
            }
        }
        if(path.isEmpty())path.add(controls.get(0));
        // Legacy negative/zero expected distance means use the calculated path length.
        if(hit.length<=0){hit.length=0;for(int i=1;i<path.size();i++)hit.length+=Math.hypot(path.get(i)[0]-path.get(i-1)[0],path.get(i)[1]-path.get(i-1)[1]);}
        ArrayList<double[]> trimmed=new ArrayList<>();trimmed.add(path.get(0));double length=0;
        for(int i=1;i<path.size();i++) {
            double[] a=trimmed.get(trimmed.size()-1),b=path.get(i);double distance=Math.hypot(b[0]-a[0],b[1]-a[1]);
            if(distance<.0001)continue;
            if(length+distance>=hit.length){double mix=(hit.length-length)/distance;trimmed.add(new double[]{a[0]+(b[0]-a[0])*mix,a[1]+(b[1]-a[1])*mix});length=hit.length;break;}
            trimmed.add(b);length+=distance;
        }
        if(trimmed.size()==1)trimmed.add(trimmed.get(0).clone());
        if(length<hit.length){double[] b=trimmed.get(trimmed.size()-1),a=trimmed.get(trimmed.size()-2);double d=Math.hypot(b[0]-a[0],b[1]-a[1]);if(d>0)trimmed.add(new double[]{b[0]+(b[0]-a[0])*(hit.length-length)/d,b[1]+(b[1]-a[1])*(hit.length-length)/d});}
        hit.px=new double[trimmed.size()];hit.py=new double[trimmed.size()];hit.distances=new double[trimmed.size()];
        for(int i=0;i<trimmed.size();i++){hit.px[i]=trimmed.get(i)[0];hit.py[i]=trimmed.get(i)[1];if(i>0)hit.distances[i]=hit.distances[i-1]+Math.hypot(hit.px[i]-hit.px[i-1],hit.py[i]-hit.py[i-1]);}
    }
    private static void bezier(ArrayList<double[]> controls,ArrayList<double[]> path) throws IOException {
        if(controls.size()>256)throw new IOException("Too many controls in a Bezier segment");
        if(controls.isEmpty())return;
        int steps=Math.max(16,Math.min(128,controls.size()*12));
        for(int j=0;j<=steps;j++){double t=j/(double)steps;double[][] work=new double[controls.size()][2];for(int i=0;i<work.length;i++)work[i]=controls.get(i).clone();for(int n=work.length-1;n>0;n--)for(int i=0;i<n;i++)for(int k=0;k<2;k++)work[i][k]+=(work[i+1][k]-work[i][k])*t;path.add(work[0]);}
    }
    private static boolean arc(ArrayList<double[]> c,ArrayList<double[]> path) {
        double ax=c.get(0)[0],ay=c.get(0)[1],bx=c.get(1)[0],by=c.get(1)[1],cx=c.get(2)[0],cy=c.get(2)[1];
        double d=2*(ax*(by-cy)+bx*(cy-ay)+cx*(ay-by));if(Math.abs(d)<.001)return false;
        double ux=((ax*ax+ay*ay)*(by-cy)+(bx*bx+by*by)*(cy-ay)+(cx*cx+cy*cy)*(ay-by))/d;
        double uy=((ax*ax+ay*ay)*(cx-bx)+(bx*bx+by*by)*(ax-cx)+(cx*cx+cy*cy)*(bx-ax))/d;
        double start=Math.atan2(ay-uy,ax-ux),middle=(Math.atan2(by-uy,bx-ux)-start+Math.PI*2)%(Math.PI*2),sweep=(Math.atan2(cy-uy,cx-ux)-start+Math.PI*2)%(Math.PI*2);
        if(middle>sweep)sweep-=Math.PI*2;double radius=Math.hypot(ax-ux,ay-uy);
        for(int i=0;i<=128;i++){double a=start+sweep*i/128;path.add(new double[]{ux+Math.cos(a)*radius,uy+Math.sin(a)*radius});}return true;
    }
    public double approachRate(long mods){double value=ar;if(adjustedAR)return value;if((mods&2)!=0)value*=.5;if((mods&16)!=0)value=Math.min(10,value*1.4);return value;}
    public double circleSize(long mods){double value=cs;if(adjustedCS)return value;if((mods&2)!=0)value*=.5;if((mods&16)!=0)value=Math.min(10,value*1.3);return value;}
    public double overallDifficulty(long mods){double value=od;if(adjustedOD)return value;if((mods&2)!=0)value*=.5;if((mods&16)!=0)value=Math.min(10,value*1.4);return value;}
    public double preempt(long mods){double value=approachRate(mods);return value<5?1800-120*value:1200-150*(value-5);}
    public double drain(long mods){double value=hp;if(adjustedHP)return value;if((mods&2)!=0)value*=.5;if((mods&16)!=0)value=Math.min(10,value*1.4);return value;}
    public void applyOverrides(OsuReplay replay){
        if(Double.isFinite(replay.overrideCS)){cs=Math.max(0,Math.min(20,replay.overrideCS));adjustedCS=true;}
        if(Double.isFinite(replay.overrideAR)){ar=Math.max(0,Math.min(12,replay.overrideAR));adjustedAR=true;}
        if(Double.isFinite(replay.overrideOD)){od=Math.max(0,Math.min(11,replay.overrideOD));adjustedOD=true;}
        if(Double.isFinite(replay.overrideHP)){hp=Math.max(0,Math.min(10,replay.overrideHP));adjustedHP=true;}
        if((replay.mods&(1L<<30))!=0){boolean horizontal=replay.mirrorReflection!=1,vertical=replay.mirrorReflection!=0;
            for(Hit hit:objects){if(hit.type==8)continue;if(horizontal)hit.x=512-hit.x;if(vertical)hit.y=384-hit.y;
                if(hit.px!=null)for(int i=0;i<hit.px.length;i++){if(horizontal)hit.px[i]=512-hit.px[i];if(vertical)hit.py[i]=384-hit.py[i];}}
        }
    }
    public double radius(long mods){return Math.max(5,54.4-4.48*circleSize(mods));}
    public double duration(){double end=0;for(Hit hit:objects)end=Math.max(end,hit.end);return end;}
    public double skipSection(double time,long mods){
        if(!objects.isEmpty()){
            double target=Math.max(0,objects.get(0).time-Math.max(1000,preempt(mods)));
            if(time>=0&&target-time>2000)return target;
        }
        return skipBreak(time);
    }
    public double skipBreak(double time){for(double[] interval:breaks)if(time>=interval[0]&&time<interval[1]-2000)return interval[1]-1000;return -1;}
}
