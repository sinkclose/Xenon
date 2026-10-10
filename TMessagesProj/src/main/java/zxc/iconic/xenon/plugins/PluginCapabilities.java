package zxc.iconic.xenon.plugins;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.browser.Browser;

/** Mediated IO: plugins receive bytes, never streams, connections or Android objects. */
final class PluginCapabilities {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ExecutorService network = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> new Thread(r, "plugin-http"), new ThreadPoolExecutor.AbortPolicy());
    private static File file(String owner, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9._-]{1,128}") || name.equals(".") || name.equals("..")) throw new IOException("Use a plain filename");
        File root = new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "plugin_data"), owner).getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create plugin storage");
        File result = new File(root, name).getCanonicalFile();
        if (!root.equals(result.getParentFile())) throw new IOException("File is outside plugin storage");
        return result;
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((count = input.read(buffer)) != -1) {
            if (System.nanoTime() > deadline) throw new IOException("Read timed out");
            if (bytes.size() + count > MAX_BYTES) throw new IOException("Response/file exceeds 1 MiB");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static URL url(String input) throws MalformedURLException {
        URL url = new URL(input);
        if ((!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) || url.getUserInfo() != null) throw new MalformedURLException("Only HTTP(S) URLs without credentials are supported");
        return url;
    }
    static void attach(LuaTable api, String owner) {
        api.set("readFile", new VarArgFunction() { public Varargs invoke(Varargs a) {
            try (InputStream input = new FileInputStream(file(owner, a.arg1().checkjstring()))) { return LuaString.valueOf(read(input)); }
            catch (IOException error) { throw new LuaError(error.toString()); }
        }});
        api.set("writeFile", new VarArgFunction() { public Varargs invoke(Varargs a) {
            LuaString value = a.arg(2).checkstring();
            if (value.m_length > MAX_BYTES) throw new LuaError("File exceeds 1 MiB");
            File temporary = null;
            try {
                File target = file(owner, a.arg1().checkjstring());
                long total = 0; File[] existing = target.getParentFile().listFiles();
                if (existing != null) for (File entry : existing) if (!entry.equals(target)) total += entry.length();
                if (total + value.m_length > 16L * MAX_BYTES) throw new IOException("Plugin storage exceeds 16 MiB");
                temporary = File.createTempFile("write-", ".tmp", target.getParentFile());
                try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(value.m_bytes, value.m_offset, value.m_length); output.getFD().sync(); }
                if (!temporary.renameTo(target)) throw new IOException("Cannot replace file");
                return LuaValue.TRUE;
            } catch (IOException error) { throw new LuaError(error.toString()); }
            finally { if (temporary != null) temporary.delete(); }
        }});
        api.set("deleteFile", new VarArgFunction() { public Varargs invoke(Varargs a) {
            try { return LuaValue.valueOf(file(owner, a.arg1().checkjstring()).delete()); }
            catch (IOException error) { throw new LuaError(error.toString()); }
        }});
        api.set("httpGet", new VarArgFunction() { public Varargs invoke(Varargs a) {
            final URL destination;
            try { destination = url(a.arg1().checkjstring()); } catch (IOException e) { throw new LuaError(e.toString()); }
            final LuaValue callback = a.arg(2).checkfunction();
            try { network.execute(() -> {
                if (!PluginManager.hasScope(owner, PluginManager.SCOPE_INTERNET)) return;
                HttpURLConnection connection = null; LuaTable response = new LuaTable();
                try {
                    connection = (HttpURLConnection) destination.openConnection();
                    connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
                    response.set("status", LuaValue.valueOf(connection.getResponseCode()));
                    InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (stream != null) try (InputStream input = stream) { response.set("body", LuaString.valueOf(read(input))); }
                } catch (Exception e) { response.set("error", e.toString()); }
                finally { if (connection != null) connection.disconnect(); }
                if (PluginManager.hasScope(owner, PluginManager.SCOPE_INTERNET)) AndroidUtilities.runOnUIThread(() -> callback.call(response));
            }); } catch (RejectedExecutionException e) { throw new LuaError("Too many queued HTTP requests"); }
            return LuaValue.NIL;
        }});
        api.set("openLink", new VarArgFunction() { public Varargs invoke(Varargs a) {
            final String address;
            try { address = url(a.arg1().checkjstring()).toString(); } catch (IOException e) { throw new LuaError(e.toString()); }
            AndroidUtilities.runOnUIThread(() -> {
                if (PluginManager.hasScope(owner, PluginManager.SCOPE_LINKS)) Browser.openUrl(ApplicationLoader.applicationContext, address);
            });
            return LuaValue.NIL;
        }});
    }
}
