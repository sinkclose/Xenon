package zxc.iconic.xenon.osu;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Public mirror search. No Telegram message, account, or replay is transmitted. */
public final class OsuBeatmapSearch {
    public static final int LIMIT=10, MAX_QUERY=160;
    public static final long DEBOUNCE_MS=700, INTERVAL_MS=2500, TTL_MS=24*60*60*1000L;
    private static final long EMPTY_TTL_MS=5*60*1000;
    private static final int CACHE_LIMIT=64, MAX_BYTES=1024*1024;
    private OsuBeatmapSearch() { }

    /** null means ordinary Telegram input; empty means a reserved but incomplete query. */
    public static String query(CharSequence text,int cursor) {
        if(text==null||text.length()<5||!text.toString().regionMatches(true,0,"@osu ",0,5))return null;
        if(cursor!=text.length())return "";
        String value=normalize(text.subSequence(5,text.length()).toString());
        return value.codePointCount(0,value.length())>=3&&value.length()<=MAX_QUERY?value:"";
    }
    public static String normalize(String value){return value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    public static final class Result {
        public final long id;
        public final String title,artist,creator;
        public final double minStars,maxStars;
        public final int difficulties;
        Result(long id,String title,String artist,String creator,double min,double max,int count){
            this.id=id;this.title=title;this.artist=artist;this.creator=creator;minStars=min;maxStars=max;difficulties=count;
        }
        public String url(){return "https://osu.ppy.sh/beatmapsets/"+id;}
        public String thumbnailUrl(){return "https://assets.ppy.sh/beatmaps/"+id+"/covers/list@2x.jpg";}
        public String heading(){return artist.isEmpty()?title:artist+" — "+title;}
        public String stars(){return Double.isFinite(minStars)?String.format(Locale.US,"★ %.2f–%.2f",minStars,maxStars):"";}
    }
    public static List<Result> parse(String body)throws IOException {
        try {
            JSONArray array=new JSONArray(body);ArrayList<Result> results=new ArrayList<>();HashSet<Long> ids=new HashSet<>();
            for(int i=0;i<array.length()&&results.size()<LIMIT;i++) {
                JSONObject set=array.optJSONObject(i);if(set==null)continue;
                long id=set.optLong("id");String title=label(set.optString("title"));
                if(id<=0||title.isEmpty()||!ids.add(id))continue;
                double min=Double.POSITIVE_INFINITY,max=0;int count=0;
                JSONArray maps=set.optJSONArray("beatmaps");
                if(maps!=null)for(int j=0;j<Math.min(maps.length(),256);j++) {
                    JSONObject map=maps.optJSONObject(j);if(map==null||map.optBoolean("convert"))continue;
                    count++;double stars=map.optDouble("difficulty_rating",Double.NaN);
                    if(Double.isFinite(stars)&&stars>=0&&stars<=100){min=Math.min(min,stars);max=Math.max(max,stars);}
                }
                results.add(new Result(id,title,label(set.optString("artist")),label(set.optString("creator")),min,max,count));
            }
            return Collections.unmodifiableList(results);
        }catch(JSONException error){throw new IOException("Invalid beatmap search response",error);}
    }
    private static String label(String text){String clean=text.replaceAll("[\\p{Cntrl}]"," ").trim();return clean.substring(0,Math.min(256,clean.length()));}

    private static final ExecutorService CANCELLATIONS=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"osu-search-cancel");t.setDaemon(true);return t;});
    public static final class Cancellation {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private final CountDownLatch wake=new CountDownLatch(1);
        public void cancel(){cancelled=true;wake.countDown();HttpURLConnection current=connection;if(current!=null)CANCELLATIONS.execute(current::disconnect);}
        public boolean isCancelled(){return cancelled;}
        void check()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Search cancelled");}
        void pause(long ms)throws IOException{check();try{wake.await(ms,TimeUnit.MILLISECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException();}check();}
    }
    public interface Clock { long now();default long uptime(){return now();}void pause(long delay,Cancellation cancellation)throws IOException; }
    public interface Transport { Response fetch(String query,Cancellation cancellation)throws IOException; }
    public static final class Response {
        final String body;final boolean cacheable;
        public Response(String body,boolean cacheable){this.body=body;this.cacheable=cacheable;}
    }
    public static final class Failure extends IOException {
        final long retryAfter;
        public Failure(String message,long retryAfter){super(message);this.retryAfter=retryAfter;}
    }
    static long retryAfter(String header,long now) {
        if(header==null)return 0;
        try{return Math.min(86400,Math.max(0,Long.parseLong(header.trim())))*1000;}catch(NumberFormatException ignored){}
        try{java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);format.setLenient(false);
            return Math.min(86400000,Math.max(0,format.parse(header).getTime()-now));
        }catch(java.text.ParseException ignored){return 0;}
    }
    public static final class HttpTransport implements Transport {
        @Override public Response fetch(String query,Cancellation cancellation)throws IOException {
            HttpURLConnection connection=(HttpURLConnection)new URL("https://mirror.hinamizawa.ai/api/v2/search?q="
                    +URLEncoder.encode(query,"UTF-8")+"&amount="+LIMIT+"&offset=0").openConnection();
            cancellation.connection=connection;
            try {
                cancellation.check();connection.setConnectTimeout(6000);connection.setReadTimeout(6000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent","Xenon-osu-search/1.0");connection.setRequestProperty("Accept","application/json");
                int status=connection.getResponseCode();
                if(status!=200)throw new Failure("Search HTTP "+status,retryAfter(connection.getHeaderField("Retry-After"),System.currentTimeMillis()));
                if(connection.getContentLengthLong()>MAX_BYTES)throw new IOException("Search response too large");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=connection.getInputStream()) {byte[] block=new byte[8192];for(int n;(n=in.read(block))!=-1;){cancellation.check();if(bytes.size()+n>MAX_BYTES)throw new IOException("Search response too large");bytes.write(block,0,n);}}
                String policy=connection.getHeaderField("Cache-Control");
                return new Response(new String(bytes.toByteArray(),StandardCharsets.UTF_8),policy==null||!policy.toLowerCase(Locale.ROOT).contains("no-store"));
            } finally {cancellation.connection=null;connection.disconnect();}
        }
    }
    /** One process-wide instance serialises network requests across chats/accounts. */
    public static final class Repository {
        private final File directory;
        private final Transport transport;
        private final Clock clock;
        private final Object network=new Object();
        private long nextRequest;
        private int failures;
        private final LinkedHashMap<String,Entry> memory=new LinkedHashMap<>(16,.75f,true);
        private static final class Entry {final String body;final long time,ttl;Entry(String body,long time,long ttl){this.body=body;this.time=time;this.ttl=ttl;}}
        public Repository(File directory){this(directory,new HttpTransport(),new Clock(){public long now(){return System.currentTimeMillis();}public long uptime(){return System.nanoTime()/1000000;}public void pause(long delay,Cancellation cancellation)throws IOException{cancellation.pause(delay);}});}
        public Repository(File directory,Transport transport,Clock clock){this.directory=directory;this.transport=transport;this.clock=clock;}
        public List<Result> search(String raw,Cancellation cancellation)throws IOException {
            String query=normalize(raw);if(query.codePointCount(0,query.length())<3||query.length()>MAX_QUERY)throw new IOException("Invalid search query");
            cancellation.check();Entry cached=read(query);if(cached!=null)return parse(cached.body);
            synchronized(network) {
                cancellation.check();cached=read(query);if(cached!=null)return parse(cached.body);
                while(nextRequest>clock.uptime())clock.pause(Math.min(1000,nextRequest-clock.uptime()),cancellation);
                cancellation.check();nextRequest=clock.uptime()+INTERVAL_MS;
                try {
                    Response response=transport.fetch(query,cancellation);cancellation.check();List<Result> result=parse(response.body);
                    if(!response.cacheable&&result.isEmpty())throw new Failure("Search temporarily unavailable",30000);
                    failures=0;
                    if(response.cacheable)write(query,new Entry(response.body,clock.now(),result.isEmpty()?EMPTY_TTL_MS:TTL_MS));
                    return result;
                }catch(IOException error){
                    if(!cancellation.isCancelled()) {long backoff=Math.min(10*60*1000L,30000L<<Math.min(failures++,4));
                        if(error instanceof Failure)backoff=Math.max(backoff,((Failure)error).retryAfter);
                        nextRequest=Math.max(nextRequest,clock.uptime()+backoff);}
                    throw error;
                }
            }
        }
        private boolean fresh(Entry entry){long age=clock.now()-entry.time;return age>=0&&age<entry.ttl;}
        private synchronized Entry read(String query) {
            Entry entry=memory.get(query);if(entry!=null&&fresh(entry)&&new File(directory,key(query)).isFile())return entry;
            memory.remove(query);File file=new File(directory,key(query));if(!file.isFile()||file.length()>MAX_BYTES+4096)return null;
            try {
                JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                if(!query.equals(json.getString("query")))return null;
                String body=json.getString("body");List<Result> result=parse(body);
                entry=new Entry(body,json.getLong("time"),result.isEmpty()?EMPTY_TTL_MS:TTL_MS);
                if(fresh(entry)){remember(query,entry);return entry;}
            }catch(Exception invalid){file.delete();}
            return null;
        }
        private void remember(String query,Entry entry){memory.put(query,entry);while(memory.size()>CACHE_LIMIT)memory.remove(memory.keySet().iterator().next());}
        private synchronized void write(String query,Entry entry) {
            File temp=null;
            try {
                if(!directory.isDirectory()&&!directory.mkdirs())return;
                JSONObject json=new JSONObject();json.put("query",query);json.put("time",entry.time);json.put("body",entry.body);
                temp=File.createTempFile("search-",".tmp",directory);Files.write(temp.toPath(),json.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temp.toPath(),new File(directory,key(query)).toPath(),StandardCopyOption.REPLACE_EXISTING);remember(query,entry);
                File[] files=directory.listFiles((dir,name)->name.endsWith(".json"));
                if(files!=null&&files.length>CACHE_LIMIT){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-CACHE_LIMIT;i++)files[i].delete();}
            }catch(Exception ignored){}finally{if(temp!=null)temp.delete();}
        }
        private static String key(String query) {
            try{StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex+".json";}
            catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
    }
}
