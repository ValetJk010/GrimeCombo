package com.grime.combo;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;

public class TpsTracker {
    private static final int MAX_SAMPLES = 40;
    private static final Deque<Long> tickTimes = new ArrayDeque<>();
    private static float lastMeasuredTps = 20.0f;

    public static void onTick(Minecraft client) {
        if (client == null || client.level == null) {
            tickTimes.clear();
            lastMeasuredTps = 20.0f;
            return;
        }

        long now = System.nanoTime();
        tickTimes.addLast(now);

        while (tickTimes.size() > MAX_SAMPLES) {
            tickTimes.removeFirst();
        }

        if (tickTimes.size() >= 10) {
            long first = tickTimes.peekFirst();
            long last = tickTimes.peekLast();
            long elapsedNano = last - first;
            if (elapsedNano > 0) {
                double seconds = elapsedNano / 1_000_000_000.0;
                double tps = (tickTimes.size() - 1) / seconds;
                lastMeasuredTps = (float) Math.max(1.0, Math.min(100.0, tps));
            }
        }
    }

    public static float getTps(Minecraft client) {
        if (client == null || client.level == null) return 20.0f;

        float targetTps = 20.0f;
        try {
            if (client.level.tickRateManager() != null) {
                targetTps = client.level.tickRateManager().tickrate();
            }
        } catch (Throwable ignored) {}

        if (lastMeasuredTps > 0.5f) {
            return Math.min(targetTps, lastMeasuredTps);
        }
        return targetTps;
    }

    /**
     * Возвращает коэффициент масштабирования времени для компенсирования TPS.
     * При нормальном TPS=20 -> 1.0.
     * При лагающем TPS=10 -> 2.0 (паузы увеличиваются в 2 раза).
     * При ускоренном TPS=40 -> 0.5 (паузы уменьшаются в 2 раза).
     */
    public static float getTpsScale(Minecraft client) {
        float tps = getTps(client);
        if (tps <= 0.1f) return 1.0f;
        return 20.0f / tps;
    }
}
