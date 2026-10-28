package de.darfarah.urtvremote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The mosque's year timetable from Mawaqit (the same one the TV shows), kept in a file for days without internet. */
final class Times {
    private Times() {}

    static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] COL = {0, 2, 3, 4, 5};   // fajr, (shuruq), dhuhr, asr, maghrib, isha

    private static File file(Context c) { return new File(c.getFilesDir(), "times.json"); }

    static JSONObject read(Context c) {
        try {
            File f = file(c);
            if (!f.isFile()) return null;
            return new JSONObject(new String(readAll(new java.io.FileInputStream(f)), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Blocking (network): fetch when the mosque changed or the file is older than 20 hours. */
    static synchronized void refresh(Context c, boolean force) {
        Prefs p = new Prefs(c);
        String slug = p.mosque();
        JSONObject cur = read(c);
        long now = System.currentTimeMillis();
        if (!force && cur != null && slug.equals(cur.optString("mosque")) && now - cur.optLong("at") < 20 * 3600_000L) return;
        try {
            String html = get("https://mawaqit.net/en/" + slug);
            Matcher m = Pattern.compile("confData\\s*=\\s*(\\{.*?\\});\\s*\\n", Pattern.DOTALL).matcher(html);
            if (!m.find()) return;
            JSONArray cal = new JSONObject(m.group(1)).optJSONArray("calendar");
            if (cal == null || cal.length() != 12) return;
            JSONObject j = new JSONObject().put("mosque", slug).put("at", now).put("calendar", cal);
            File tmp = new File(c.getFilesDir(), "times.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(j.toString().getBytes("UTF-8")); } finally { o.close(); }
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
            // keep the old file: its timetable still holds
        }
    }

    /** {time in ms, prayer 0-4} of the first adhan after `after` (3 days ahead at most); null if none known */
    static long[] next(JSONObject j, long after, boolean fajr) {
        JSONArray cal = j == null ? null : j.optJSONArray("calendar");
        if (cal == null) return null;
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(after);
        for (int k = 0; k < 3; k++) {
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_MONTH, k);
            JSONObject month = cal.optJSONObject(d.get(Calendar.MONTH));
            JSONArray day = month == null ? null : month.optJSONArray(String.valueOf(d.get(Calendar.DAY_OF_MONTH)));
            if (day == null || day.length() < 6) continue;
            for (int i = 0; i < 5; i++) {
                if (i == 0 && !fajr) continue;
                int[] hm = hm(day.optString(COL[i], ""));
                if (hm == null) continue;
                Calendar a = (Calendar) d.clone();
                a.set(Calendar.HOUR_OF_DAY, hm[0]);
                a.set(Calendar.MINUTE, hm[1]);
                a.set(Calendar.SECOND, 0);
                a.set(Calendar.MILLISECOND, 0);
                if (a.getTimeInMillis() > after) return new long[] {a.getTimeInMillis(), i};
            }
        }
        return null;
    }

    static int[] hm(String s) {
        Matcher m = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})").matcher(s == null ? "" : s);
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
        return h < 24 && mi < 60 ? new int[] {h, mi} : null;
    }

    /** "٣:٤٩ م" */
    static String clock(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY), mi = c.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return arabic(h12 + ":" + (mi < 10 ? "0" : "") + mi) + (h < 12 ? " ص" : " م");
    }

    static String arabic(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return b.toString();
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) UrTVRemote");
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            return new String(readAll(con.getInputStream()), "UTF-8");
        } finally {
            con.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }
}
