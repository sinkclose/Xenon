package zxc.iconic.xenon.helpers;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;
import java.util.HashMap;
import java.util.function.BiConsumer;

public class RegDateHelper {
    private static final HashMap<Long, Integer> regDates = new HashMap<>();

    public static String formatRegDate(int regDate, String error) {
        if (error != null) return error;
        return LocaleController.formatString(R.string.RegistrationDateApproximately, LocaleController.getInstance().getFormatterMonthYear().format(regDate * 1000L));
    }

    public static Integer getRegDate(long userId) {
        return regDates.get(userId);
    }

    public static void getRegDate(long userId, BiConsumer<Integer, String> callback) {
        Integer cached = regDates.get(userId);
        if (cached != null) {
            callback.accept(cached, null);
        } else {
            callback.accept(0, "UNAVAILABLE");
        }
    }

    public static void setRegDate(long dialogId, TLRPC.PeerSettings settings) {
        if (settings == null || settings.registration_month == null) {
            return;
        }
        var parts = settings.registration_month.split("\\.");
        if (parts.length != 2) return;
        var month = Integer.parseInt(parts[0]);
        var year = Integer.parseInt(parts[1]);
        var calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 2, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        regDates.put(dialogId, (int) (calendar.getTimeInMillis() / 1000L));
    }
}
