"""Run the production permission, Lua and IO guards without building Android.

Requires a JDK and luaj-jse 3.0.1 in the Gradle cache (or LUAJ_JAR).
Android scheduling and HTTP transport are fakes; no device or network is used.
"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT


PLUGINS = ROOT / "zxc/iconic/xenon/plugins"


def method(source, signature):
    """Extract a Java member without counting braces inside comments or strings."""
    start = source.index(signature)
    masked = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                    lambda match: ' ' * len(match.group()), source)
    end = masked.index('{', start) + 1
    depth = 1
    while depth:
        depth += (masked[end] == '{') - (masked[end] == '}')
        end += 1
    return source[start:end]


class PluginSecurityTest(unittest.TestCase):
    def test_java_syntax_and_internal_call_arities(self):
        """Parse entire edited classes; check propagation of deferred-action guards."""
        parser = r'''
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
public class PluginSourceAudit {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask)compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null,
                    files.getJavaFileObjectsFromStrings(Arrays.asList(args)));
            for (CompilationUnitTree unit : task.parse()) {
                Map<String, Set<Integer>> arities = new HashMap<>();
                Set<String> varargs = new HashSet<>();
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree m, Void unused) {
                        String name = m.getName().toString();
                        arities.computeIfAbsent(name, key -> new HashSet<>()).add(m.getParameters().size());
                        if (m.getParameters().stream().anyMatch(p -> p.toString().contains("..."))) varargs.add(name);
                        return super.visitMethod(m, unused);
                    }
                }.scan(unit, null);
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        if (call.getMethodSelect() instanceof IdentifierTree) {
                            String name = call.getMethodSelect().toString();
                            if (arities.containsKey(name) && !varargs.contains(name)
                                    && !arities.get(name).contains(call.getArguments().size())) {
                                throw new AssertionError(unit.getSourceFile().getName() + ": wrong argument count: " + call);
                            }
                        }
                        return super.visitMethodInvocation(call, unused);
                    }
                }.scan(unit, null);
            }
        }
        for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
        }
    }
}
'''
        sources = list(PLUGINS.glob('*.java')) + [ROOT / 'zxc/iconic/xenon/settings/PluginSettingsActivity.java']
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'PluginSourceAudit.java'
            source.write_text(parser)
            result = subprocess.run(['java', str(source), *map(str, sources)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_production_guards(self):
        jars = list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.luaj/luaj-jse/3.0.1").glob("*/*jar"))
        jar = os.environ.get("LUAJ_JAR") or next((str(p) for p in jars if p.name == "luaj-jse-3.0.1.jar"), None)
        if not jar:
            self.skipTest("Set LUAJ_JAR to luaj-jse-3.0.1.jar")
        manager = (PLUGINS / "PluginManager.java").read_text()
        api = (PLUGINS / "PluginApi.java").read_text()
        constants = manager[manager.index('    public static final String SCOPE_GENERAL'):manager.index('    private static final java.util.Map')]
        manager_methods = "\n".join(method(manager, signature) for signature in (
            'private static Globals createSandboxGlobals(String owner)',
            'public static boolean hasScope(String fileName, String scope)',
            'public static org.luaj.vm2.Varargs invokeCallback(String owner, LuaValue callback,',
            'public static org.luaj.vm2.Varargs invokeCallback(String owner, PluginLuaRuntime runtime,',
            'public static List<String> parseScopes(File file)',
            'private static void grantScopes(String fileName, File file)',
            'private static void clearPluginState(String fileName)',
            'private static void copyPluginFile(File source, File destination)'))
        api_methods = "\n".join(method(api, signature) for signature in (
            'private static BooleanSupplier actionGuard(', 'private static LuaValue guardCallbacks(',
            'static boolean allowsApi(', 'static int setTimeout(', 'static void clearTimeout(',
            'private static boolean watcherAllowed(', 'private static String watcherKey('))
        # Widen access only in the harness so assertions can call private helpers.
        manager_methods = manager_methods.replace('private static', 'static')
        api_methods = api_methods.replace('private static', 'static')
        scoped = api[api.index('        LuaTable scopedApi = new LuaTable();'):api.index('    private static BooleanSupplier actionGuard(')]
        # The block is the actual final part of createApiTable, including its closing brace.
        scoped = 'static LuaTable[] scoped(LuaTable api, String pluginFileName, PluginLuaRuntime apiRuntime, LuaTable hooks) {\n' + scoped
        setting = method(manager, 'public static class PluginSetting')
        watcher = method(api, 'private static class MessageWatcher').replace('private static', 'static')
        api_names = re.findall(r'api.set\("([^"]+)"', api + (PLUGINS / 'PluginCapabilities.java').read_text())
        harness = r'''
package zxc.iconic.xenon.plugins;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.io.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.*;
import android.os.Handler;
import zxc.iconic.xenon.NekoConfig;
import org.telegram.messenger.ApplicationLoader;

class SharedPreferences {
    final Map<String, Boolean> flags = new ConcurrentHashMap<>();
    boolean getBoolean(String key, boolean fallback) { return flags.getOrDefault(key, fallback); }
    boolean contains(String key) { return flags.containsKey(key); }
    Map<String, Boolean> getAll() { return flags; }
    Editor edit() { return new Editor(); }
    class Editor {
        final Map<String, Boolean> changes = new HashMap<>();
        final Set<String> removed = new HashSet<>();
        Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
        Editor remove(String key) { removed.add(key); return this; }
        boolean commit() { removed.forEach(flags::remove); flags.putAll(changes); return true; }
        void apply() { commit(); }
    }
}
class FileLog { static void e(Throwable error) {} }
class PluginManager {
    CONSTANTS
    static final Map<String, PluginLuaRuntime> runtimes = new ConcurrentHashMap<>();
    static final SharedPreferences prefs = new SharedPreferences();
    static boolean enabled = true;
    static int failures;
    static PluginManager getInstance() { return new PluginManager(); }
    boolean isEnabled() { return enabled; }
    static SharedPreferences getPrefs() { return prefs; }
    static String[] parseMetadata(File file) { return new String[]{"name", "description", "legacy_id"}; }
    static PluginLuaRuntime getRuntime(String owner) { return runtimes.get(owner); }
    static boolean isRuntimeCurrent(String owner, PluginLuaRuntime runtime) { return runtime != null && getRuntime(owner) == runtime; }
    static void markHookStart(String name) {}
    static void markHookEnd() {}
    void quarantineFile(String owner, String stage, Throwable error) { failures++; }
    static class XenonLuajavaLib extends LuajavaLib {}
    static class LoadedPlugin { SETTING }
    MANAGER_METHODS
}
class PluginApi {
    WATCHER
    static final Map<String, MessageWatcher> watchers = new ConcurrentHashMap<>();
    static final Map<Integer, TimerEntry> timers = new ConcurrentHashMap<>();
    static final Handler timerHandler = new Handler();
    static final AtomicInteger nextTimerId = new AtomicInteger(1);
    static class TimerEntry {
        Runnable runnable; String pluginFileName;
        TimerEntry(Runnable r, String owner) { runnable = r; pluginFileName = owner; }
    }
    static boolean isPluginValid(String owner) {
        return PluginManager.enabled && PluginManager.getRuntime(owner) != null
                && getPrefs().getBoolean("plugin_enabled_" + owner, true);
    }
    static SharedPreferences getPrefs() { return PluginManager.prefs; }
    API_METHODS
    SCOPED
    static LuaValue guard(String owner, PluginLuaRuntime runtime, String api, LuaValue value) {
        return guardCallbacks(owner, runtime, api, value, new IdentityHashMap<>(), new int[]{4096}, 0);
    }
}
public class PluginSecurityHarness {
    static final String OWNER = "test.xplugin";
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void denied(Runnable runnable) {
        try { runnable.run(); throw new AssertionError("Expected a LuaError"); } catch (LuaError expected) {}
    }
    static void grant(String scope, boolean value) { PluginManager.prefs.flags.put("plugin_scope_" + OWNER + "_" + scope, value); }
    static Globals vm() {
        Globals g = PluginManager.createSandboxGlobals(OWNER);
        PluginManager.runtimes.put(OWNER, PluginLuaRuntime.attach(g));
        return g;
    }
    static LuaValue execute(Globals g, String source) {
        return PluginManager.invokeCallback(OWNER, g.load(source), LuaValue.NONE).arg1();
    }
    static void policy() {
        check(PluginManager.hasScope(OWNER, "GENERAL"), "general default");
        check(PluginManager.hasScope(OWNER, "UI"), "UI compatibility default");
        for (String scope : PluginManager.OPTIONAL_SCOPES) {
            if (!scope.equals("UI")) check(!PluginManager.hasScope(OWNER, scope), "unexpected default: " + scope);
        }
        NekoConfig.pluginGodMode = true;
        check(!PluginManager.hasScope(OWNER, "JAVA"), "God Mode needs per-plugin opt in");
        check(!PluginManager.hasScope(OWNER, "INTERNET"), "God Mode cannot grant IO");
        grant("JAVA", true);
        check(PluginManager.hasScope(OWNER, "JAVA"), "explicit Java grant");
        check(!PluginManager.hasScope(OWNER, "HOOKS"), "Java does not auto-grant hooks");
        grant("HOOKS", true);
        check(PluginManager.hasScope(OWNER, "HOOKS"), "explicit hooks grant");
        NekoConfig.pluginGodMode = false;
        check(!PluginManager.hasScope(OWNER, "JAVA"), "global Java revocation");
        grant("MESSAGING", true); grant("SEND_MESSAGES", false);
        check(PluginManager.hasScope(OWNER, "READ_MESSAGES"), "legacy messaging compatibility");
        check(!PluginManager.hasScope(OWNER, "SEND_MESSAGES"), "specific send revocation");
        grant("MESSAGING", false); grant("READ_MESSAGES", true);
        check(!PluginManager.hasScope(OWNER, "READ_MESSAGES"), "master messaging revocation");
        check(!PluginApi.allowsApi(OWNER, "futureUnclassifiedApi"), "new APIs deny by default");
        grant("UI", false);
        check(!PluginApi.allowsApi(OWNER, "refreshSettings"), "refresh needs UI");
        NekoConfig.pluginGodMode = true;
        for (String scope : PluginManager.OPTIONAL_SCOPES) grant(scope, true);
        for (String name : new String[]{API_NAMES}) check(PluginApi.allowsApi(OWNER, name), "unclassified exported API: " + name);
        NekoConfig.pluginGodMode = false;
        PluginManager.prefs.flags.clear();
    }
    static void sandboxAndJava() {
        Globals g = vm();
        for (String name : new String[]{"io", "package", "debug", "coroutine", "luajava", "load", "loadfile", "dofile", "loadstring", "require"}) {
            check(g.get(name).isnil(), "sandbox exposes " + name);
        }
        check(g.get("os").get("execute").isnil(), "os.execute");
        grant("LIMITED_JAVA", true); g = vm();
        LuaValue java = g.get("luajava");
        LuaValue math = java.get("bindClass").call(LuaValue.valueOf("java.lang.Math"));
        LuaValue abs = math.get("abs");
        check(abs.call(LuaValue.valueOf(-7)).toint() == 7, "limited Math call");
        denied(() -> java.get("bindClass").call(LuaValue.valueOf("java.io.File")));
        denied(() -> math.get("getClass"));
        LuaValue a = java.get("newInstance").call(LuaValue.valueOf("java.math.BigInteger"), LuaValue.valueOf("1"));
        LuaValue b = java.get("newInstance").call(LuaValue.valueOf("java.math.BigInteger"), LuaValue.valueOf("2"));
        check(!a.eq_b(b), "distinct Java handles must not compare equal");
        grant("LIMITED_JAVA", false);
        denied(() -> abs.call(LuaValue.valueOf(-7)));
    }
    static void migrations(File manifest) {
        NekoConfig.pluginGodMode = true;
        grant("GENERAL", true); grant("JAVA", false);
        grant("MESSAGING", false);
        PluginManager.prefs.flags.put("plugin_scope_legacy_id_MESSAGING", true);
        PluginManager.grantScopes(OWNER, manifest);
        check(!PluginManager.hasScope(OWNER, "JAVA"), "migration preserves explicit Java revocation");
        check(!PluginManager.hasScope(OWNER, "MESSAGING"), "migration preserves explicit messaging revocation");
        check(!PluginManager.hasScope(OWNER, "FILES") && !PluginManager.hasScope(OWNER, "INTERNET"), "manifest cannot auto-grant sensitive capabilities");
        PluginManager.prefs.flags.clear(); grant("GENERAL", true); grant("HOOKS", false);
        PluginManager.grantScopes(OWNER, manifest);
        check(PluginManager.hasScope(OWNER, "JAVA") && !PluginManager.hasScope(OWNER, "HOOKS"), "legacy trust migration preserves hook revocation");
        PluginManager.prefs.flags.clear();
        PluginManager.grantScopes(OWNER, manifest);
        check(!PluginManager.hasScope(OWNER, "JAVA"), "new God Mode plugin is untrusted");
        NekoConfig.pluginGodMode = false; PluginManager.prefs.flags.clear();
    }
    static void callbacksAndTimers() {
        Globals g = vm(); PluginLuaRuntime old = PluginManager.getRuntime(OWNER);
        AtomicInteger count = new AtomicInteger();
        LuaValue callback = new ZeroArgFunction() { public LuaValue call() { count.incrementAndGet(); return TRUE; }};
        grant("INTERNET", true);
        LuaValue guarded = PluginApi.guard(OWNER, old, "httpGet", callback);
        guarded.call(); check(count.get() == 1, "allowed callback");
        grant("INTERNET", false); guarded.call();
        check(count.get() == 1, "callback after scope revocation");
        grant("INTERNET", true); vm(); guarded.call();
        check(count.get() == 1, "callback from replaced VM");
        PluginManager.invokeCallback(OWNER, old, callback, LuaValue.NONE);
        check(count.get() == 1, "explicit stale VM callback");
        LuaValue broken = new ZeroArgFunction() { public LuaValue call() { throw new AssertionError("plugin failure"); }};
        PluginApi.guard(OWNER, PluginManager.getRuntime(OWNER), "httpGet", broken).call();
        check(PluginManager.failures == 1, "callback errors are contained");
        BooleanSupplier action = PluginApi.actionGuard(OWNER, PluginManager.getRuntime(OWNER), "httpGet");
        check(action.getAsBoolean(), "allowed deferred action");
        vm(); check(!action.getAsBoolean(), "stale deferred action");
        PluginApi.MessageWatcher watcher = new PluginApi.MessageWatcher();
        watcher.key = "watcher"; watcher.pluginFileName = OWNER;
        watcher.runtime = PluginManager.getRuntime(OWNER); watcher.running = true;
        PluginApi.watchers.put(watcher.key, watcher);
        grant("MESSAGING", true);
        check(PluginApi.watcherAllowed(watcher), "allowed message watcher");
        grant("READ_MESSAGES", false);
        check(!PluginApi.watcherAllowed(watcher), "watcher after read revocation");
        grant("READ_MESSAGES", true); vm();
        check(!PluginApi.watcherAllowed(watcher), "watcher after VM replacement");
        check(!PluginApi.watcherKey("p.xplugin", "q.xplugin:key").equals(
                PluginApi.watcherKey("p.xplugin:q.xplugin", "key")), "watcher owner namespaces cannot collide");
        int brokenTimer = PluginApi.setTimeout(OWNER, 0, broken);
        PluginApi.timers.get(brokenTimer).runnable.run();
        check(PluginManager.failures == 2, "timer host errors are contained");
        int oldTimer = PluginApi.setTimeout(OWNER, 0, callback);
        vm(); PluginApi.timers.get(oldTimer).runnable.run();
        check(count.get() == 1, "timer from replaced VM is dropped");
        for (double delay : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 31_536_001}) {
            denied(() -> PluginApi.setTimeout(OWNER, delay, callback));
        }
        for (int i = 0; i < 256; i++) PluginApi.setTimeout(OWNER, 0, callback);
        denied(() -> PluginApi.setTimeout(OWNER, 0, callback));
        int id = PluginApi.setTimeout("other.xplugin", 0, callback);
        PluginApi.clearTimeout(id);
        check(PluginApi.timers.size() == 256, "timer quota is per plugin");
        PluginApi.timers.clear();
    }
    static void schemaAndResults() {
        Globals g = vm();
        LuaValue schema = execute(g, "return {{type='seekbar',key='s',name='Slider',min=1,max=5}, {type='list',key='l',name='List',options={'A','B'}}, {type='seekbar',key='bad',name='Bad',min=5,max=1}} ");
        java.util.List<PluginManager.LoadedPlugin.PluginSetting> rows = PluginSettingsParser.parse(schema);
        check(rows.size() == 2 && rows.get(0).step == 1 && rows.get(1).defaultInt == 0, "optional defaults and invalid range");
        LuaValue hostile = execute(g, "return setmetatable({{}}, {__len=function() error('length executed') end, __index=function() error('index executed') end})");
        check(PluginSettingsParser.parse(hostile).isEmpty(), "schema metatables ignored");
        LuaValue result = execute(g, "local t={ok=true}; t.self=t; return setmetatable(t, {__index=function() error('index executed') end, __len=function() error('length executed') end})");
        LuaValue copied = PluginLuaValues.copyData(result);
        check(copied.get("ok").toboolean() && copied.get("self") == copied && copied.getmetatable() == null, "data snapshot preserves cycles, strips metatables");
        check(copied.get("missing").isnil(), "hook result cannot execute code in host getters");
        LuaValue huge = execute(g, "local t={}; for i=1,5000 do t[i]=i end; return t");
        denied(() -> PluginLuaValues.copyData(huge));
        LuaValue nested = execute(g, "local root={}; local t=root; for i=1,40 do t.child={}; t=t.child end; return root");
        denied(() -> PluginLuaValues.copyData(nested));
        denied(() -> PluginLuaValues.copyData(new ZeroArgFunction() { public LuaValue call() { return NIL; }}));
        denied(() -> PluginApi.guard(OWNER, PluginManager.getRuntime(OWNER), "on", huge));
        LuaValue guarded = PluginApi.guard(OWNER, PluginManager.getRuntime(OWNER), "on", result);
        check(guarded.getmetatable() == null && guarded.get("self") == guarded, "callback arguments strip metatables and preserve cycles");
    }
    static void runtime() throws Exception {
        Globals g = vm(); PluginLuaRuntime runtime = PluginManager.getRuntime(OWNER);
        long start = System.nanoTime();
        try { execute(g, "while true do pcall(function() while true do end end) end"); throw new AssertionError("loop survived budget"); }
        catch (PluginLuaRuntime.BudgetExceeded expected) {}
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "bounded pcall loop");
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        LuaValue callback = new ZeroArgFunction() { public LuaValue call() {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            active.decrementAndGet(); return NIL;
        }};
        Thread first = new Thread(() -> runtime.invoke(callback, LuaValue.NONE));
        Thread second = new Thread(() -> runtime.invoke(callback, LuaValue.NONE));
        first.start(); second.start(); first.join(); second.join();
        check(maximum.get() == 1, "Lua callbacks serialize");
        runtime.stop(); check(runtime.invoke(callback, LuaValue.NONE).arg1().isnil(), "stopped VM cannot execute");
    }
    static void filesAndHttp() throws Exception {
        File storage = new File(System.getProperty("plugin.test.storage"));
        File source = new File(storage, "source.xplugin"), destination = new File(storage, "installed.xplugin");
        java.nio.file.Files.writeString(destination.toPath(), "old");
        java.nio.file.Files.write(source.toPath(), new byte[2 * 1024 * 1024 + 1]);
        try { PluginManager.copyPluginFile(source, destination); throw new AssertionError("oversized update accepted"); }
        catch (IOException expected) {}
        check(java.nio.file.Files.readString(destination.toPath()).equals("old"), "failed install preserves existing plugin");
        java.nio.file.Files.writeString(source.toPath(), "new"); PluginManager.copyPluginFile(source, destination);
        check(java.nio.file.Files.readString(destination.toPath()).equals("new"), "atomic plugin replacement");
        check(storage.listFiles((dir, name) -> name.startsWith("install-")).length == 0, "staging files cleaned");
        vm(); grant("FILES", true); grant("INTERNET", true);
        LuaTable api = PluginApi.scoped(new LuaTable(), OWNER, PluginManager.getRuntime(OWNER), new LuaTable())[0];
        LuaString bytes = LuaString.valueOf(new byte[]{0, 1, (byte)255, 10});
        check(api.get("writeFile").call(LuaValue.valueOf("test.bin"), bytes).toboolean(), "file write");
        check(api.get("readFile").call(LuaValue.valueOf("test.bin")).raweq(bytes), "binary file roundtrip");
        denied(() -> api.get("readFile").call(LuaValue.valueOf("../secret")));
        denied(() -> api.get("writeFile").call(LuaValue.valueOf("large.bin"), LuaString.valueOf(new byte[1024*1024+1])));
        grant("FILES", false); denied(() -> api.get("readFile").call(LuaValue.valueOf("test.bin")));
        grant("FILES", true);
        check(api.get("deleteFile").call(LuaValue.valueOf("test.bin")).toboolean(), "delete own file");
        java.net.URL.setURLStreamHandlerFactory(protocol -> protocol.equals("http") ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) {
                return new java.net.HttpURLConnection(url) {
                    public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
                    public int getResponseCode() { return 200; }
                    public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream("ok".getBytes()); }
                };
            }
        } : null);
        AtomicInteger delivered = new AtomicInteger();
        LuaValue callback = new OneArgFunction() { public LuaValue call(LuaValue response) {
            check(response.get("status").toint() == 200 && response.get("body").tojstring().equals("ok"), "HTTP data");
            delivered.incrementAndGet(); return NIL;
        }};
        denied(() -> api.get("httpGet").call(LuaValue.valueOf("file:///secret"), callback));
        denied(() -> api.get("httpGet").call(LuaValue.valueOf("http://user:password@example.test"), callback));
        api.get("httpGet").call(LuaValue.valueOf("http://example.test"), callback);
        org.telegram.messenger.AndroidUtilities.awaitTask();
        org.telegram.messenger.AndroidUtilities.drain(); check(delivered.get() == 1, "HTTP callback delivered");
        api.get("httpGet").call(LuaValue.valueOf("http://example.test"), callback);
        org.telegram.messenger.AndroidUtilities.awaitTask();
        grant("INTERNET", false); org.telegram.messenger.AndroidUtilities.drain();
        check(delivered.get() == 1, "HTTP delivery checks revoked permission");
        grant("INTERNET", true);
        api.get("httpGet").call(LuaValue.valueOf("http://example.test"), callback);
        org.telegram.messenger.AndroidUtilities.awaitTask();
        vm(); org.telegram.messenger.AndroidUtilities.drain();
        check(delivered.get() == 1, "HTTP delivery checks VM generation");
        denied(() -> api.get("readFile").call(LuaValue.valueOf("test.bin")));
        grant("JAVA", true);
        PluginManager.prefs.flags.put("settings:" + OWNER.length() + ":" + OWNER + ":secret", true);
        PluginManager.prefs.flags.put("settings:other.xplugin:secret", true);
        File data = new File(System.getProperty("plugin.test.storage"), "plugin_data/" + OWNER + "/secret.txt");
        java.nio.file.Files.writeString(data.toPath(), "secret");
        PluginManager.clearPluginState(OWNER);
        check(!PluginManager.prefs.contains("plugin_scope_" + OWNER + "_JAVA") && !data.exists(), "uninstall clears privileges and private files");
        check(PluginManager.prefs.contains("settings:other.xplugin:secret"), "uninstall preserves another plugin's settings");
        java.lang.reflect.Field field = PluginCapabilities.class.getDeclaredField("network"); field.setAccessible(true);
        ((ExecutorService)field.get(null)).shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        File manifest = new File(System.getProperty("plugin.test.storage"), "manifest.xplugin");
        java.nio.file.Files.writeString(manifest.toPath(), "plugin_scopes = {'internet', \"FILES\", 'FILES', 'unknown'}");
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        check(PluginManager.parseScopes(manifest).equals(Arrays.asList("INTERNET", "FILES")), "single quotes, deduplication, locale-independent scopes");
        policy(); migrations(manifest); sandboxAndJava(); callbacksAndTimers(); schemaAndResults(); runtime(); filesAndHttp();
        System.out.println("PASS: permissions, sandbox, Java bridge, callbacks, timers, schemas, hook data, runtime, files, HTTP");
    }
}
'''
        harness = harness.replace('CONSTANTS', constants).replace('SETTING', setting).replace('MANAGER_METHODS', manager_methods).replace('API_METHODS', api_methods).replace('SCOPED', scoped).replace('WATCHER', watcher)
        harness = harness.replace('API_NAMES', ','.join('"' + name + '"' for name in api_names))
        stubs = {
            'android/os/Handler.java': '''package android.os; public class Handler {
                public void postDelayed(Runnable r, long delay) {} public void removeCallbacks(Runnable r) {}
            }''',
            'zxc/iconic/xenon/NekoConfig.java': 'package zxc.iconic.xenon; public class NekoConfig { public static boolean pluginGodMode; }',
            'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger;
                public class ApplicationLoader {
                    public static final Context applicationContext = new Context();
                    public static class Context { public java.io.File getFilesDir() { return new java.io.File(System.getProperty("plugin.test.storage")); } }
                }''',
            'org/telegram/messenger/AndroidUtilities.java': '''package org.telegram.messenger;
                public class AndroidUtilities {
                    static final java.util.concurrent.BlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();
                    public static void runOnUIThread(Runnable r) { tasks.add(r); }
                    public static void awaitTask() throws Exception {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                        while (tasks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                        if (tasks.isEmpty()) throw new AssertionError("HTTP completion not scheduled");
                    }
                    public static void drain() { Runnable r; while ((r = tasks.poll()) != null) r.run(); }
                }''',
            'org/telegram/messenger/browser/Browser.java': '''package org.telegram.messenger.browser;
                public class Browser { public static void openUrl(Object context, String address) {} }''',
        }
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            for name, content in stubs.items():
                path = out / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            path = out / 'zxc/iconic/xenon/plugins/PluginSecurityHarness.java'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(harness)
            sources = list(out.rglob('*.java')) + [PLUGINS / name for name in (
                'PluginPermissionPolicy.java', 'PluginLuaRuntime.java', 'PluginLimitedJava.java',
                'PluginSettingsParser.java', 'PluginLuaValues.java', 'PluginCapabilities.java')]
            compile_result = subprocess.run(['javac', '-cp', jar, '-d', directory, *map(str, sources)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(['java', '-Xmx128m', '-Dplugin.test.storage=' + directory,
                                     '-cp', directory + os.pathsep + jar,
                                     'zxc.iconic.xenon.plugins.PluginSecurityHarness'], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS:', result.stdout)


if __name__ == '__main__':
    unittest.main()
