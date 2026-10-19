package dev.dayssince;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.ZoneId;

/** The 1x1 home-screen widget. Shows whole calendar days since the stored date. */
public class DaysWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "dev.dayssince.action.REFRESH";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id);
        scheduleMidnightRefresh(c);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_REFRESH:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case Intent.ACTION_DATE_CHANGED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                updateAll(c);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) Store.remove(c, id);
    }

    @Override
    public void onDisabled(Context c) {
        c.getSystemService(AlarmManager.class).cancel(refreshIntent(c));
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, DaysWidget.class));
        for (int id : ids) render(c, m, id);
        if (ids.length > 0) scheduleMidnightRefresh(c);
    }

    static long daysSince(Context c, int id) {
        long days = LocalDate.now().toEpochDay() - Store.startDay(c, id);
        return Math.max(0, days);
    }

    static void render(Context c, AppWidgetManager m, int id) {
        long days = daysSince(c, id);
        String label = Store.label(c, id);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.count, String.valueOf(days));
        v.setTextViewText(R.id.label, label.isEmpty() ? (days == 1 ? "day" : "days") : label);
        v.setContentDescription(android.R.id.background,
                days + (days == 1 ? " day" : " days") + (label.isEmpty() ? "" : " since " + label));

        Intent open = new Intent(c, CounterActivity.class)
                .setAction(CounterActivity.ACTION_MENU)
                .setData(Uri.parse("dayssince://widget/" + id)) // unique per widget
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(android.R.id.background, pi);

        m.updateAppWidget(id, v);
    }

    /**
     * Refresh just after local midnight. Non-wakeup alarm: if the phone is asleep it fires
     * as soon as the phone wakes (e.g. screen on), which is exactly when the widget is seen.
     */
    static void scheduleMidnightRefresh(Context c) {
        long at = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli() + 2_000;
        c.getSystemService(AlarmManager.class).set(AlarmManager.RTC, at, refreshIntent(c));
    }

    private static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, DaysWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
