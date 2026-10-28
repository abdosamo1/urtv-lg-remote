package de.darfarah.urtvremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** One screen: the TV, the mosque, the adhan options, Developer Mode, and what happened last time. */
public class MainActivity extends Activity {
    private static final int GOLD = 0xFFD6AF6B, BG = 0xFF14110C, CARD = 0xFF211C14, TEXT = 0xFFF5EFE0, DIM = 0xFFB9AE98;

    private Prefs p;
    private LinearLayout root;
    private TextView status, last;
    private EditText ip, mac, mosque, volume, minutes, token;
    private Switch adhanOn, fajr, offAfter, backToApp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, dp(40));
        sv.addView(root);
        setContentView(sv);

        TextView title = text("ريموت تلفزيونك", 26, GOLD);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("يشغّل الشاشة وقت الأذان، يرفع الصوت، ويفتح الأذان على تلفزيونك", 14, DIM));

        status = text("", 17, TEXT);
        card(status);

        header("الشاشة");
        ip = field("عنوان الشاشة (IP)", p.ip(), InputType.TYPE_CLASS_TEXT);
        mac = field("عنوان MAC (يتعبّى لحاله بعد الاقتران)", p.mac(), InputType.TYPE_CLASS_TEXT);
        button("اقتران مع الشاشة", v -> pair());

        header("الجامع");
        mosque = field("رابط الجامع على mawaqit.net", "https://mawaqit.net/de/" + p.mosque(), InputType.TYPE_TEXT_VARIATION_URI);

        header("الأذان");
        adhanOn = toggle("تشغيل الشاشة للأذان", p.adhanOn());
        fajr = toggle("أذان الفجر (شغّله كمان بإعدادات الشاشة)", p.fajr());
        volume = field("مستوى الصوت وقت الأذان", String.valueOf(p.volume()), InputType.TYPE_CLASS_NUMBER);
        minutes = field("مدة الأذان بالدقائق (بعدها يرجع كل شي)", String.valueOf(p.minutes()), InputType.TYPE_CLASS_NUMBER);
        offAfter = toggle("طفّي الشاشة بعد الأذان إذا كانت مطفية", p.offAfter());
        backToApp = toggle("رجّع التطبيق اللي كان شغّال", p.backToApp());

        header("وضع المطوّر (تمديد تلقائي كل يوم)");
        token = field("رمز الجلسة من ملف get-token.bat", p.devToken(), InputType.TYPE_CLASS_TEXT);

        button("حفظ", v -> { save(); toast("انحفظ"); });
        button("جرّب الآن (يشغّل الشاشة والأذان)", v -> { save(); test(); });
        button("استثناء من توفير البطارية", v -> battery());

        header("آخر مرة");
        last = text("", 14, DIM);
        card(last);
    }

    @Override protected void onResume() {
        super.onResume();
        Alarms.update(this, false);
        ui.postDelayed(this::refresh, 300);
        ui.postDelayed(this::refresh, 4000);
    }

    private void refresh() {
        String paired = p.clientKey().isEmpty() ? "❌ غير مقترن" : "✅ مقترن";
        String dev = p.devToken().isEmpty() ? "بدون تمديد تلقائي" : (p.devStatus().isEmpty() ? "ما صار تمديد بعد" : p.devStatus());
        status.setText("الأذان القادم: " + p.nextAdhan() + "\nالشاشة: " + paired + "\nوضع المطوّر: " + dev);
        last.setText(p.lastRun().isEmpty() ? "—" : p.lastRun());
        mac.setText(p.mac());
    }

    private void save() {
        String slug = Prefs.mosqueSlug(mosque.getText().toString());
        if (slug == null) { toast("رابط الجامع غلط"); slug = p.mosque(); }
        boolean mosqueChanged = !slug.equals(p.mosque());
        p.edit()
            .putString("ip", ip.getText().toString().trim())
            .putString("mac", mac.getText().toString().trim())
            .putString("mosque", slug)
            .putBoolean("adhanOn", adhanOn.isChecked())
            .putBoolean("fajr", fajr.isChecked())
            .putInt("volume", clamp(volume.getText().toString(), 10, 0, 100))
            .putInt("minutes", clamp(minutes.getText().toString(), 5, 1, 15))
            .putBoolean("offAfter", offAfter.isChecked())
            .putBoolean("backToApp", backToApp.isChecked())
            .putString("devToken", token.getText().toString().trim())
            .apply();
        Alarms.update(this, mosqueChanged);
        ui.postDelayed(this::refresh, 3000);
    }

    /** the TV asks once «allow this phone?»; the key and the MAC address are kept */
    private void pair() {
        save();
        toast("اقبل الطلب اللي رح يطلع على الشاشة…");
        final String host = p.ip();
        new Thread(() -> {
            Tv tv = new Tv();
            String msg;
            if (!tv.connect(host)) msg = "ما وصلت للشاشة على " + host + " (شغّالة؟ نفس الشبكة؟)";
            else {
                String key = tv.register(p.clientKey(), true);
                if (key == null || key.isEmpty()) msg = "ما انقبل الاقتران";
                else {
                    String m = tv.mac();
                    p.edit().putString("clientKey", key).putString("mac", m.isEmpty() ? p.mac() : m).apply();
                    tv.toast("ريموت تلفزيونك: تم الاقتران ✓");
                    msg = "تم الاقتران ✓" + (m.isEmpty() ? " (ما وصلني MAC، اكتبه بإيدك)" : "");
                }
            }
            tv.close();
            final String shown = msg;
            ui.post(() -> { toast(shown); refresh(); });
        }).start();
    }

    private void test() {
        Intent s = new Intent(this, AdhanService.class).putExtra("prayer", -1).putExtra("test", true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        toast("عم جرّب… شوف «آخر مرة» تحت");
        for (int i = 1; i <= 12; i++) ui.postDelayed(this::refresh, i * 5000L);
    }

    private void battery() {
        if (Build.VERSION.SDK_INT < 23) { toast("ما في داعي على هالأندرويد"); return; }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) { toast("مستثنى من قبل ✓"); return; }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------------------------------------------------------------- views

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static int clamp(String s, int def, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim()))); } catch (Exception e) { return def; }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void header(String s) {
        TextView t = text(s, 15, GOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        root.addView(t);
    }

    private void card(View v) {
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        root.addView(v, lp);
    }

    private EditText field(String label, String value, int type) {
        root.addView(text(label, 13, DIM));
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setTextColor(TEXT);
        e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        root.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Switch toggle(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(15);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(10));
        root.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFF1B1408);
        b.setTextSize(16);
        GradientDrawable g = new GradientDrawable();
        g.setColor(GOLD);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(12);
        root.addView(b, lp);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
