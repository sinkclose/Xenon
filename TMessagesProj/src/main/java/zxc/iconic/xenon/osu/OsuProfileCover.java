package zxc.iconic.xenon.osu;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.*;
import org.json.*;

/** Optional public profile decoration. Never needed to prepare or play a replay. */
final class OsuProfileCover {
    private static final Pattern INITIAL_DATA=Pattern.compile("data-initial-data=\"([^\"]+)\"");
    private static JSONObject user(String page,long userId)throws IOException{
        Matcher match=INITIAL_DATA.matcher(page);if(!match.find())throw new IOException("Profile data unavailable");
        String json=match.group(1).replace("&quot;","\"").replace("&#039;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
        try{
            JSONObject user=new JSONObject(json).getJSONObject("user");
            if(user.optLong("id")!=userId)throw new IOException("Profile identity mismatch");
            return user;
        }catch(JSONException error){throw new IOException("Invalid public profile data",error);}
    }
    static String username(String page,long userId)throws IOException{
        String name=user(page,userId).optString("username","").trim();
        if(name.isEmpty())return null;
        if(name.length()>64)throw new IOException("Invalid profile username");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IOException("Invalid profile username");
        return name;
    }
    static String fetchUsername(long userId)throws IOException{
        if(userId<=0)return null;
        File cached=OsuLocalCache.file("profiles",userId+".html"),profile;
        try{profile=OsuLocalCache.fetch("profiles",userId+".html","https://osu.ppy.sh/users/"+userId,2*1024*1024,null,null,15*60*1000);}
        catch(IOException error){if(!cached.isFile())throw error;profile=cached;}
        return username(new String(Files.readAllBytes(profile.toPath()),StandardCharsets.UTF_8),userId);
    }
    static String cachedUsername(long userId)throws IOException{
        File profile=OsuLocalCache.file("profiles",userId+".html");
        return profile.isFile()?username(new String(Files.readAllBytes(profile.toPath()),StandardCharsets.UTF_8),userId):null;
    }
    static String coverUrl(String page,long userId)throws IOException{
        try{
            JSONObject user=user(page,userId);
            JSONObject cover=user.optJSONObject("cover");String address=cover==null?user.optString("cover_url",""):cover.optString("url","");
            if(address.isEmpty())return null;
            URI uri=URI.create(address);
            if(!"https".equals(uri.getScheme())||!"assets.ppy.sh".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IOException("Invalid profile cover URL");
            return address;
        }catch(IllegalArgumentException error){throw new IOException("Invalid public profile data",error);}
    }
    static File fetch(long userId)throws IOException{
        if(userId<=0)return null;
        File cached=OsuLocalCache.file("covers",userId+".img");if(cached.isFile())return cached;
        File profile=OsuLocalCache.fetch("profiles",userId+".html","https://osu.ppy.sh/users/"+userId,2*1024*1024,null);
        String address;
        try{address=coverUrl(new String(Files.readAllBytes(profile.toPath()),StandardCharsets.UTF_8),userId);}catch(IOException error){profile.delete();throw error;}
        return address==null?null:OsuLocalCache.fetch("covers",userId+".img",address,8*1024*1024,null);
    }
}
