package zxc.iconic.xenon.helpers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Platform-independent interval validation and stable configuration fingerprint. */
public final class AutoBackupPolicy {
    public static long parseInterval(String text) {
        String[] parts = text.trim().split(":", -1);
        if (parts.length != 3) throw new IllegalArgumentException();
        try {
            long[] units = {60_000L, 3_600_000L, 86_400_000L};
            long total = 0;
            for (int i = 0; i < 3; i++) {
                if (!parts[i].matches("[0-9]+")) throw new IllegalArgumentException();
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(parts[i]), units[i]));
            }
            if (total <= 0 || total > Long.MAX_VALUE / 2) throw new IllegalArgumentException();
            return total;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String formatInterval(long millis) {
        long minutes = millis / 60_000;
        return (minutes % 60) + ":" + (minutes / 60 % 24) + ":" + (minutes / 1440);
    }

    public static String formatDisplayInterval(long millis, String minuteUnit, String hourUnit, String dayUnit) {
        long totalMinutes = millis / 60_000;
        long[] values = {totalMinutes % 60, totalMinutes / 60 % 24, totalMinutes / 1440};
        String[] units = {minuteUnit, hourUnit, dayUnit};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) continue;
            if (result.length() > 0) result.append(' ');
            result.append(values[i]).append(units[i]);
        }
        return result.toString();
    }

    public static String fingerprint(Map<String, ?> config) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, ?> entry : new TreeMap<>(config).entrySet()) {
            String value = entry.getValue() instanceof java.util.Set
                    ? new java.util.TreeSet<>((java.util.Set<?>) entry.getValue()).toString()
                    : String.valueOf(entry.getValue());
            value = (entry.getValue() instanceof java.util.Set ? "set" : entry.getValue().getClass().getName()) + ":" + value;
            // Length prefixes prevent collisions between adjacent keys/values.
            digest.update((entry.getKey().length() + ":" + entry.getKey() + value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }

    public static boolean shouldSend(boolean forced, boolean onlyChanged, String hash, String previous) {
        return forced || !onlyChanged || !hash.equals(previous);
    }
}
