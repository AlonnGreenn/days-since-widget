package com.vibecoded.morningtimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Store store = new Store(ctx);
        Profile p = store.find(intent.getIntExtra(Scheduler.EXTRA_ID, -1));
        if (p == null) return;
        long start = intent.getLongExtra(Scheduler.EXTRA_START, System.currentTimeMillis());
        // Ignore alarms delivered very late (phone was off etc.).
        if (System.currentTimeMillis() - start < 2 * 60 * 60_000L) TimerService.start(ctx, p.id, start);
        if (p.days == 0) { p.enabled = false; store.save(p); }
        Scheduler.schedule(ctx, p);
    }
}
