package de.darfarah.urtvremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The LG TV's own remote-control API (SSAP over a WebSocket, the one LG ThinQ uses): wss://TV:3001, or ws://TV:3000
 * on older sets. The TV asks once on its screen to allow this phone; the key it gives is kept.
 * Blocking calls: use from a background thread.
 */
final class Tv {
    static final String APP_ID = "com.darfarah.urtv";

    private OkHttpClient http;
    private WebSocket ws;
    private final Map<String, JSONObject> got = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile boolean open, failed;
    private int nextId = 1;

    // the TV signs its own certificate: on the home network, trust it
    private static OkHttpClient client() throws Exception {
        final X509TrustManager all = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {all}, new SecureRandom());
        return new OkHttpClient.Builder()
            .sslSocketFactory(ssl.getSocketFactory(), all)
            .hostnameVerifier((h, s) -> true)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    }

    /** true when a socket to the TV is open */
    boolean connect(String ip) {
        close();
        try { if (http == null) http = client(); } catch (Exception e) { return false; }
        for (String url : new String[] {"wss://" + ip + ":3001", "ws://" + ip + ":3000"}) {
            open = false; failed = false;
            ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response r) { open = true; synchronized (got) { got.notifyAll(); } }
                @Override public void onMessage(WebSocket w, String text) {
                    try {
                        JSONObject j = new JSONObject(text);
                        synchronized (got) { got.put(j.optString("id") + "|" + seq.incrementAndGet(), j); got.notifyAll(); }
                    } catch (Exception ignored) {}
                }
                @Override public void onFailure(WebSocket w, Throwable t, Response r) { failed = true; open = false; synchronized (got) { got.notifyAll(); } }
                @Override public void onClosed(WebSocket w, int code, String reason) { open = false; synchronized (got) { got.notifyAll(); } }
            });
            long end = System.currentTimeMillis() + 6000;
            synchronized (got) {
                while (!open && !failed && System.currentTimeMillis() < end) {
                    try { got.wait(300); } catch (InterruptedException e) { return false; }
                }
            }
            if (open) return true;
            ws.cancel();
        }
        return false;
    }

    boolean isOpen() { return open; }

    void close() {
        if (ws != null) { try { ws.close(1000, null); } catch (Exception ignored) {} ws = null; }
        open = false;
        synchronized (got) { got.clear(); }
    }

    /** the next message with this id, waiting up to `ms`; null on timeout or a closed socket */
    private JSONObject await(String id, long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (got) {
            while (true) {
                for (Map.Entry<String, JSONObject> e : got.entrySet()) {
                    if (e.getKey().startsWith(id + "|")) { got.remove(e.getKey()); return e.getValue(); }
                }
                long left = end - System.currentTimeMillis();
                if (left <= 0 || (!open && got.isEmpty())) return null;
                try { got.wait(Math.min(left, 500)); } catch (InterruptedException e) { return null; }
            }
        }
    }

    /**
     * Register with the stored key. With allowPrompt the TV may ask «allow?» on its screen (up to 60 s);
     * returns the key (new or the same), or null.
     */
    String register(String key, boolean allowPrompt) {
        try {
            JSONObject payload = new JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put("manifest", new JSONObject()
                    .put("appVersion", "1.1")
                    .put("manifestVersion", 1)
                    .put("permissions", new JSONArray(PERMISSIONS)));
            if (key != null && !key.isEmpty()) payload.put("client-key", key);
            String id = "register_" + (nextId++);
            ws.send(new JSONObject().put("type", "register").put("id", id).put("payload", payload).toString());
            long end = System.currentTimeMillis() + (allowPrompt ? 60_000 : 8_000);
            while (System.currentTimeMillis() < end) {
                JSONObject r = await(id, end - System.currentTimeMillis());
                if (r == null) return null;
                String type = r.optString("type");
                if ("registered".equals(type)) return r.optJSONObject("payload").optString("client-key", key);
                if ("error".equals(type)) return null;
                // "response" with pairingType PROMPT: the TV is asking; wait for the answer
                if (!allowPrompt) return null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** ssap://uri; the answer's payload, or null */
    JSONObject request(String uri, JSONObject payload) {
        try {
            String id = "r" + (nextId++);
            JSONObject m = new JSONObject().put("id", id).put("type", "request").put("uri", "ssap://" + uri);
            if (payload != null) m.put("payload", payload);
            if (ws == null || !ws.send(m.toString())) return null;
            JSONObject r = await(id, 8000);
            if (r == null || !"response".equals(r.optString("type"))) return null;
            return r.optJSONObject("payload");
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- what the adhan needs

    /** "Active", "Screen Off", "Active Standby", "Suspend", "Power Off"; "" when unknown */
    String powerState() {
        JSONObject p = request("com.webos.service.tvpower/power/getPowerState", null);
        return p == null ? "" : p.optString("state", "");
    }

    static boolean isOn(String state) {
        return "Active".equals(state) || state.isEmpty();
    }

    String foregroundApp() {
        JSONObject p = request("com.webos.applicationManager/getForegroundAppInfo", null);
        return p == null ? "" : p.optString("appId", "");
    }

    /** {volume, muted 0/1}; null when unknown */
    int[] volume() {
        JSONObject p = request("audio/getVolume", null);
        if (p == null) return null;
        JSONObject s = p.optJSONObject("volumeStatus");   // webOS 22+
        if (s != null) return new int[] {s.optInt("volume", -1), s.optBoolean("muteStatus", false) ? 1 : 0};
        return new int[] {p.optInt("volume", -1), p.optBoolean("muted", false) ? 1 : 0};
    }

    boolean setVolume(int v) {
        try { return request("audio/setVolume", new JSONObject().put("volume", v)) != null; } catch (Exception e) { return false; }
    }

    boolean setMute(boolean m) {
        try { return request("audio/setMute", new JSONObject().put("mute", m)) != null; } catch (Exception e) { return false; }
    }

    boolean launch(String app, JSONObject params) {
        try {
            JSONObject p = new JSONObject().put("id", app);
            if (params != null) p.put("params", params);
            return request("system.launcher/launch", p) != null;
        } catch (Exception e) {
            return false;
        }
    }

    void turnOnScreen() { request("com.webos.service.tvpower/power/turnOnScreen", new JSONObject()); }
    void turnOn() { request("system/turnOn", null); }
    void turnOff() { request("system/turnOff", null); }

    void toast(String text) {
        try { request("system.notifications/createToast", new JSONObject().put("message", text)); } catch (Exception ignored) {}
    }

    /** the wired (else wireless) MAC address, for waking it; "" when unknown */
    String mac() {
        JSONObject p = request("com.webos.service.connectionmanager/getinfo", null);
        if (p == null) return "";
        for (String k : new String[] {"wiredInfo", "wifiInfo"}) {
            JSONObject i = p.optJSONObject(k);
            if (i != null && i.optString("macAddress").matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}")) return i.optString("macAddress");
        }
        return "";
    }

    /** Wake-on-LAN: the magic packet to the whole network and to the TV (needs «turn on via Wi-Fi» on the TV) */
    static void wake(String mac, String ip) {
        if (mac == null || mac.isEmpty()) return;
        try {
            String[] hex = mac.split("[:-]");
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex[i], 16);
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
            for (int i = 6; i < pkt.length; i += 6) System.arraycopy(m, 0, pkt, i, 6);
            String bcast = ip.substring(0, ip.lastIndexOf('.')) + ".255";
            DatagramSocket s = new DatagramSocket();
            try {
                s.setBroadcast(true);
                for (String to : new String[] {"255.255.255.255", bcast, ip}) {
                    for (int port : new int[] {9, 7}) {
                        try { s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(to), port)); } catch (Exception ignored) {}
                    }
                }
            } finally {
                s.close();
            }
        } catch (Exception ignored) {}
    }

    private static final String[] PERMISSIONS = {
        "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV",
        "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP",
        "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS",
        "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE",
        "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO",
        "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT",
        "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
    };
}
