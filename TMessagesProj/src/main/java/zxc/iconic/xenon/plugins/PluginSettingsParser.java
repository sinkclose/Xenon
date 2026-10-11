package zxc.iconic.xenon.plugins;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import java.util.ArrayList;
import java.util.List;
import zxc.iconic.xenon.plugins.PluginManager.LoadedPlugin.PluginSetting;

/** Reads data only: a schema's metatables must never execute code on the UI thread. */
final class PluginSettingsParser {
    static List<PluginSetting> parse(LuaValue schema) {
        List<PluginSetting> settings = new ArrayList<>();
        if (!schema.istable()) return settings;
        int count = Math.min(schema.rawlen(), 512);
        for (int i = 1; i <= count; i++) {
            LuaValue entry = schema.rawget(i);
            if (!entry.istable()) continue;
            LuaValue type = entry.rawget("type"), key = entry.rawget("key"), name = entry.rawget("name");
            if (type.type() != LuaValue.TSTRING || key.type() != LuaValue.TSTRING || name.type() != LuaValue.TSTRING) continue;
            LuaValue value = entry.rawget("default");
            try {
                PluginSetting setting;
                switch (type.tojstring()) {
                    case "toggle":
                        setting = new PluginSetting(PluginSetting.Type.TOGGLE, key.tojstring(), name.tojstring(),
                                value.toboolean(), 0, null, 0, 0, 0, null, null);
                        break;
                    case "seekbar": {
                        int min = entry.rawget("min").checkint(), max = entry.rawget("max").checkint();
                        if (max <= min || (long) max - min > Integer.MAX_VALUE) continue;
                        int step = Math.max(1, entry.rawget("step").optint(1));
                        int def = Math.max(min, Math.min(max, value.optint(min)));
                        setting = new PluginSetting(PluginSetting.Type.SEEKBAR, key.tojstring(), name.tojstring(),
                                false, def, null, min, max, step, null, null);
                        break;
                    }
                    case "text":
                        setting = new PluginSetting(PluginSetting.Type.TEXT, key.tojstring(), name.tojstring(),
                                false, 0, value.optjstring(""), 0, 0, 0, entry.rawget("hint").optjstring(""), null);
                        break;
                    case "button": {
                        LuaValue action = entry.rawget("action");
                        setting = new PluginSetting(PluginSetting.Type.BUTTON, key.tojstring(), name.tojstring(),
                                false, 0, null, 0, 0, 0, null, action.isfunction() ? action : null);
                        break;
                    }
                    case "header":
                        setting = new PluginSetting(PluginSetting.Type.HEADER, key.tojstring(), name.tojstring(),
                                false, 0, null, 0, 0, 0, null, null);
                        break;
                    case "list": {
                        LuaValue options = entry.rawget("options");
                        int length = options.istable() ? Math.min(options.rawlen(), 256) : 0;
                        String[] labels = new String[length];
                        for (int j = 0; j < length; j++) labels[j] = options.rawget(j + 1).checkjstring();
                        int def = value.optint(0);
                        if (def < 0 || def >= length) def = 0;
                        setting = new PluginSetting(PluginSetting.Type.LIST, key.tojstring(), name.tojstring(),
                                false, def, null, 0, 0, 0, null, null, labels);
                        break;
                    }
                    default: continue;
                }
                settings.add(setting);
            } catch (LuaError ignored) {
                // One malformed row must not prevent opening the permissions screen.
            }
        }
        return settings;
    }
}
