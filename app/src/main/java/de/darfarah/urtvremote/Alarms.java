package de.darfarah.urtvremote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/** One exact alarm for the next adhan; the times and Developer Mode are refreshed around it. */
final class Alarms {
    private Alarms() {}

    static final String ADHAN = "de.darfarah.urtvremote.ADHAN";

    private static PendingIntent pi(Context c, int prayer, long at) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ADHAN).putExtra("prayer", prayer).putExtra("at", at);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 7, i, flags);
    }

    /** Arms the alarm from the stored timetable (no network). */
    static synchronized void schedule(Context c) {
        Prefs p = new Prefs(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!p.adhanOn()) {
            am.cancel(pi(c, -1, 0));
            p.edit().putString("nextAdhan", "متوقف").apply();
            return;
        }
        long[] n = Times.next(Times.read(c), System.currentTimeMillis() + 20_000L, p.fajr());
        if (n == null) {
            am.cancel(pi(c, -1, 0));
            p.edit().putString("nextAdhan", "لا توجد مواقيت بعد (يحتاج إنترنت مرة)").apply();
            return;
        }
        PendingIntent alarm = pi(c, (int) n[1], n[0]);
        try {
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent show = PendingIntent.getActivity(c, 8, new Intent(c, MainActivity.class), flags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(n[0], show), alarm);   // exact, even in doze
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[0], alarm);
            else am.setExact(AlarmManager.RTC_WAKEUP, n[0], alarm);
        }
        p.edit().putString("nextAdhan", Times.NAMES[(int) n[1]] + " · " + Times.clock(n[0])).apply();
    }

    /** Refresh the timetable and Developer Mode if due, then re-arm; off the main thread. */
    static void update(Context c, final boolean force) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Times.refresh(app, force);
            extendDevMode(app, force);
            schedule(app);
        }).start();
    }

    /**
     * Developer Mode on the TV lasts 1000 hours; LG's site resets it with the session token the TV holds
     * (/var/luna/preferences/devmode_enabled). Once a day is plenty. Blocking.
     */
    static void extendDevMode(Context c, boolean force) {
        Prefs p = new Prefs(c);
        String token = p.devToken().trim();
        if (token.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force && now - p.devAt() < 20 * 3600_000L) return;
        String status;
        try {
            String r = Times.get("https://developer.lge.com/secure/ResetDevModeSession.dev?sessionToken="
                + java.net.URLEncoder.encode(token, "UTF-8"));
            JSONObject j = null;
            try { j = new JSONObject(r); } catch (Exception ignored) {}
            boolean ok = j != null ? "200".equals(j.optString("result")) || j.optBoolean("success") || "success".equalsIgnoreCase(j.optString("result")) : r.contains("200");
            status = (ok ? "تم التمديد " : "رد غير متوقع ") + Times.clock(now) + (ok ? "" : " · " + r.trim().replaceAll("\\s+", " ").substring(0, Math.min(80, r.trim().length())));
        } catch (Exception e) {
            status = "فشل التمديد " + Times.clock(now) + " · " + e.getMessage();
        }
        p.edit().putLong("devAt", now).putString("devStatus", status).apply();
    }
}
