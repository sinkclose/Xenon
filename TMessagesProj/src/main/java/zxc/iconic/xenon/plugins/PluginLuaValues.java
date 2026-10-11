package zxc.iconic.xenon.plugins;

import java.util.IdentityHashMap;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** Copies hook results as bounded data, without executable metatables or Java handles. */
final class PluginLuaValues {
    static LuaValue copyData(LuaValue value) {
        return copy(value, new IdentityHashMap<>(), new int[]{4096}, 0);
    }

    private static LuaValue copy(LuaValue value, IdentityHashMap<LuaValue, LuaValue> copies, int[] remaining, int depth) {
        if (--remaining[0] < 0 || depth > 32) throw new LuaError("Hook result is too large or deeply nested");
        switch (value.type()) {
            case LuaValue.TNIL: case LuaValue.TBOOLEAN: case LuaValue.TNUMBER: case LuaValue.TSTRING:
                return value;
            case LuaValue.TTABLE:
                if (copies.containsKey(value)) return copies.get(value);
                LuaTable result = new LuaTable();
                copies.put(value, result);
                LuaValue key = LuaValue.NIL;
                while (true) {
                    org.luaj.vm2.Varargs next = value.next(key);
                    key = next.arg1();
                    if (key.isnil()) break;
                    if (key.type() != LuaValue.TSTRING && key.type() != LuaValue.TNUMBER && key.type() != LuaValue.TBOOLEAN) {
                        throw new LuaError("Hook result keys must be strings, numbers or booleans");
                    }
                    result.rawset(key, copy(next.arg(2), copies, remaining, depth + 1));
                }
                return result;
            default: throw new LuaError("Hook results must contain only Lua data");
        }
    }
}
