package com.grime.combo;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ComboHud implements HudElement {
    private static final int DOT_SIZE = 6;
    private static final int MARGIN_TOP = 10;
    private static final int MARGIN_RIGHT = 10;

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui.screen() != null) {
            return;
        }

        int width = guiGraphicsExtractor.guiWidth();
        int x = width - MARGIN_RIGHT - DOT_SIZE;
        int y = MARGIN_TOP;

        boolean enabled = GrimeComboClient.isEnabled;

        // Контрастная обводка для отличной видимости на любом фоне
        guiGraphicsExtractor.fill(x - 1, y - 1, x + DOT_SIZE + 1, y + DOT_SIZE + 1, 0xFF000000);

        // Цвет точки: Зеленый (ВКЛ) или Красный (ВЫКЛ)
        int color = enabled ? 0xFF00FF44 : 0xFFFF2233;
        guiGraphicsExtractor.fill(x, y, x + DOT_SIZE, y + DOT_SIZE, color);
    }
}
