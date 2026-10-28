package de.darfarah.urtvremote;

import android.content.Context;
import android.content.SharedPreferences;

/** What the owner picks in the app. */
final class Prefs {
    static final String DEFAULT_IP = "192.168.2.209";
    static final String DEFAULT_MOSQUE = "masjid-tawasul-kirchheim-unter-teck-73230-germany";

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    String ip() { return sp.getString("ip", DEFAULT_IP); }
    String mac() { return sp.getString("mac", ""); }
    String clientKey() { return sp.getString("clientKey", ""); }
    String mosque() { return sp.getString("mosque", DEFAULT_MOSQUE); }
    boolean adhanOn() { return sp.getBoolean("adhanOn", true); }
    boolean fajr() { return sp.getBoolean("fajr", false); }
    int volume() { return sp.getInt("volume", 10); }
    boolean offAfter() { return sp.getBoolean("offAfter", true); }
    boolean backToApp() { return sp.getBoolean("backToApp", true); }
    int minutes() { return sp.getInt("minutes", 5); }
    String devToken() { return sp.getString("devToken", ""); }

    String nextAdhan() { return sp.getString("nextAdhan", ""); }
    String lastRun() { return sp.getString("lastRun", ""); }
    String devStatus() { return sp.getString("devStatus", ""); }
    long devAt() { return sp.getLong("devAt", 0); }

    SharedPreferences.Editor edit() { return sp.edit(); }

    /** "https://mawaqit.net/de/xyz?x" or "xyz" -> "xyz"; null when it is neither */
    static String mosqueSlug(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("mawaqit\\.net/(?:[a-z]{2}(?:-[A-Za-z]{2})?/)?([A-Za-z0-9_-]+)").matcher(s);
        if (m.find()) s = m.group(1);
        return s.matches("[A-Za-z0-9_-]{3,160}") ? s : null;
    }
}
