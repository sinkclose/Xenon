package zxc.iconic.xenon.proxy;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Adapts both libv2ray stats APIs, which return and reset traffic counters. */
final class XrayTrafficStats {
    private Object controller;
    private Method legacyQuery;
    private Method bulkQuery;
    private final Map<String, Long> pending = new HashMap<>();

    synchronized void reset() {
        controller = null;
        legacyQuery = null;
        bulkQuery = null;
        pending.clear();
    }

    synchronized long query(Object currentController, String tag, String link) throws Exception {
        if (controller != currentController) {
            reset();
            try {
                legacyQuery = currentController.getClass().getMethod("queryStats", String.class, String.class);
            } catch (NoSuchMethodException ignored) {
                bulkQuery = currentController.getClass().getMethod("queryAllOutboundTrafficStats");
            }
            controller = currentController;
        }
        if (legacyQuery != null) {
            return ((Number) legacyQuery.invoke(controller, tag, link)).longValue();
        }

        String stats = (String) bulkQuery.invoke(controller);
        if (stats != null && !stats.isEmpty()) {
            for (String entry : stats.split(";")) {
                // Split from the right so tags containing commas still work.
                int valueSeparator = entry.lastIndexOf(',');
                int directionSeparator = entry.lastIndexOf(',', valueSeparator - 1);
                if (directionSeparator < 0 || valueSeparator <= directionSeparator) {
                    continue;
                }
                try {
                    long value = Long.parseLong(entry.substring(valueSeparator + 1));
                    String key = entry.substring(0, directionSeparator) + ">>>"
                            + entry.substring(directionSeparator + 1, valueSeparator);
                    Long previous = pending.get(key);
                    pending.put(key, (previous == null ? 0L : previous) + value);
                } catch (NumberFormatException ignored) {
                    // A malformed entry must not discard the other counters.
                }
            }
        }
        Long value = pending.remove(tag + ">>>" + link);
        return value == null ? 0L : value;
    }
}
