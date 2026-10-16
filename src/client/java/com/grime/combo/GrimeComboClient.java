package com.grime.combo;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class GrimeComboClient implements ClientModInitializer {
    public static final String MOD_ID = "grimecombo";
    public static boolean isEnabled = false;

    private static KeyMapping toggleKey;
    private static File configFile;

    @Override
    public void onInitializeClient() {
        // Чтение или создание конфига: config/grimecombo.toml
        configFile = new File("config/grimecombo.toml");
        ComboConfig.load(configFile);

        // Хоткей переключения режима (загружается из конфига)
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.grimecombo.toggle",
                InputConstants.Type.KEYSYM,
                ComboConfig.toggleGlfwKey,
                KeyMapping.Category.GAMEPLAY
        ));

        // Регистрация HUD индикатора в правом верхнем углу (зеленая/красная точка)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud_dot"), new ComboHud());

        // Выполнение в НАЧАЛЕ тика клиента (START_CLIENT_TICK) — до того, как Minecraft обработает клавиши
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            // Обновление трекинга TPS
            TpsTracker.onTick(client);

            // Автоматическое отключение режима комбо при смерти или открытом чате (при инвентаре режим продолжается!)
            if (isEnabled) {
                if (client.player.isDeadOrDying() 
                        || client.player.getHealth() <= 0.0f 
                        || (client.gui != null && client.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                    isEnabled = false;
                }
            }

            // Переключение режима: динамическое перечитывание конфига на лету в фоновом потоке
            while (toggleKey.consumeClick()) {
                isEnabled = !isEnabled;
                if (isEnabled && configFile != null) {
                    CompletableFuture.runAsync(() -> ComboConfig.load(configFile));
                }
            }

            if (!isEnabled) return;

            // Мгновенное подавление ванильной клавиши/кнопки мыши игры ДО вызова handleKeybinds() в Minecraft
            for (Combo combo : ComboConfig.combos) {
                if (combo.glfwKey != -1 && isComboKeyPressed(client, combo.glfwKey)) {
                    suppressNativeKey(client, combo.glfwKey);
                    ComboExecutor.tryExecute(client, combo);
                }
            }
        });

        // Контроль блокировки мыши и ввода движения в END_CLIENT_TICK
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            // Блокировка перебивания угла ручным движением мыши во время windPearl
            if (ComboExecutor.isMouseLocked) {
                client.player.setYRot(ComboExecutor.lockedYaw);
                client.player.setXRot(ComboExecutor.lockedPitch);
            }

            // Блокировка клавиш движения и мыши/кликов на время исполнения windPearl для 100% точности
            if (ComboExecutor.isInputLocked && client.options != null) {
                KeyMapping.set(client.options.keyUp.getDefaultKey(), false);
                KeyMapping.set(client.options.keyDown.getDefaultKey(), false);
                KeyMapping.set(client.options.keyLeft.getDefaultKey(), false);
                KeyMapping.set(client.options.keyRight.getDefaultKey(), false);
                KeyMapping.set(client.options.keyUse.getDefaultKey(), false);
                KeyMapping.set(client.options.keyAttack.getDefaultKey(), false);
                while (client.options.keyUse.consumeClick()) {}
                while (client.options.keyAttack.consumeClick()) {}
            }
        });
    }

    /**
     * Проверяет нажатие как клавиатурной клавиши GLFW, так и мышиных кнопок (LMB, RMB, MMB).
     */
    public static boolean isComboKeyPressed(net.minecraft.client.Minecraft client, int glfwKey) {
        if (glfwKey == -1 || client.getWindow() == null) return false;
        if (glfwKey >= 1000) {
            int mouseButton = glfwKey - 1000;
            com.mojang.blaze3d.platform.InputConstants.Key mouseKey =
                    com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(mouseButton);
            return InputConstants.isKeyDown(client.getWindow(), mouseKey.getValue());
        } else {
            return InputConstants.isKeyDown(client.getWindow(), glfwKey);
        }
    }

    /**
     * Подавляет стандартное действие Майнкрафта на боевые клавиши и кнопки мыши комбо.
     * Клавиши управления (WASD/Пробел), инвентаря (E) и смены рук (F) строго исключены!
     */
    private static void suppressNativeKey(net.minecraft.client.Minecraft client, int glfwKey) {
        if (client.options == null) return;

        if (glfwKey >= 1000) {
            int mouseButton = glfwKey - 1000;
            com.mojang.blaze3d.platform.InputConstants.Key mouseKey =
                    com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(mouseButton);
            KeyMapping[] mappingsToSuppress = new KeyMapping[] {
                client.options.keyDrop, client.options.keyUse, client.options.keyAttack,
                client.options.keyPickItem, client.options.keyChat, client.options.keyPlayerList
            };
            for (KeyMapping km : mappingsToSuppress) {
                if (km != null && km.matches(mouseKey)) {
                    KeyMapping.set(km.getDefaultKey(), false);
                    while (km.consumeClick()) {}
                }
            }
            return;
        }

        com.mojang.blaze3d.platform.InputConstants.Key keyToSuppress =
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(glfwKey);

        KeyMapping[] mappingsToSuppress = new KeyMapping[] {
            client.options.keyDrop,
            client.options.keyUse,
            client.options.keyAttack,
            client.options.keyPickItem,
            client.options.keyChat,
            client.options.keyPlayerList
        };

        for (KeyMapping km : mappingsToSuppress) {
            if (km != null 
                    && km != client.options.keyInventory 
                    && km != client.options.keySwapOffhand 
                    && km != client.options.keyUp 
                    && km != client.options.keyDown 
                    && km != client.options.keyLeft 
                    && km != client.options.keyRight 
                    && km != client.options.keyJump 
                    && km.matches(keyToSuppress)) {
                KeyMapping.set(km.getDefaultKey(), false);
                while (km.consumeClick()) {}
            }
        }

        if (client.options.keyHotbarSlots != null) {
            for (KeyMapping km : client.options.keyHotbarSlots) {
                if (km != null 
                        && km != client.options.keyInventory 
                        && km != client.options.keySwapOffhand 
                        && km != client.options.keyUp 
                        && km != client.options.keyDown 
                        && km != client.options.keyLeft 
                        && km != client.options.keyRight 
                        && km != client.options.keyJump 
                        && km.matches(keyToSuppress)) {
                    KeyMapping.set(km.getDefaultKey(), false);
                    while (km.consumeClick()) {}
                }
            }
        }
    }
}
