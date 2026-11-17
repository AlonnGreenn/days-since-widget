package com.vibecoded.morningtimer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

/** One screen: timers list (like alarms), start-now, and settings. Built in code, no layouts. */
public class MainActivity extends Activity {
    private Store store;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(32));
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Scheduler.scheduleAll(this);
        render();
    }

    private void render() {
        root.removeAllViews();

        // ---- permissions ----
        if (!Settings.canDrawOverlays(this)) {
            addButton("Allow floating window (needed for the on-screen stopwatch)", v -> startActivity(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
            addButton("Allow exact alarms (so it starts on the minute)", v -> startActivity(
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        }

        // ---- timers ----
        header("Timers");
        List<Profile> list = store.profiles();
        for (Profile p : list) root.addView(row(p));
        addButton("+ Add timer", v -> {
            Profile p = new Profile();
            p.id = store.nextId();
            p.name = "Timer " + p.id;
            edit(p, true);
        });

        header("Now");
        if (TimerService.running) {
            addText("A timer is running — tap Done on it (or in the notification).");
        } else {
            addButton("Start now", v -> {
                if (list.size() == 1) { TimerService.start(this, list.get(0).id, System.currentTimeMillis()); return; }
                String[] names = new String[list.size()];
                for (int i = 0; i < names.length; i++) names[i] = list.get(i).name;
                new AlertDialog.Builder(this).setTitle("Start which timer?").setItems(names,
                        (d, w) -> TimerService.start(this, list.get(w).id, System.currentTimeMillis())).show();
            });
        }
        if (!store.lastResult().isEmpty()) addText("Last: " + store.lastResult());

        // ---- settings ----
        header("Beep sound");
        Button sound = addButton("Sound: " + Beeper.NAMES[store.sound()], null);
        sound.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Beep sound")
                .setSingleChoiceItems(Beeper.NAMES, store.sound(), (d, w) -> {
                    store.setSound(w);
                    Beeper.play(w, store.volume());
                    sound.setText("Sound: " + Beeper.NAMES[w]);
                }).setPositiveButton("OK", null).show());
        slider("Volume", 5, 100, store.volume(), "%", store::setVolume);
        addButton("Test beep", v -> Beeper.play(store.sound(), store.volume()));
        addText("Beeps follow your notification volume, and stay silent in silent / Do Not Disturb.");

        header("Floating window");
        slider("Size", 60, 200, store.size(), "%", store::setSize);
        slider("Opacity", 30, 100, store.opacity(), "%", store::setOpacity);
        addText("Drag it around; it hops to the nearest of 8 spots (corners and thirds of each side) and remembers it. Changes apply to the next run.");
    }

    private View row(Profile p) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this);
        long next = Scheduler.nextFire(p, System.currentTimeMillis());
        String nextText = next < 0 ? "Off" : "Next: " + DateFormat.format("EEE HH:mm", new Date(next));
        t.setText(p.timeText() + "   " + p.name + "\n" + p.daysText() + " · " + p.beepText() + "\n" + nextText);
        t.setTextSize(16);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(p.enabled);
        sw.setOnCheckedChangeListener((v, on) -> { p.enabled = on; saveAndRefresh(p); });
        r.addView(sw);
        r.setOnClickListener(v -> edit(p, false));
        return r;
    }

    private void edit(Profile src, boolean isNew) {
        Profile p = Profile.fromJson(toJsonQuiet(src)); // work on a copy
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);

        EditText name = new EditText(this);
        name.setHint("Name");
        name.setText(p.name);
        f.addView(name);

        Button time = new Button(this);
        time.setText("Starts at " + p.timeText());
        time.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            p.hour = h; p.minute = m;
            time.setText("Starts at " + p.timeText());
        }, p.hour, p.minute, true).show());
        f.addView(time);

        label(f, "Days (none = once)");
        LinearLayout days = new LinearLayout(this);
        String[] dn = {"M", "T", "W", "T", "F", "S", "S"};
        CheckBox[] boxes = new CheckBox[7];
        for (int i = 0; i < 7; i++) {
            boxes[i] = new CheckBox(this);
            boxes[i].setText(dn[i]);
            boxes[i].setChecked((p.days & (1 << i)) != 0);
            days.addView(boxes[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        f.addView(days);

        label(f, "Beeps");
        RadioGroup mode = new RadioGroup(this);
        String[] modes = {"Off", "Every N minutes", "Custom times"};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(modes[i]);
            rb.setId(100 + i);
            mode.addView(rb);
        }
        mode.check(100 + p.beepMode);
        f.addView(mode);

        EditText interval = new EditText(this);
        interval.setHint("Minutes between beeps, e.g. 8.5");
        interval.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        interval.setText(Profile.fmt(p.intervalMin));
        f.addView(interval);

        EditText custom = new EditText(this);
        custom.setHint("Minutes after start, e.g. 5, 10, 15, 25");
        custom.setText(p.customMin);
        f.addView(custom);

        Runnable vis = () -> {
            int m = mode.getCheckedRadioButtonId() - 100;
            interval.setVisibility(m == Profile.BEEP_INTERVAL ? View.VISIBLE : View.GONE);
            custom.setVisibility(m == Profile.BEEP_CUSTOM ? View.VISIBLE : View.GONE);
        };
        mode.setOnCheckedChangeListener((g, id) -> vis.run());
        vis.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "New timer" : "Edit timer")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    p.name = name.getText().toString().trim().isEmpty() ? "Timer" : name.getText().toString().trim();
                    p.days = 0;
                    for (int i = 0; i < 7; i++) if (boxes[i].isChecked()) p.days |= 1 << i;
                    p.beepMode = mode.getCheckedRadioButtonId() - 100;
                    try { p.intervalMin = Double.parseDouble(interval.getText().toString().trim()); } catch (NumberFormatException ignored) { }
                    p.customMin = custom.getText().toString().trim();
                    if (isNew) p.enabled = true;
                    saveAndRefresh(p);
                })
                .setNegativeButton("Cancel", null);
        if (!isNew) b.setNeutralButton("Delete", (d, w) -> {
            Scheduler.cancel(this, p.id);
            store.delete(p.id);
            render();
        });
        b.show();
    }

    private void saveAndRefresh(Profile p) {
        store.save(p);
        Scheduler.schedule(this, p);
        render();
    }

    private static org.json.JSONObject toJsonQuiet(Profile p) {
        try { return p.toJson(); } catch (org.json.JSONException e) { return new org.json.JSONObject(); }
    }

    // ---- tiny view helpers ----

    private interface IntSink { void set(int v); }

    private void slider(String label, int min, int max, int value, String unit, IntSink sink) {
        TextView t = addText(label + ": " + value + unit);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(value - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean user) {
                t.setText(label + ": " + (prog + min) + unit);
                sink.set(prog + min);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(s);
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

    private void label(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(12), 0, 0);
        parent.addView(t);
    }

    private TextView addText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
        return t;
    }

    private Button addButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        root.addView(b);
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
