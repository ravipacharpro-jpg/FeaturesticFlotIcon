package com.featurestic.loader.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bridge between the Java floating menu and the native "shock" engine (socu64).
 *
 * Protocol (matches jni/INCLUDE/socket.h + struct.h of the shock project):
 *   - abstract AF_UNIX socket name: "\0XARG"  -> LocalServerSocket("XARG")
 *   - every packet = 4-byte big-endian length + payload
 *   - client -> server : Response (ESP data)   [read by Java overlay]
 *   - server -> client : Request  (settings)   [192 bytes, native layout]
 *
 * Native struct layout (ARM64, default alignment) used below:
 *   Options  = 132 bytes, OtherFeature = 28 bytes, Request = 192 bytes.
 *
 * NOTE: this class never modifies the native library. The native side keeps
 * its BT-bypass (0x70F26E8 / 0x70F20CC), anti-cheat fixes and 4.5 flow intact.
 */
public final class ShockBridge {

    public static final String TAG = "ShockBridge";
    public static final String SOCKET_NAME = "XARG";
    public static final String PREFS = "featurestic_mod_prefs";

    public static final int MODE_INIT = 1;
    public static final int MODE_ESP = 2;
    public static final int MODE_HACK = 3;
    public static final int MODE_STOP = 4;

    private static final ShockBridge INSTANCE = new ShockBridge();
    private ShockBridge() {}

    public static ShockBridge get() { return INSTANCE; }

    @FunctionalInterface
    public interface ResponseListener {
        void onResponse(EspSnapshot snapshot);
        default void onError(String message) {}
    }

    private volatile boolean running = false;
    private LocalServerSocket server;
    private LocalSocket client;
    private DataOutputStream out;
    private DataInputStream in;
    private Thread acceptThread;
    private volatile ResponseListener listener;
    private volatile EspSnapshot lastSnapshot = new EspSnapshot();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    public void start(Context ctx, ResponseListener l) {
        this.listener = l;
        if (running) return;
        running = true;
        acceptThread = new Thread(() -> {
            try {
                server = new LocalServerSocket(SOCKET_NAME);
                Log.i(TAG, "listening on abstract socket \\0" + SOCKET_NAME);
                while (running) {
                    try {
                        client = server.accept();
                        out = new DataOutputStream(client.getOutputStream());
                        in = new DataInputStream(client.getInputStream());
                        Log.i(TAG, "native engine connected");
                        readLoop();
                    } catch (IOException e) {
                        if (running) Log.w(TAG, "accept/read failed: " + e.getMessage());
                    } finally {
                        closeClient();
                    }
                    if (!running) break;
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } catch (IOException e) {
                Log.e(TAG, "cannot open socket: " + e.getMessage());
                running = false;
            }
        }, "shock-socket");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        closeClient();
        if (server != null) { try { server.close(); } catch (IOException ignored) {} server = null; }
        if (acceptThread != null) { acceptThread.interrupt(); acceptThread = null; }
    }

    private void closeClient() {
        if (out != null) { try { out.close(); } catch (IOException ignored) {} out = null; }
        if (in != null) { try { in.close(); } catch (IOException ignored) {} in = null; }
        if (client != null) { try { client.close(); } catch (IOException ignored) {} client = null; }
    }

    public boolean isConnected() { return client != null && out != null; }

    private void readLoop() throws IOException {
        while (running && in != null) {
            int len = in.readInt();                 // network byte order (big endian)
            if (len <= 0 || len > 1_000_000) throw new IOException("bad packet length " + len);
            byte[] payload = new byte[len];
            in.readFully(payload);
            EspSnapshot snap = EspSnapshot.parse(payload);
            lastSnapshot = snap;
            if (listener != null) listener.onResponse(snap);
        }
    }

    // ------------------------------------------------------------------
    // Sending settings
    // ------------------------------------------------------------------
    /** Build a 192-byte Request (native layout) from the floating-menu prefs. */
    public synchronized boolean sendRequest(int mode, SharedPreferences p, int width, int height) {
        if (out == null) { Log.w(TAG, "not connected, request dropped"); return false; }
        try {
            byte[] req = buildRequest(mode, p, width, height);
            out.writeInt(req.length);               // length-prefixed, big endian
            out.write(req);
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "send failed: " + e.getMessage());
            return false;
        }
    }

    /** Convenience: send INIT + current prefs. */
    public boolean applySettings(Context ctx, int width, int height) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sendRequest(MODE_INIT, p, width, height);
    }

    public void sendStop() {
        if (out == null) return;
        try {
            byte[] req = new byte[192];
            ByteBuffer b = ByteBuffer.wrap(req).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0, MODE_STOP);
            out.writeInt(req.length); out.write(req); out.flush();
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------
    // Exact native struct offsets (see struct.h of shock project)
    // ------------------------------------------------------------------
    // Options @ 4
    private static final int OPTION_BASE = 4;
    private static final int OPTION_AIMBOTMODE = OPTION_BASE + 0;    // int
    private static final int OPTION_OPENSTATE = OPTION_BASE + 4;     // int  (0 => aimbot on)
    private static final int OPTION_AIMT = OPTION_BASE + 8;          // int  (0 => touch aim on)
    private static final int OPTION_AIMINGSTATE = OPTION_BASE + 12;  // int
    private static final int OPTION_PRIORITY = OPTION_BASE + 20;     // int
    private static final int OPTION_AIMINGRANGE = OPTION_BASE + 28;  // int
    private static final int OPTION_AIMINGDIST = OPTION_BASE + 32;   // int
    private static final int OPTION_AIMINGSPEED = OPTION_BASE + 36;  // int
    private static final int OPTION_TOUCHSPEED = OPTION_BASE + 40;   // int
    private static final int OPTION_RECCOMPE = OPTION_BASE + 44;     // int
    private static final int OPTION_AIMBULLET = OPTION_BASE + 48;    // int  (0 => gEnable)
    private static final int OPTION_SMOOTHING = OPTION_BASE + 56;    // float
    private static final int OPTION_TOUCHSIZE = OPTION_BASE + 64;    // int
    private static final int OPTION_RECSCOPE = OPTION_BASE + 84;     // float[9]
    private static final int OPTION_CUSTOMSCOPE = OPTION_BASE + 120; // bool
    private static final int OPTION_PREDICTION = OPTION_BASE + 121;  // bool
    private static final int OPTION_HACKPUBGV1 = OPTION_BASE + 128;  // int

    // OtherFeature @ 136
    private static final int OTHER_BASE = 136;
    private static final int OTHER_SMALLCROSSHAIR = OTHER_BASE + 0;  // bool
    private static final int OTHER_WIDEVIEW = OTHER_BASE + 4;        // int
    private static final int OTHER_SHOWDAMAGE = OTHER_BASE + 8;      // bool
    private static final int OTHER_CLOTHES = OTHER_BASE + 16;        // int
    private static final int OTHER_BAG = OTHER_BASE + 20;            // int
    private static final int OTHER_HELMET = OTHER_BASE + 24;         // int

    // Request tail
    private static final int REQ_SCREENW = 164;
    private static final int REQ_SCREENH = 168;
    private static final int REQ_RADARX = 172;
    private static final int REQ_RADARY = 176;
    private static final int REQ_RADARSIZE = 180;

    private static byte[] buildRequest(int mode, SharedPreferences p, int width, int height) {
        byte[] buf = new byte[192];
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, mode);

        // ---- AIM tab -> native aim options ----
        boolean aimAssist = p.getBoolean("aim_assist", true);
        boolean recoil = p.getBoolean("recoil_control", true);
        boolean bulletTrack = p.getBoolean("bullet_track", false);
        boolean quickScope = p.getBoolean("quick_scope", true);
        boolean fireSens = p.getBoolean("fire_sens", true);
        boolean crosshairLock = p.getBoolean("crosshair_lock", false);

        b.putInt(OPTION_AIMBOTMODE, aimAssist ? 1 : 0);
        b.putInt(OPTION_OPENSTATE, aimAssist ? 0 : 1);   // C++: aimbot = openState == 0
        b.putInt(OPTION_AIMT, crosshairLock ? 0 : 1);    // C++: aimTouch = aimT == 0
        b.putInt(OPTION_AIMINGSTATE, crosshairLock ? 2 : 1);
        b.putInt(OPTION_PRIORITY, 1);
        b.putInt(OPTION_AIMINGRANGE, 200);
        b.putInt(OPTION_AIMINGDIST, 50);
        b.putInt(OPTION_AIMINGSPEED, fireSens ? 660 : 330);
        b.putInt(OPTION_TOUCHSPEED, fireSens ? 660 : 330);
        b.putInt(OPTION_RECCOMPE, recoil ? 100 : 0);
        b.putInt(OPTION_AIMBULLET, bulletTrack ? 0 : 1); // C++: gEnable = aimBullet == 0
        b.putFloat(OPTION_SMOOTHING, 1.0f);
        b.putInt(OPTION_TOUCHSIZE, 25);
        b.put(OPTION_CUSTOMSCOPE, (byte) (quickScope ? 1 : 0));
        b.put(OPTION_PREDICTION, (byte) 1);
        b.putInt(OPTION_HACKPUBGV1, 4);

        // ---- VISUALS / WEAPONS / ITEMS tabs -> otherFeature flags ----
        b.put(OTHER_SMALLCROSSHAIR, (byte) (p.getBoolean("esp_player_line", true) ? 1 : 0));
        b.put(OTHER_SHOWDAMAGE, (byte) (p.getBoolean("esp_player_health", true) ? 1 : 0));
        b.putInt(OTHER_WIDEVIEW, 0);
        b.putInt(OTHER_CLOTHES, p.getBoolean("esp_loot_helmet3", true) ? 1 : 0);
        b.putInt(OTHER_BAG, p.getBoolean("esp_loot_bag3", true) ? 1 : 0);
        b.putInt(OTHER_HELMET, p.getBoolean("esp_loot_vest3", true) ? 1 : 0);

        b.putInt(REQ_SCREENW, width);
        b.putInt(REQ_SCREENH, height);
        b.putFloat(REQ_RADARX, 0.0f);
        b.putFloat(REQ_RADARY, 0.0f);
        b.putFloat(REQ_RADARSIZE, 80.0f);
        return buf;
    }

    public EspSnapshot getLastSnapshot() { return lastSnapshot; }
}
