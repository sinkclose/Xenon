package zxc.iconic.xenon.osu;

import java.util.*;

/** Weighted LRU: evict whole textures rather than destroying their resolution to fit every frame. */
public final class OsuSkinTextureCache<T> {
    private static final class Entry<T> {final T value;final long pixels;Entry(T value,long pixels){this.value=value;this.pixels=pixels;}}
    private final LinkedHashMap<String,Entry<T>> entries=new LinkedHashMap<>(16,.75f,true);
    private final long budget;
    private long pixels;
    public OsuSkinTextureCache(long budget){this.budget=budget;}
    public synchronized T get(String name){Entry<T> entry=entries.get(name);return entry==null?null:entry.value;}
    public synchronized boolean put(String name,T value,long size){
        if(size<1||size>budget)return false;
        Entry<T> old=entries.remove(name);if(old!=null)pixels-=old.pixels;
        entries.put(name,new Entry<>(value,size));pixels+=size;
        Iterator<Entry<T>> iterator=entries.values().iterator();
        while(pixels>budget&&iterator.hasNext()){Entry<T> entry=iterator.next();pixels-=entry.pixels;iterator.remove();}
        return true;
    }
    public synchronized long pixels(){return pixels;}
    public synchronized int size(){return entries.size();}
    public synchronized void clear(){entries.clear();pixels=0;}
}
