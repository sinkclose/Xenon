package zxc.iconic.xenon.plugins;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.lang.reflect.*;
import java.util.*;

/** Value-only Java bridge. Never exposes a Class, reflection, Android or IO object. */
public final class PluginLimitedJava {
    private static final Set<String> CLASSES = new HashSet<>(Arrays.asList(
            "java.lang.String", "java.lang.Math", "java.lang.Integer", "java.lang.Long",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.util.UUID",
            "java.math.BigInteger", "java.math.BigDecimal", "java.util.ArrayList", "java.util.HashMap"));
    private final Runnable check;
    public PluginLimitedJava(Runnable check) { this.check = check; }
    public LuaTable library() {
        LuaTable lib = new LuaTable();
        lib.set("bindClass", new VarArgFunction() { public Varargs invoke(Varargs a) {
            return permittedType(a.arg1().checkjstring());
        }});
        lib.set("newInstance", new VarArgFunction() { public Varargs invoke(Varargs a) {
            return construct(permittedType(a.arg1().checkjstring()).type, a.subargs(2));
        }});
        return lib;
    }
    private Handle permittedType(String name) {
        check.run();
        if (!CLASSES.contains(name)) throw new LuaError("Restricted Java class: " + name);
        try { return new Handle(Class.forName(name), null); }
        catch (ClassNotFoundException e) { throw new LuaError(e); }
    }
    private LuaValue construct(Class<?> type, Varargs args) {
        check.run();
        for (Constructor<?> constructor : type.getConstructors()) {
            Object[] converted = convert(constructor.getParameterTypes(), args);
            if (converted == null) continue;
            try { return wrap(constructor.newInstance(converted)); }
            catch (ReflectiveOperationException e) { throw failure(e); }
        }
        throw new LuaError("No supported constructor");
    }
    private LuaError failure(Exception error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
        return new LuaError(cause.toString());
    }
    private Object[] convert(Class<?>[] types, Varargs args) {
        if (types.length != args.narg()) return null;
        Object[] result = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            LuaValue value = args.arg(i + 1); Class<?> t = types[i]; Object v;
            if (value instanceof Handle && ((Handle)value).object != null) v = ((Handle)value).object;
            else if (value.isnil() && !t.isPrimitive()) v = null;
            else if (value.isboolean() && (t == boolean.class || t == Boolean.class || t == Object.class)) v = value.toboolean();
            else if (value.isnumber() && (t == int.class || t == Integer.class)) v = value.toint();
            else if (value.isnumber() && (t == long.class || t == Long.class)) v = value.tolong();
            else if (value.isnumber() && (t == double.class || t == Double.class || t == Object.class)) v = value.todouble();
            else if (value.isnumber() && (t == float.class || t == Float.class)) v = (float)value.todouble();
            else if (value.type() == LuaValue.TSTRING && (t == String.class || t == CharSequence.class || t == Object.class)) v = value.tojstring();
            else return null;
            if (v != null && !t.isPrimitive() && !t.isInstance(v)) return null;
            result[i] = v;
        }
        return result;
    }
    private LuaValue wrap(Object value) {
        if (value == null) return LuaValue.NIL;
        if (value instanceof String || value instanceof Character) return LuaValue.valueOf(value.toString());
        if (value instanceof Boolean) return LuaValue.valueOf((Boolean)value);
        if (value instanceof Number && !(value instanceof java.math.BigInteger) && !(value instanceof java.math.BigDecimal)) return LuaValue.valueOf(((Number)value).doubleValue());
        Class<?> type = value instanceof List ? List.class : value instanceof Map ? Map.class : value.getClass();
        if (type != List.class && type != Map.class && !CLASSES.contains(type.getName())) throw new LuaError("Restricted Java return type");
        return new Handle(type, value);
    }
    private final class Handle extends LuaUserdata {
        final Class<?> type; final Object object;
        Handle(Class<?> type, Object object) { super("restricted Java handle"); this.type = type; this.object = object; }
        @Override public LuaValue get(LuaValue key) {
            check.run(); String method = key.checkjstring();
            if (method.equals("getClass") || method.equals("wait") || method.equals("notify") || method.equals("notifyAll")) throw new LuaError("Restricted Java method: " + method);
            if (object == null) {
                try {
                    Field field = type.getField(method);
                    if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) return wrap(field.get(null));
                } catch (NoSuchFieldException ignored) {} catch (IllegalAccessException e) { throw new LuaError(e); }
            }
            return new VarArgFunction() { public Varargs invoke(Varargs arguments) {
                check.run(); Varargs args = arguments.arg1() == Handle.this ? arguments.subargs(2) : arguments;
                for (Method candidate : type.getMethods()) {
                    if (!candidate.getName().equals(method) || (object == null && !Modifier.isStatic(candidate.getModifiers()))) continue;
                    Object[] converted = convert(candidate.getParameterTypes(), args);
                    if (converted == null) continue;
                    try { return wrap(candidate.invoke(object, converted)); }
                    catch (ReflectiveOperationException e) { throw failure(e); }
                }
                throw new LuaError("Unsupported Java method: " + method);
            }};
        }
        @Override public void set(LuaValue key, LuaValue value) { throw new LuaError("Restricted Java fields are read-only"); }
    }
}
