package dev.dayssince;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

/** Per-widget storage: start date (as epoch day) and label, keyed by appWidgetId. */
final class Store {
    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widgets", Context.MODE_PRIVATE);
    }

    static boolean has(Context c, int id) {
        return prefs(c).contains("d" + id);
    }

    static long startDay(Context c, int id) {
        return prefs(c).getLong("d" + id, LocalDate.now().toEpochDay());
    }

    static String label(Context c, int id) {
        return prefs(c).getString("l" + id, "");
    }

    static void save(Context c, int id, long epochDay, String label) {
        prefs(c).edit().putLong("d" + id, epochDay).putString("l" + id, label).commit();
    }

    static void saveDay(Context c, int id, long epochDay) {
        prefs(c).edit().putLong("d" + id, epochDay).commit();
    }

    static void saveLabel(Context c, int id, String label) {
        prefs(c).edit().putString("l" + id, label).commit();
    }

    static void remove(Context c, int id) {
        prefs(c).edit().remove("d" + id).remove("l" + id).apply();
    }
}
