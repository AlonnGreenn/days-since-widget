package com.vibecoded.morningtimer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One "alarm-like" instance: when the stopwatch appears and how it beeps. */
public class Profile {
    public static final int BEEP_OFF = 0, BEEP_INTERVAL = 1, BEEP_CUSTOM = 2;

    public int id;
    public String name = "Morning";
    public boolean enabled = true;
    public int hour = 8, minute = 5;
    /** Bit 0 = Monday ... bit 6 = Sunday. 0 = one-shot (turns itself off after firing). */
    public int days = 0b0011111;
    public int beepMode = BEEP_INTERVAL;
    public double intervalMin = 8.5;
    /** Minutes after start, e.g. "5, 10, 15, 25". */
    public String customMin = "5, 10, 15, 20, 30";

    public String timeText() { return String.format(Locale.US, "%02d:%02d", hour, minute); }

    public String daysText() {
        if (days == 0) return "Once";
        if (days == 0b1111111) return "Every day";
        if (days == 0b0011111) return "Weekdays";
        if (days == 0b1100000) return "Weekends";
        String[] n = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) b.append(b.length() > 0 ? " " : "").append(n[i]);
        return b.toString();
    }

    public String beepText() {
        switch (beepMode) {
            case BEEP_OFF: return "No beeps";
            case BEEP_INTERVAL: return "Beep every " + fmt(intervalMin) + " min";
            default: return "Beeps at " + customMin + " min";
        }
    }

    /** Beep times in ms after start, ascending, up to maxMs. */
    public List<Long> beepTimesMs(long maxMs) {
        List<Long> out = new ArrayList<>();
        if (beepMode == BEEP_INTERVAL && intervalMin > 0.05) {
            long step = (long) (intervalMin * 60_000);
            for (long t = step; t <= maxMs; t += step) out.add(t);
        } else if (beepMode == BEEP_CUSTOM) {
            for (String s : customMin.split("[,;\\s]+")) {
                try {
                    double m = Double.parseDouble(s.trim());
                    if (m > 0) out.add((long) (m * 60_000));
                } catch (NumberFormatException ignored) { }
            }
            java.util.Collections.sort(out);
        }
        return out;
    }

    public static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("enabled", enabled)
                .put("hour", hour).put("minute", minute).put("days", days)
                .put("beepMode", beepMode).put("intervalMin", intervalMin).put("customMin", customMin);
    }

    static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optInt("id");
        p.name = o.optString("name", p.name);
        p.enabled = o.optBoolean("enabled", p.enabled);
        p.hour = o.optInt("hour", p.hour);
        p.minute = o.optInt("minute", p.minute);
        p.days = o.optInt("days", p.days);
        p.beepMode = o.optInt("beepMode", p.beepMode);
        p.intervalMin = o.optDouble("intervalMin", p.intervalMin);
        p.customMin = o.optString("customMin", p.customMin);
        return p;
    }

    static String listToJson(List<Profile> list) {
        JSONArray a = new JSONArray();
        try { for (Profile p : list) a.put(p.toJson()); } catch (JSONException ignored) { }
        return a.toString();
    }

    static List<Profile> listFromJson(String s) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }
}
