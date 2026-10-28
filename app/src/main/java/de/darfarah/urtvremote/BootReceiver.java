package de.darfarah.urtvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart, an update or a clock change: arm the next adhan again. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Alarms.update(c, false);
    }
}
