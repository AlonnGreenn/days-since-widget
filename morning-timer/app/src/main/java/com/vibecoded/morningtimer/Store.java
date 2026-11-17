package com.vibecoded.morningtimer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** All persisted state lives in one SharedPreferences file. */
public class Store {
    private final SharedPreferences sp;

    public Store(Context c) { sp = c.getSharedPreferences("timer", Context.MODE_PRIVATE); }

    public List<Profile> profiles() {
        if (!sp.contains("profiles")) {
            List<Profile> l = new ArrayList<>();
            Profile p = new Profile();
            p.id = 1;
            p.enabled = false; // nothing fires until the user turns it on
            l.add(p);
            saveProfiles(l);
            return l;
        }
        return Profile.listFromJson(sp.getString("profiles", "[]"));
    }

    public void saveProfiles(List<Profile> l) { sp.edit().putString("profiles", Profile.listToJson(l)).apply(); }

    public Profile find(int id) {
        for (Profile p : profiles()) if (p.id == id) return p;
        return null;
    }

    public void save(Profile p) {
        List<Profile> l = profiles();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id == p.id) { l.set(i, p); found = true; }
        if (!found) l.add(p);
        saveProfiles(l);
    }

    public void delete(int id) {
        List<Profile> l = profiles();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id == id) l.remove(i);
        saveProfiles(l);
    }

    public int nextId() {
        int max = 0;
        for (Profile p : profiles()) max = Math.max(max, p.id);
        return max + 1;
    }

    // ---- global settings ----
    public int sound() { return sp.getInt("sound", 0); }
    public void setSound(int v) { sp.edit().putInt("sound", v).apply(); }
    public int volume() { return sp.getInt("volume", 60); } // 0..100
    public void setVolume(int v) { sp.edit().putInt("volume", v).apply(); }
    public int size() { return sp.getInt("size", 100); } // overlay scale %
    public void setSize(int v) { sp.edit().putInt("size", v).apply(); }
    public int opacity() { return sp.getInt("opacity", 85); } // overlay opacity %
    public void setOpacity(int v) { sp.edit().putInt("opacity", v).apply(); }
    public int position() { return sp.getInt("position", 1); } // 0..7, see Overlay
    public void setPosition(int v) { sp.edit().putInt("position", v).apply(); }
    public String lastResult() { return sp.getString("last", ""); }
    public void setLastResult(String s) { sp.edit().putString("last", s).apply(); }
}
