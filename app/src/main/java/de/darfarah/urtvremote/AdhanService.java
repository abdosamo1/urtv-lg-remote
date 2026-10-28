package de.darfarah.urtvremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * At the adhan: wake the TV (Wake-on-LAN) if it is off, unmute it and set the volume, open تلفزيونك with
 * {"adhan":"now"} over whatever runs (it plays the adhan), then after it: volume back, and the TV off again if it
 * was off, or the app that was on brought back.
 */
public class AdhanService extends Service {
    private static final String CHANNEL = "adhan";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final int prayer = intent == null ? -1 : intent.getIntExtra("prayer", -1);
        final boolean test = intent != null && intent.getBooleanExtra("test", false);
        startForeground(1, note(test ? "تجربة الأذان على الشاشة…" : "الأذان على الشاشة…"));
        new Thread(() -> {
            PowerManager.WakeLock wl = null;
            WifiManager.WifiLock wifi = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "urtvremote:adhan");
                wl.acquire(15 * 60_000L);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "urtvremote:adhan"); wifi.acquire(); }
                run(prayer, test);
            } catch (Throwable t) {
                log("خطأ: " + t);
            } finally {
                if (wifi != null && wifi.isHeld()) wifi.release();
                if (wl != null && wl.isHeld()) wl.release();
                if (!test) Alarms.update(getApplicationContext(), false);   // the next adhan
                stopForeground(true);
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    private final StringBuilder logText = new StringBuilder();

    private void log(String line) {
        if (logText.length() > 0) logText.append('\n');
        logText.append(Times.clock(System.currentTimeMillis())).append("  ").append(line);
        new Prefs(this).edit().putString("lastRun", logText.toString()).apply();
    }

    private void run(int prayer, boolean test) throws Exception {
        Prefs p = new Prefs(this);
        String ip = p.ip(), mac = p.mac(), key = p.clientKey();
        log((test ? "تجربة" : "أذان " + (prayer >= 0 ? Times.NAMES[prayer] : "")) + " · الشاشة " + ip);
        if (key.isEmpty()) { log("الموبايل مو مقترن بالشاشة: افتح التطبيق واكبس «اقتران»"); return; }

        Tv tv = new Tv();
        // 1. is it on? (with Quick Start+ the TV answers on the network even in standby, so ask its power state)
        String state = "";
        boolean reached = tv.connect(ip) && tv.register(key, false) != null;
        if (reached) state = tv.powerState();
        boolean wasOff = !reached || !really(tv, state);
        log(reached ? "الشاشة ترد · الحالة: " + (state.isEmpty() ? "؟" : state) : "الشاشة ما ترد (مطفية؟)");

        // 2. wake it: the magic packet every 3 s, until the TV says «Active» (90 s at most)
        if (wasOff) {
            long end = System.currentTimeMillis() + 90_000;
            boolean on = false;
            String last = state;
            int tries = 0;
            while (System.currentTimeMillis() < end && !on) {
                Tv.wake(mac, ip);
                if (reached && tv.isOpen()) {
                    if ("Screen Off".equals(state)) tv.turnOnScreen(); else tv.turnOn();
                }
                tries++;
                Thread.sleep(3000);
                if (!reached || !tv.isOpen()) reached = tv.connect(ip) && tv.register(key, false) != null;
                if (reached) {
                    state = tv.powerState();
                    if (!state.equals(last)) { log("الحالة: " + (state.isEmpty() ? "؟" : state)); last = state; }
                    on = really(tv, state);
                }
            }
            if (!on) {
                log(mac.isEmpty() ? "ما قدرت أشغّلها: عنوان MAC ناقص (اعمل اقتران مرة تانية)"
                    : "ما قدرت أشغّلها بعد " + Times.arabic(String.valueOf(tries)) + " محاولة: تأكد من «التشغيل عبر Wi-Fi / الموبايل» بإعدادات الشاشة");
                tv.close();
                return;
            }
            log("اشتغلت الشاشة");
            Thread.sleep(5000);   // let it settle
        }

        // 3. what is on, the sound
        String before = wasOff ? "" : tv.foregroundApp();
        int[] vol = tv.volume();
        tv.setMute(false);
        tv.setVolume(p.volume());
        log("الصوت " + Times.arabic(String.valueOf(p.volume())) + (vol != null ? " (كان " + Times.arabic(String.valueOf(vol[0])) + ")" : ""));

        // 4. the adhan on تلفزيونك
        boolean launched = tv.launch(Tv.APP_ID, new JSONObject().put("adhan", test ? "test" : "now"));
        log(launched ? "فتحت تلفزيونك للأذان" : "ما قدرت أفتح تلفزيونك (هل التطبيق منزّل؟)");
        if (!launched) { restore(tv, vol); tv.close(); return; }

        // 5. wait for the adhan (or until someone switches away)
        long end = System.currentTimeMillis() + p.minutes() * 60_000L;
        String now = Tv.APP_ID;
        Thread.sleep(15_000);
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10_000);
            if (!tv.isOpen() && !(tv.connect(ip) && tv.register(key, false) != null)) continue;
            now = tv.foregroundApp();
            if (!now.isEmpty() && !Tv.APP_ID.equals(now)) break;
        }
        if (!tv.isOpen() && !(tv.connect(ip) && tv.register(key, false) != null)) { log("انقطع الاتصال بعد الأذان"); return; }
        now = tv.foregroundApp();

        // 6. after it
        restore(tv, vol);
        if (!Tv.APP_ID.equals(now) && !now.isEmpty()) { log("انتهى · غيّرت التطبيق، تركت الشاشة متل ما هي"); tv.close(); return; }
        if (wasOff && p.offAfter()) { tv.turnOff(); log("انتهى · طفيت الشاشة متل ما كانت"); }
        else if (p.backToApp() && !before.isEmpty() && !Tv.APP_ID.equals(before)) { tv.launch(before, null); log("انتهى · رجعت لـ " + before); }
        else log("انتهى");
        tv.close();
    }

    /** really on: «Active», or (a TV that doesn't say) something in the foreground */
    private static boolean really(Tv tv, String state) {
        if ("Active".equals(state)) return true;
        return state.isEmpty() && !tv.foregroundApp().isEmpty();
    }

    private void restore(Tv tv, int[] vol) {
        if (vol == null || vol[0] < 0) return;
        tv.setVolume(vol[0]);
        if (vol[1] == 1) tv.setMute(true);
    }

    private Notification note(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "الأذان", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("ريموت تلفزيونك").setContentText(text)
            .setOngoing(true).build();
    }
}
