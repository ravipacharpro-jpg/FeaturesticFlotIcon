package com.featurestic.loader.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Transparent overlay that renders the ESP data received from the shock engine.
 * Respects the floating-menu toggles from SharedPreferences "featurestic_mod_prefs".
 *
 * Draw semantics (from Main64.cpp):
 *   PlayerData.Precise (Vec4) = screen rect: x1, y1, x2, y2
 *   PlayerData.HeadLocation    = screen-space head point
 *   PlayerData.RadarLocation   = radar point
 */
public final class EspOverlay extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SharedPreferences prefs;
    private EspSnapshot snapshot = new EspSnapshot();

    public EspOverlay(Context c) {
        super(c);
        line.setColor(Color.CYAN); line.setStrokeWidth(2f); line.setStyle(Paint.Style.STROKE);
        box.setColor(Color.GREEN); box.setStrokeWidth(2f); box.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE); text.setTextSize(24f);
    }

    public void attach(SharedPreferences p) { this.prefs = p; }

    public void update(EspSnapshot s) {
        snapshot = s;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (prefs == null || snapshot == null || snapshot.players == null) return;

        boolean pLine = prefs.getBoolean("esp_player_line", true);
        boolean pBox = prefs.getBoolean("esp_player_box", true);
        boolean pHealth = prefs.getBoolean("esp_player_health", true);
        boolean pDist = prefs.getBoolean("esp_player_distance", true);
        boolean pEnemy = prefs.getBoolean("esp_enemy_count", true);

        for (EspSnapshot.Player p : snapshot.players) {
            if (p == null || p.distance <= 0) continue;
            float x1 = p.headX, y1 = p.headY;   // fallback points
            // Precise rect is read in EspSnapshot as head point; use head for line.
            if (pLine) {
                c.drawLine(getWidth()/2f, getHeight(), x1, y1, line);
            }
            if (pBox) {
                float s = Math.max(20f, 4000f / Math.max(1f, p.distance));
                c.drawRect(x1 - s/2f, y1 - s, x1 + s/2f, y1 + s, box);
            }
            if (pHealth) {
                c.drawText("HP " + (int) p.health, x1, y1 - 10f, text);
            }
            if (pDist) {
                c.drawText((int) p.distance + "m", x1, y1 + 24f, text);
            }
        }
        if (pEnemy) {
            c.drawText("Enemies: " + snapshot.playerCount, 20f, 60f, text);
        }
    }
}
