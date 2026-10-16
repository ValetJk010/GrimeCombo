package com.grime.combo;

import java.util.List;

public class Combo {
    public final String name;
    public final String bindKey;
    public final int glfwKey;
    public final long cooldownMs;
    public final List<Action> actions;

    public Combo(String name, String bindKey, int glfwKey, long cooldownMs, List<Action> actions) {
        this.name = name;
        this.bindKey = bindKey;
        this.glfwKey = glfwKey;
        this.cooldownMs = cooldownMs;
        this.actions = actions;
    }

    public enum ActionType {
        SLOT,
        ROTATE,
        RIGHT_CLICK,
        LEFT_CLICK,
        DELAY,
        WIND_PEARL,
        ATTRIBUTE_SWAP,
        JUMP,
        RESTORE_SLOT
    }

    public static class Action {
        public final ActionType type;
        public final int param;
        public final float yaw;
        public final float pitch;
        public final float distance;
        public final int param2;

        public Action(ActionType type, int param, float yaw, float pitch, float distance, int param2) {
            this.type = type;
            this.param = param;
            this.yaw = yaw;
            this.pitch = pitch;
            this.distance = distance;
            this.param2 = param2;
        }

        public ActionType type() { return type; }
        public int param() { return param; }
        public float yaw() { return yaw; }
        public float pitch() { return pitch; }
        public float distance() { return distance; }
        public int param2() { return param2; }

        public static Action slot(int slot) {
            return new Action(ActionType.SLOT, slot, 0, 0, 0, 0);
        }

        public static Action rotate(float yaw, float pitch) {
            return new Action(ActionType.ROTATE, 0, yaw, pitch, 0, 0);
        }

        public static Action rightClick() {
            return new Action(ActionType.RIGHT_CLICK, 0, 0, 0, 0, 0);
        }

        public static Action leftClick() {
            return new Action(ActionType.LEFT_CLICK, 0, 0, 0, 0, 0);
        }

        public static Action delay(int ms) {
            return new Action(ActionType.DELAY, ms, 0, 0, 0, 0);
        }

        public static Action jump() {
            return new Action(ActionType.JUMP, 0, 0, 0, 0, 0);
        }

        public static Action restoreSlot() {
            return new Action(ActionType.RESTORE_SLOT, 0, 0, 0, 0, 0);
        }

        public static Action windPearl(float distanceBlocks, int pearlSlot, int windSlot) {
            return new Action(ActionType.WIND_PEARL, pearlSlot, 0, 0, distanceBlocks, windSlot);
        }

        public static Action attributeSwap(int useSlot, int targetSlot, boolean isRightClick, float holdMs) {
            return new Action(ActionType.ATTRIBUTE_SWAP, useSlot, isRightClick ? 1.0f : 0.0f, 0, holdMs, targetSlot);
        }
        public static Action attributeSwap(int useSlot, int targetSlot, boolean isRightClick) {
            return new Action(ActionType.ATTRIBUTE_SWAP, useSlot, isRightClick ? 1.0f : 0.0f, 0, 50.0f, targetSlot);
        }
    }
}
