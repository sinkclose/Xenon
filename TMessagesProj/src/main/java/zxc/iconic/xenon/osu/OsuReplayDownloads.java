package zxc.iconic.xenon.osu;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

/** Process-owned downloads. Sheets observe a job; dismissing a sheet never cancels it.
 * Only local session data is retained, never an Activity, audio player or bitmap.
 * Registry and observers are accessed on the main looper.
 */
final class OsuReplayDownloads {
    private static final DispatchQueue QUEUE=new DispatchQueue("osu-map-downloads");
    private static final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    interface Observer { void changed(Job job); }
    static final class Job {
        final File source;
        final boolean video;
        final ArrayList<Observer> observers=new ArrayList<>();
        boolean running,downloading;
        long bytes,total;
        OsuReplaySession session;
        Exception error;
        Job(File source,boolean video){this.source=source;this.video=video;}
        void observe(Observer observer){observers.add(observer);observer.changed(this);}
        void remove(Observer observer){observers.remove(observer);trim();}
        private void notifyObservers(){for(Observer observer:new ArrayList<>(observers))observer.changed(this);}
        void start(){
            if(running||session!=null)return;
            running=true;error=null;notifyObservers();
            QUEUE.postRunnable(()->{
                try{
                    OsuReplaySession prepared=OsuReplaySession.prepare(source,(active,count,size)->
                        AndroidUtilities.runOnUIThread(()->{downloading=active;bytes=count;total=size;notifyObservers();}),video);
                    AndroidUtilities.runOnUIThread(()->{running=false;downloading=false;session=prepared;notifyObservers();trim();});
                }catch(Exception failure){FileLog.e(failure);AndroidUtilities.runOnUIThread(()->{running=false;downloading=false;error=failure;notifyObservers();trim();});}
            });
        }
    }
    static Job get(File source,boolean video){
        String key=source.getAbsolutePath()+":"+source.length()+":"+source.lastModified()+":"+video;
        Job job=jobs.get(key);
        if(job==null){job=new Job(source,video);jobs.put(key,job);}
        return job;
    }
    private static void trim(){
        if(jobs.size()<=4)return;
        java.util.Iterator<Job> iterator=jobs.values().iterator();
        while(jobs.size()>4&&iterator.hasNext()){Job job=iterator.next();if(!job.running&&job.observers.isEmpty())iterator.remove();}
    }
}
