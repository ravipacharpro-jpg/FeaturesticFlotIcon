package com.featurestic.loader.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Parsed view of the native shock Response struct (jni/INCLUDE/struct.h).
 * Layout (ARM64 default alignment):
 *   bool Success@0, bool InLobby@1, int PlayerCount@4, VehicleCount@8,
 *   ItemsCount@12, BoxItemsCount@16, GrenadeCount@20, ZoneCount@24,
 *   float fov@28, flags@32, int HackpubgV1@36,
 *   PlayerData Players[100]@40 (each 500 bytes),
 *   VehicleData[50]@50040 (84), ItemData[400]@54240 (68),
 *   Grenade[10]@81440 (20), Zone[10]@81640 (16), BoxItem[10]@81800 (220).
 */
public final class EspSnapshot {

    public boolean success;
    public boolean inLobby;
    public int playerCount, vehicleCount, itemsCount, boxItemsCount, grenadeCount, zoneCount;
    public float fov;

    public static final class Player {
        public String name, nation, uid;
        public int teamId, states, status;
        public float health, healthy, distance;
        public boolean bot, visible;
        public float headX, headY, headZ;
        public float radarX, radarY;
        public int weaponId, ammo;
        public Player(ByteBuffer b, int off) {
            name = readC(b, off, 100);
            nation = readC(b, off + 100, 100);
            uid = readC(b, off + 200, 100);
            teamId = b.getInt(off + 300);
            states = b.getInt(off + 304);
            health = b.getFloat(off + 308);
            healthy = b.getFloat(off + 312);
            distance = b.getFloat(off + 316);
            bot = b.get(off + 320) != 0;
            visible = b.get(off + 321) != 0;
            headX = b.getFloat(off + 340);
            headY = b.getFloat(off + 344);
            headZ = b.getFloat(off + 348);
            radarX = b.getFloat(off + 352);
            radarY = b.getFloat(off + 356);
            weaponId = b.getInt(off + 364);
            ammo = b.getInt(off + 368);
            status = b.getInt(off + 496);
        }
    }

    public Player[] players = new Player[0];

    public static EspSnapshot parse(byte[] payload) {
        EspSnapshot s = new EspSnapshot();
        if (payload == null || payload.length < 40) return s;
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        s.success = b.get(0) != 0;
        s.inLobby = b.get(1) != 0;
        s.playerCount = b.getInt(4);
        s.vehicleCount = b.getInt(8);
        s.itemsCount = b.getInt(12);
        s.boxItemsCount = b.getInt(16);
        s.grenadeCount = b.getInt(20);
        s.zoneCount = b.getInt(24);
        s.fov = b.getFloat(28);

        int n = Math.max(0, Math.min(s.playerCount, 100));
        s.players = new Player[n];
        for (int i = 0; i < n; i++) {
            s.players[i] = new Player(b, 40 + i * 500);
        }
        return s;
    }

    private static String readC(ByteBuffer b, int off, int max) {
        int len = 0;
        while (len < max && off + len < b.limit() && b.get(off + len) != 0) len++;
        byte[] tmp = new byte[len];
        for (int i = 0; i < len; i++) tmp[i] = b.get(off + i);
        return new String(tmp, StandardCharsets.UTF_8);
    }
}
