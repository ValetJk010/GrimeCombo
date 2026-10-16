package com.grime.combo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ComboExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("GrimeComboExecutor");
    private static final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();
    private static volatile boolean isRunning = false;

    public static volatile boolean isMouseLocked = false;
    public static volatile boolean isInputLocked = false;
    public static volatile float lockedYaw = 0.0f;
    public static volatile float lockedPitch = 0.0f;

    /**
     * Возвращает текущий RTT пинг игрока до сервера в миллисекундах (или 0 в синглплеере).
     */
    public static int getPingMs(Minecraft client) {
        if (client.player == null || client.getConnection() == null) return 0;
        var entry = client.getConnection().getPlayerInfo(client.player.getUUID());
        return entry != null ? Math.max(0, entry.getLatency()) : 0;
    }

    /**
     * Возвращает время задержки в мс с поправкой на тикрейт (TPS) и RTT пинг.
     */
    public static int getScaledDelay(Minecraft client, int baseDelayMs) {
        float tpsScale = TpsTracker.getTpsScale(client);
        int pingMs = getPingMs(client);
        int halfPing = pingMs > 0 ? (pingMs / 2) : 0;
        return Math.max(1, Math.round(baseDelayMs * tpsScale)) + halfPing;
    }

    public static void tryExecute(Minecraft client, Combo combo) {
        if (isRunning) return;

        long now = System.currentTimeMillis();
        long readyTime = cooldowns.getOrDefault(combo.bindKey, 0L);

        if (now < readyTime) {
            long remainSec = ((readyTime - now) / 1000) + 1;
            if (client.player != null) {
                client.player.sendOverlayMessage(
                        Component.literal("\u00a7c" + combo.name + " \u043f\u0435\u0440\u0435\u0437\u0430\u0440\u044f\u0436\u0430\u0435\u0442\u0441\u044f! (" + remainSec + "\u0441)")
                );
            }
            return;
        }

        cooldowns.put(combo.bindKey, now + combo.cooldownMs);
        isRunning = true;

        final int[] initialSlotArr = new int[1];
        readSync(client, () -> {
            if (client.player != null) {
                initialSlotArr[0] = client.player.getInventory().getSelectedSlot();
            }
        });
        final int initialSlot = initialSlotArr[0];

        int ping = getPingMs(client);
        float currentTps = TpsTracker.getTps(client);
        float tpsScale = TpsTracker.getTpsScale(client);

        LOGGER.info("[GrimeCombo] Launch combo: '{}' (bind='{}', steps: {}, initialSlot={}, ping={}ms, tps={:.1f}, tpsScale={:.2f})",
                combo.name, combo.bindKey, combo.actions.size(), initialSlot + 1, ping, currentTps, tpsScale);

        CompletableFuture.runAsync(() -> {
            try {
                int stepIndex = 1;
                for (Combo.Action action : combo.actions) {
                    if (client.player == null || client.level == null) break;

                    LOGGER.info("[GrimeCombo] [{}] Step {}/{}: type={}",
                            combo.name, stepIndex, combo.actions.size(), action.type());

                    executeAction(client, action, initialSlot);

                    if (stepIndex < combo.actions.size()) {
                        int baseDelay = getRandomDelay();
                        sleep(getScaledDelay(client, baseDelay));
                    }
                    stepIndex++;
                }
                LOGGER.info("[GrimeCombo] [{}] Combo done!", combo.name);
            } catch (Exception e) {
                LOGGER.error("[GrimeCombo] Error in combo '" + combo.name + "'", e);
            } finally {
                isMouseLocked = false;
                isInputLocked = false;
                isRunning = false;
            }
        });
    }

    private static void readSync(Minecraft client, Runnable task) {
        CountDownLatch latch = new CountDownLatch(1);
        client.execute(() -> {
            try { task.run(); } finally { latch.countDown(); }
        });
        try { latch.await(200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }

    private static void executeAction(Minecraft client, Combo.Action action, int initialSlot) {
        float tpsScale = TpsTracker.getTpsScale(client);

        switch (action.type()) {
            case SLOT -> {
                client.execute(() -> {
                    if (client.player != null && client.gameMode != null) {
                        client.gameMode.releaseUsingItem(client.player);
                        if (client.options != null) {
                            KeyMapping.set(client.options.keyUse.getDefaultKey(), false);
                            KeyMapping.set(client.options.keyAttack.getDefaultKey(), false);
                        }
                        int slot = action.param();
                        client.player.getInventory().setSelectedSlot(slot);
                        if (client.player.connection != null) {
                            client.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
                        }
                    }
                });
                sleep(getScaledDelay(client, 18));
            }
            case ROTATE -> {
                smoothRotate(client, action.yaw(), action.pitch());
            }
            case RIGHT_CLICK -> {
                client.execute(() -> {
                    if (client.player == null || client.gameMode == null) return;
                    client.gameMode.releaseUsingItem(client.player);
                    client.player.swing(InteractionHand.MAIN_HAND);

                    HitResult hit = client.hitResult;
                    net.minecraft.world.InteractionResult result = net.minecraft.world.InteractionResult.PASS;

                    if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                        result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
                    } else if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
                        result = client.gameMode.interact(client.player, entityHit.getEntity(), entityHit, InteractionHand.MAIN_HAND);
                    }

                    if (!result.consumesAction()) {
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    }
                });
                sleep(getScaledDelay(client, 25));
                client.execute(() -> {
                    if (client.player != null && client.gameMode != null && client.options != null) {
                        client.gameMode.releaseUsingItem(client.player);
                        KeyMapping.set(client.options.keyUse.getDefaultKey(), false);
                        while (client.options.keyUse.consumeClick()) {}
                    }
                });
            }
            case LEFT_CLICK -> {
                client.execute(() -> {
                    if (client.player == null || client.gameMode == null) return;
                    client.player.swing(InteractionHand.MAIN_HAND);

                    HitResult hit = client.hitResult;
                    if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
                        client.gameMode.attack(client.player, entityHit.getEntity());
                    } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                        client.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
                    }
                });
                sleep(getScaledDelay(client, 22));
                client.execute(() -> {
                    if (client.options != null) {
                        KeyMapping.set(client.options.keyAttack.getDefaultKey(), false);
                        while (client.options.keyAttack.consumeClick()) {}
                    }
                });
            }
            case DELAY -> {
                sleep(getScaledDelay(client, action.param()));
            }
            case JUMP -> {
                readSync(client, () -> {
                    if (client.player != null && client.options != null) {
                        KeyMapping.click(client.options.keyJump.getDefaultKey());
                        KeyMapping.set(client.options.keyJump.getDefaultKey(), true);
                        if (client.player.onGround()) {
                            client.player.jumpFromGround();
                        }
                    }
                });
                sleep(getScaledDelay(client, 22));
                readSync(client, () -> {
                    if (client.options != null) {
                        KeyMapping.set(client.options.keyJump.getDefaultKey(), false);
                    }
                });
            }
            case RESTORE_SLOT -> {
                readSync(client, () -> {
                    if (client.player != null && client.gameMode != null) {
                        client.player.getInventory().setSelectedSlot(initialSlot);
                        if (client.player.connection != null) {
                            client.player.connection.send(new ServerboundSetCarriedItemPacket(initialSlot));
                        }
                    }
                });
                sleep(getScaledDelay(client, 18));
            }
            case ATTRIBUTE_SWAP -> {
                int useSlot = action.param();
                int targetSlot = action.param2();
                boolean isRightClick = action.yaw() > 0.5f;

                LOGGER.info("[GrimeCombo] AttributeSwap Spear Dash: useSlot={} (1-based={}), targetSlot={} (1-based={}), isRightClick={}",
                        useSlot, useSlot + 1, targetSlot, targetSlot + 1, isRightClick);

                // 1. Установка слота оружия использования (копье)
                client.execute(() -> {
                    if (client.player == null || client.gameMode == null) return;
                    client.player.getInventory().setSelectedSlot(useSlot);
                    if (client.player.connection != null) {
                        client.player.connection.send(new ServerboundSetCarriedItemPacket(useSlot));
                    }
                });

                // Пауза 10 мс для быстрой пред-синхронизации копья
                sleep(getScaledDelay(client, 10));

                // 2. АТОМАРНЫЙ вызов атаки и МГНОВЕННОЙ смены слота на targetSlot (топор) в ОДНОМ сетевом такте!
                client.execute(() -> {
                    if (client.player == null || client.gameMode == null || client.player.connection == null) return;

                    if (isRightClick) {
                        HitResult hit = client.hitResult;
                        net.minecraft.world.InteractionResult result = net.minecraft.world.InteractionResult.PASS;
                        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                            result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
                        } else if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
                            result = client.gameMode.interact(client.player, entityHit.getEntity(), entityHit, InteractionHand.MAIN_HAND);
                        }
                        if (!result.consumesAction()) {
                            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        }
                        if (client.options != null) {
                            KeyMapping.click(client.options.keyUse.getDefaultKey());
                        }
                    } else {
                        boolean invokedNativeAttack = false;
                        try {
                            java.lang.reflect.Method m = net.minecraft.client.Minecraft.class.getDeclaredMethod("startAttack");
                            m.setAccessible(true);
                            m.invoke(client);
                            invokedNativeAttack = true;
                        } catch (Exception ignored) {}

                        if (!invokedNativeAttack) {
                            client.player.resetAttackStrengthTicker();
                            client.player.swing(InteractionHand.MAIN_HAND);
                            client.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                            HitResult hit = client.hitResult;
                            if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
                                client.gameMode.attack(client.player, entityHit.getEntity());
                            } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                                client.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
                            }
                        }

                        if (client.options != null) {
                            KeyMapping.click(client.options.keyAttack.getDefaultKey());
                        }
                    }

                    // Мгновенно переключаем слот на targetSlot (топор с топовым атрибутом урона) в том же вызове
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
                });

                sleep(getScaledDelay(client, 15));
            }
            case WIND_PEARL -> {
                int pearlSlot = action.param();
                int windSlot = action.param2();

                sleep(getScaledDelay(client, 15));

                final float[] angles = new float[2];
                final double[] motion = new double[3];
                final boolean[] playerOnGround = new boolean[1];

                readSync(client, () -> {
                    if (client.player != null) {
                        angles[0] = client.player.getXRot();
                        angles[1] = client.player.getYRot();
                        playerOnGround[0] = client.player.onGround();
                        net.minecraft.world.phys.Vec3 vel = client.player.getDeltaMovement();
                        if (vel != null) {
                            motion[0] = vel.x;
                            motion[1] = vel.y;
                            motion[2] = vel.z;
                        }
                    }
                });

                float originalPitch = angles[0];
                float originalYaw = angles[1];

                double playerVx = Math.abs(motion[0]) < 0.005 ? 0.0 : motion[0];
                double playerVy = Math.abs(motion[1]) < 0.005 ? 0.0 : motion[1];
                double playerVz = Math.abs(motion[2]) < 0.005 ? 0.0 : motion[2];

                lockedPitch = originalPitch;
                lockedYaw = originalYaw;
                isMouseLocked = true;
                isInputLocked = true;

                // 1. Бросок жемчужины Края (Ender Pearl)
                client.execute(() -> {
                    if (client.player != null && client.gameMode != null && client.options != null) {
                        client.gameMode.releaseUsingItem(client.player);
                        KeyMapping.set(client.options.keyUse.getDefaultKey(), false);
                        KeyMapping.set(client.options.keyAttack.getDefaultKey(), false);
                        while (client.options.keyUse.consumeClick()) {}
                        while (client.options.keyAttack.consumeClick()) {}

                        client.player.getInventory().setSelectedSlot(pearlSlot);
                        if (client.player.connection != null) {
                            client.player.connection.send(new ServerboundSetCarriedItemPacket(pearlSlot));
                        }
                        client.player.swing(InteractionHand.MAIN_HAND);
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    }
                });

                // Пауза между броском жемчужины и выстрелом ветра с учетом TPS и пинга
                int delayPearlToWind = getScaledDelay(client, 22);
                sleep(delayPearlToWind);

                float currentTps = TpsTracker.getTps(client);
                double msPerTick = 1000.0 / Math.max(1.0, currentTps);

                // Задержка выстрела ветряного заряда в серверных тиках
                double fireDelayTicks = (double) delayPearlToWind / msPerTick;

                double yawRad = Math.toRadians(originalYaw);
                double pitchRad = Math.toRadians(originalPitch);

                // Физические параметры спавна жемчужины Края в Minecraft
                double v0 = 1.5;
                double vpx = -Math.sin(yawRad) * Math.cos(pitchRad) * v0 + playerVx;
                double vpy = -Math.sin(pitchRad) * v0 + playerVy + (playerOnGround[0] ? 0.0 : 0.1);
                double vpz = Math.cos(yawRad) * Math.cos(pitchRad) * v0 + playerVz;

                double curPx = 0.0;
                double curPy = 1.62; // Глаза игрока
                double curPz = 0.0;

                double prevPx = curPx;
                double prevPy = curPy;
                double prevPz = curPz;

                double curVpx = vpx;
                double curVpy = vpy;
                double curVpz = vpz;

                double bestTargetPitch = originalPitch;
                double bestTargetYaw = originalYaw;

                // Пошаговая 3D-симуляция траектории жемчужины и перехвата ветряным зарядом
                for (int t = 1; t <= 60; t++) {
                    prevPx = curPx;
                    prevPy = curPy;
                    prevPz = curPz;

                    curPx += curVpx;
                    curPy += curVpy;
                    curPz += curVpz;

                    curVpy = (curVpy - 0.03) * 0.99;
                    curVpx *= 0.99;
                    curVpz *= 0.99;

                    double tWind = t - fireDelayTicks;
                    if (tWind > 0) {
                        double dPearlPrev = Math.sqrt(prevPx * prevPx + (prevPy - 1.62) * (prevPy - 1.62) + prevPz * prevPz);
                        double dWindPrev = 1.5 * (t - 1 - fireDelayTicks);

                        double dPearlCurr = Math.sqrt(curPx * curPx + (curPy - 1.62) * (curPy - 1.62) + curPz * curPz);
                        double dWindCurr = 1.5 * tWind;

                        if (dWindCurr >= dPearlCurr) {
                            // Субтиковая линейная интерполяция точки перехвата между тиком t-1 и t
                            double deltaPrev = Math.max(0.0, dPearlPrev - dWindPrev);
                            double deltaCurr = Math.max(0.0, dWindCurr - dPearlCurr);
                            double totalDelta = deltaPrev + deltaCurr;
                            double frac = totalDelta > 0.0001 ? (deltaPrev / totalDelta) : 0.5;

                            double hitX = prevPx + frac * (curPx - prevPx);
                            double hitY = prevPy + frac * (curPy - prevPy);
                            double hitZ = prevPz + frac * (curPz - prevPz);

                            double dy = hitY - 1.62;
                            double rHoriz = Math.sqrt(hitX * hitX + hitZ * hitZ);

                            bestTargetPitch = -Math.toDegrees(Math.atan2(dy, Math.max(0.01, rHoriz)));
                            bestTargetYaw = Math.toDegrees(Math.atan2(-hitX, hitZ));
                            break;
                        }
                    }
                }

                float targetPitch = Math.max(-80.0f, Math.min(80.0f, (float) bestTargetPitch));
                float targetYaw = (float) bestTargetYaw;

                lockedPitch = targetPitch;
                lockedYaw = targetYaw;

                LOGGER.info("[GrimeCombo] 3D Sub-tick Intercept windPearl: Pitch={:.1f}°->{:.1f}°, Yaw={:.1f}°->{:.1f}°, tps={:.1f}, fireDelayTicks={:.2f}",
                        originalPitch, targetPitch, originalYaw, targetYaw, currentTps, fireDelayTicks);

                // Выставляем целевой вектор и переключаемся на слот Ветряного заряда
                client.execute(() -> {
                    if (client.player != null && client.gameMode != null) {
                        client.player.setXRot(targetPitch);
                        client.player.setYRot(targetYaw);
                        client.player.getInventory().setSelectedSlot(windSlot);
                        if (client.player.connection != null) {
                            client.player.connection.send(new ServerboundSetCarriedItemPacket(windSlot));
                        }
                    }
                });
                sleep(getScaledDelay(client, 22));

                // Выстрел ветряным зарядом
                client.execute(() -> {
                    if (client.player == null || client.gameMode == null) return;
                    client.gameMode.releaseUsingItem(client.player);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                });
                sleep(getScaledDelay(client, 45));

                client.execute(() -> {
                    if (client.player != null && client.gameMode != null && client.options != null) {
                        client.gameMode.releaseUsingItem(client.player);
                        KeyMapping.set(client.options.keyUse.getDefaultKey(), false);
                        while (client.options.keyUse.consumeClick()) {}
                    }
                });
                sleep(getScaledDelay(client, 18));

                isMouseLocked = false;
                isInputLocked = false;
            }
        }
    }

    private static void smoothRotate(Minecraft client, float deltaYaw, float deltaPitch) {
        if (client.player == null) return;

        final float[] startAngles = new float[2];
        readSync(client, () -> {
            if (client.player != null) {
                startAngles[0] = client.player.getXRot();
                startAngles[1] = client.player.getYRot();
            }
        });

        float startPitch = startAngles[0];
        float startYaw = startAngles[1];

        float targetPitch = Math.max(-80.0f, Math.min(80.0f, startPitch + deltaPitch));
        float targetYaw = startYaw + deltaYaw;

        float totalDeltaPitch = targetPitch - startPitch;
        float totalDeltaYaw = deltaYaw;

        if (Math.abs(totalDeltaYaw) < 0.01f && Math.abs(totalDeltaPitch) < 0.01f) return;

        float maxAbs = Math.max(Math.abs(totalDeltaYaw), Math.abs(totalDeltaPitch));
        int steps = Math.min(8, Math.max(4, (int) (maxAbs / 20.0f)));

        float tpsScale = TpsTracker.getTpsScale(client);
        int stepDelay = Math.max(1, Math.round(6 * tpsScale));

        for (int i = 1; i <= steps; i++) {
            float t = (float) i / (float) steps;
            float currentYaw = startYaw + totalDeltaYaw * t;
            float currentPitch = Math.max(-80.0f, Math.min(80.0f, startPitch + totalDeltaPitch * t));

            readSync(client, () -> {
                if (client.player != null) {
                    client.player.setYRot(currentYaw);
                    client.player.setXRot(currentPitch);
                }
            });
            sleep(stepDelay);
        }
    }

    private static int getRandomDelay() {
        return 22 + RANDOM.nextInt(39);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
