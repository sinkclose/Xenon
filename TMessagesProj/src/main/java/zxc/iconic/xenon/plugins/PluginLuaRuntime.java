package zxc.iconic.xenon.plugins;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

/** Serializes a Lua VM and aborts runaway bytecode; never exposed as Lua debug API. */
public final class PluginLuaRuntime extends DebugLib {
    public static final class BudgetExceeded extends Error {
        BudgetExceeded() { super("Plugin execution exceeded its time budget"); }
    }
    private long deadline;
    private int depth;
    private int instructions;
    private volatile boolean stopped;

    public static PluginLuaRuntime attach(Globals globals) {
        PluginLuaRuntime runtime = new PluginLuaRuntime();
        // DebugLib initialization expects package.loaded even in a stripped VM.
        // Use a private temporary registry, never the plugin-visible package table.
        LuaValue previousPackage = globals.get("package");
        org.luaj.vm2.LuaTable registry = new org.luaj.vm2.LuaTable();
        registry.set("loaded", new org.luaj.vm2.LuaTable());
        globals.set("package", registry);
        try { globals.load(runtime); }
        finally { globals.set("package", previousPackage); }
        globals.set("debug", LuaValue.NIL);
        globals.set("coroutine", LuaValue.NIL); // LuaJ coroutines create separate unguarded threads.
        return runtime;
    }
    public void stop() { stopped = true; }
    public synchronized Varargs invoke(LuaValue callback, Varargs args) {
        if (stopped) return LuaValue.NIL;
        if (depth++ == 0) deadline = System.nanoTime() + 2_000_000_000L;
        try { return callback.invoke(args); }
        finally { if (--depth == 0) deadline = 0; }
    }
    @Override public void onInstruction(int pc, Varargs args, int top) {
        // Error, rather than LuaError, cannot be swallowed by a Lua pcall loop.
        if ((++instructions & 255) == 0 && (stopped || Thread.currentThread().isInterrupted()
                || (deadline != 0 && System.nanoTime() > deadline))) throw new BudgetExceeded();
    }
    // No debug call-stack tracking is needed; only bytecode checkpoints.
    @Override public void onCall(org.luaj.vm2.LuaFunction function) { }
    @Override public void onCall(org.luaj.vm2.LuaClosure closure, Varargs args, LuaValue[] stack) { }
    @Override public void onReturn() { }
}
