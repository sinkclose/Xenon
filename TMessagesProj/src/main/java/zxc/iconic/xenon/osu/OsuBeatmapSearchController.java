package zxc.iconic.xenon.osu;

import org.telegram.messenger.AndroidUtilities;
import java.util.List;
import java.util.concurrent.*;

/** Composer lifecycle and debounce; destroyed/changed queries can never update a new draft. */
public final class OsuBeatmapSearchController {
    public interface Listener {void complete(List<OsuBeatmapSearch.Result> results,boolean error);}
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"osu-beatmap-search");t.setDaemon(true);return t;});
    private static OsuBeatmapSearch.Repository repository;
    private static synchronized OsuBeatmapSearch.Repository repository(){if(repository==null)repository=new OsuBeatmapSearch.Repository(OsuLocalCache.file("search","results"));return repository;}
    private Runnable delayed;
    private Future<?> work;
    private OsuBeatmapSearch.Cancellation cancellation;
    private final OsuBeatmapSearch.Repository suppliedRepository;
    private final ExecutorService executor;
    private int generation;
    public OsuBeatmapSearchController(){this(null,WORKER);}
    OsuBeatmapSearchController(OsuBeatmapSearch.Repository repository,ExecutorService executor){suppliedRepository=repository;this.executor=executor;}
    public void search(String query,Listener listener) {
        cancel();int token=generation;
        OsuBeatmapSearch.Cancellation request=cancellation=new OsuBeatmapSearch.Cancellation();
        delayed=()->{
            delayed=null;
            work=executor.submit(()->{
                List<OsuBeatmapSearch.Result> result=null;boolean error=false;
                try{result=(suppliedRepository==null?repository():suppliedRepository).search(query,request);}catch(java.io.IOException failure){error=true;}
                List<OsuBeatmapSearch.Result> delivered=result;boolean failed=error;
                AndroidUtilities.runOnUIThread(()->{if(token==generation&&!request.isCancelled())listener.complete(delivered,failed);});
            });
        };
        AndroidUtilities.runOnUIThread(delayed,OsuBeatmapSearch.DEBOUNCE_MS);
    }
    public void cancel() {
        generation++;
        if(delayed!=null){AndroidUtilities.cancelRunOnUIThread(delayed);delayed=null;}
        if(cancellation!=null){cancellation.cancel();cancellation=null;}
        if(work!=null){work.cancel(true);work=null;}
    }
}
