package de.darfarah.urtvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** The adhan alarm: hand the work to AdhanService (a foreground service may start from an alarm-clock alarm). */
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Alarms.ADHAN.equals(i.getAction())) return;
        long at = i.getLongExtra("at", 0);
        // a phone that slept through the alarm must not call a late adhan
        if (at > 0 && Math.abs(System.currentTimeMillis() - at) > 10 * 60_000L) { Alarms.update(c, false); return; }
        Intent s = new Intent(c, AdhanService.class).putExtra("prayer", i.getIntExtra("prayer", -1));
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    }
}
