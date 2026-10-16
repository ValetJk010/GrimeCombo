package com.grime.combo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ComboConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("GrimeComboConfig");
    public static final List<Combo> combos = new CopyOnWriteArrayList<>();
    public static volatile int toggleGlfwKey = 71; // G

    public static void load(File configFile) {
        if (configFile != null) {
            loadConfig(configFile.getParentFile() != null ? configFile.getParentFile().toPath() : Path.of("config"));
        }
    }

    public static void loadConfig(Path configDir) {
        combos.clear();
        File configFile = configDir.resolve("grimecombo.toml").toFile();

        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }

        try {
            TomlSimpleParser parser = new TomlSimpleParser();
            parser.parse(configFile);

            String toggleStr = parser.getString("toggle_key", "G");
            toggleGlfwKey = parseGlfwKey(toggleStr);

            List<Map<String, Object>> comboTables = parser.getTables("combo");

            for (Map<String, Object> table : comboTables) {
                String name = (String) table.get("name");
                if (name == null || name.trim().isEmpty() || name.equals("Unnamed Combo")) {
                    name = (String) table.get("_header_name");
                }
                if (name == null || name.trim().isEmpty()) {
                    name = "Unnamed Combo";
                }

                String bind = (String) table.getOrDefault("bind", "NONE");
                long cooldownMs = parseLong(table.getOrDefault("cooldown_ms", 1000L));
                List<String> rawActions = parseStringList(table.get("actions"));

                List<Combo.Action> parsedActions = new ArrayList<>();
                for (String rawAction : rawActions) {
                    Combo.Action action = parseAction(rawAction);
                    if (action != null) {
                        parsedActions.add(action);
                    }
                }

                if (!bind.equalsIgnoreCase("NONE") && !parsedActions.isEmpty()) {
                    int glfwKey = parseGlfwKey(bind);
                    Combo combo = new Combo(name, bind.toUpperCase(), glfwKey, cooldownMs, parsedActions);
                    combos.add(combo);
                }
            }

            LOGGER.info("[GrimeCombo] Конфиг успешно загружен. Всего комбо: {}", combos.size());
            for (Combo c : combos) {
                LOGGER.info("[GrimeCombo]  -> Комбо '{}' (bind='{}', glfw={}, cooldown={}ms, шагов={})",
                        c.name, c.bindKey, c.glfwKey, c.cooldownMs, c.actions.size());
                for (int i = 0; i < c.actions.size(); i++) {
                    Combo.Action a = c.actions.get(i);
                    LOGGER.info("[GrimeCombo]     * Шаг {}: тип={}, distance={}, pearlSlot={}, windSlot={}, yaw={}, pitch={}",
                            i + 1, a.type, a.distance, a.param, a.param2, a.yaw, a.pitch);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[GrimeCombo] Ошибка при чтении файла grimecombo.toml", e);
        }
    }

    public static int parseGlfwKey(String keyStr) {
        if (keyStr == null || keyStr.trim().isEmpty()) return -1;
        String str = keyStr.trim().toUpperCase();
        if (str.length() == 1) {
            char ch = str.charAt(0);
            if (ch >= 'A' && ch <= 'Z') return 65 + (ch - 'A');
            if (ch >= '0' && ch <= '9') return 48 + (ch - '0');
        }
        return switch (str) {
            case "SPACE" -> 32;
            case "ESCAPE", "ESC" -> 256;
            case "ENTER", "RETURN" -> 257;
            case "TAB" -> 258;
            case "BACKSPACE" -> 259;
            case "INSERT" -> 260;
            case "DELETE" -> 261;
            case "RIGHT" -> 262;
            case "LEFT" -> 263;
            case "DOWN" -> 264;
            case "UP" -> 265;
            case "LEFT_SHIFT", "LSHIFT", "SHIFT" -> 340;
            case "LEFT_CONTROL", "LCONTROL", "LCTRL", "CTRL" -> 341;
            case "LEFT_ALT", "LALT", "ALT" -> 342;
            // Бинды на кнопки мыши: 1000 = LMB (Левая), 1001 = RMB (Правая), 1002 = MMB (Колёсико), 1003 = MOUSE4 (Боковая Назад), 1004 = MOUSE5 (Боковая Вперед)
            case "LMB", "LEFT_CLICK", "MOUSE_LEFT", "MOUSE1", "BUTTON1" -> 1000;
            case "RMB", "RIGHT_CLICK", "MOUSE_RIGHT", "MOUSE2", "BUTTON2" -> 1001;
            case "MMB", "MIDDLE_CLICK", "MOUSE_MIDDLE", "WHEEL", "SCROLL", "MOUSE3", "BUTTON3" -> 1002;
            case "MOUSE4", "MOUSE_4", "BUTTON4", "MOUSE_BUTTON_4", "SIDE_BACK", "THUMB1" -> 1003;
            case "MOUSE5", "MOUSE_5", "BUTTON5", "MOUSE_BUTTON_5", "SIDE_FORWARD", "THUMB2" -> 1004;
            case "MOUSE6", "MOUSE_6", "BUTTON6" -> 1005;
            case "MOUSE7", "MOUSE_7", "BUTTON7" -> 1006;
            case "MOUSE8", "MOUSE_8", "BUTTON8" -> 1007;
            default -> -1;
        };
    }

    private static Combo.Action parseAction(String raw) {
        String trimmed = raw.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return null;

        String command = parts[0].toLowerCase();
        switch (command) {
            case "slot" -> {
                if (parts.length >= 2) {
                    try {
                        int slot1Based = Integer.parseInt(parts[1]);
                        int slot0Based = Math.max(0, Math.min(8, slot1Based - 1));
                        return Combo.Action.slot(slot0Based);
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "rotate" -> {
                if (parts.length >= 3) {
                    try {
                        float yaw = Float.parseFloat(parts[1]);
                        float pitch = Float.parseFloat(parts[2]);
                        return Combo.Action.rotate(yaw, pitch);
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "right_click" -> {
                return Combo.Action.rightClick();
            }
            case "left_click" -> {
                return Combo.Action.leftClick();
            }
            case "jump", "space" -> {
                return Combo.Action.jump();
            }
            case "restore_slot", "return_slot", "initial_slot", "restore", "back_slot" -> {
                return Combo.Action.restoreSlot();
            }
            case "delay" -> {
                if (parts.length >= 2) {
                    try {
                        int ms = Integer.parseInt(parts[1]);
                        return Combo.Action.delay(ms);
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "windpearl" -> {
                float distance = 10.0f;
                int pearlSlot = 1; // 0-based
                int windSlot = 2;  // 0-based

                if (parts.length >= 2) {
                    try { distance = Float.parseFloat(parts[1]); } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 3) {
                    try { pearlSlot = Math.max(0, Math.min(8, Integer.parseInt(parts[2]) - 1)); } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 4) {
                    try { windSlot = Math.max(0, Math.min(8, Integer.parseInt(parts[3]) - 1)); } catch (NumberFormatException ignored) {}
                }

                return Combo.Action.windPearl(distance, pearlSlot, windSlot);
            }
            case "attributeswap", "attribute_swap", "attrswap", "attr_swap" -> {
                int useSlot = 0;   // 0-based
                int targetSlot = 0; // 0-based
                boolean isRightClick = true;
                float holdMs = 50.0f;

                if (parts.length >= 2) {
                    try { useSlot = Math.max(0, Math.min(8, Integer.parseInt(parts[1]) - 1)); } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 3) {
                    try { targetSlot = Math.max(0, Math.min(8, Integer.parseInt(parts[2]) - 1)); } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 4) {
                    String clickType = parts[3].trim().toUpperCase();
                    isRightClick = !clickType.startsWith("L");
                }
                if (parts.length >= 5) {
                    try { holdMs = Float.parseFloat(parts[4]); } catch (NumberFormatException ignored) {}
                }

                return Combo.Action.attributeSwap(useSlot, targetSlot, isRightClick, holdMs);
            }
        }
        return null;
    }

    private static long parseLong(Object obj) {
        if (obj instanceof Number num) return num.longValue();
        if (obj instanceof String str) {
            try { return Long.parseLong(str.trim()); } catch (NumberFormatException ignored) {}
        }
        return 1000L;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> res = new ArrayList<>();
            for (Object item : list) {
                if (item != null) res.add(item.toString());
            }
            return res;
        }
        return Collections.emptyList();
    }

    private static void createDefaultConfig(File configFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(configFile))) {
            writer.println("toggle_key = \"G\"");
            writer.println();
            writer.println("[[combo \"Wind Pearl\"]]");
            writer.println("bind = \"R\"");
            writer.println("cooldown_ms = 1200");
            writer.println("actions = [");
            writer.println("    \"rotate 0 -150\",");
            writer.println("    \"delay 10\",");
            writer.println("    \"windPearl 35 4 5\",");
            writer.println("    \"slot 3\",");
            writer.println("    \"right_click\",");
            writer.println("    \"rotate 0 90\",");
            writer.println("    \"slot 7\"");
            writer.println("]");
            writer.println();
            writer.println("[[combo \"Emergency Pearl\"]]");
            writer.println("bind = \"Z\"");
            writer.println("cooldown_ms = 600");
            writer.println("actions = [");
            writer.println("    \"rotate 180 -30\",");
            writer.println("    \"slot 4\",");
            writer.println("    \"right_click\",");
            writer.println("    \"rotate 180 30\",");
            writer.println("    \"slot 1\"");
            writer.println("]");
            writer.println();
            writer.println("[[combo \"Crystall Boom\"]]");
            writer.println("bind = \"C\"");
            writer.println("cooldown_ms = 600");
            writer.println("actions = [");
            writer.println("    \"slot 8\",");
            writer.println("    \"right_click\",");
            writer.println("    \"slot 7\",");
            writer.println("    \"rotate 0 -5\",");
            writer.println("    \"right_click\",");
            writer.println("    \"rotate 0 -10\",");
            writer.println("    \"slot 1\",");
            writer.println("    \"left_click\"");
            writer.println("]");
            writer.println();
            writer.println("[[combo \"Spear Dash\"]]");
            writer.println("bind = \"Q\"");
            writer.println("cooldown_ms = 500");
            writer.println("actions = [");
            writer.println("    \"attributeSwap 6 9 L\"");
            writer.println("]");
        } catch (Exception e) {
            LOGGER.error("[GrimeCombo] Не удалось создать дефолтный файл grimecombo.toml", e);
        }
    }

    private static class TomlSimpleParser {
        private final List<Map<String, Object>> tables = new ArrayList<>();
        private final Map<String, Object> globals = new HashMap<>();

        public void parse(File file) throws Exception {
            Map<String, Object> currentTable = null;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    // Парсинг заголовков [[combo]] или [[combo "Wind Pearl"]] или [[combo.Wind Pearl]]
                    if (line.startsWith("[[") && line.endsWith("]]")) {
                        currentTable = new HashMap<>();
                        tables.add(currentTable);

                        String headerContent = line.substring(2, line.length() - 2).trim();
                        if (headerContent.startsWith("combo")) {
                            String sub = headerContent.substring(5).trim();
                            if (sub.startsWith(".")) sub = sub.substring(1).trim();
                            sub = cleanValue(sub).toString().trim();
                            if (!sub.isEmpty()) {
                                currentTable.put("_header_name", sub);
                            }
                        }
                        continue;
                    }

                    if (line.contains("=")) {
                        String[] kv = line.split("=", 2);
                        String key = kv[0].trim();
                        String valStr = kv[1].trim();

                        if (currentTable != null) {
                            if (key.equals("actions") && valStr.startsWith("[")) {
                                StringBuilder listBuilder = new StringBuilder(valStr);
                                while (!listBuilder.toString().contains("]")) {
                                    String nxt = reader.readLine();
                                    if (nxt == null) break;
                                    listBuilder.append(nxt.trim());
                                }
                                currentTable.put(key, parseJsonLikeList(listBuilder.toString()));
                            } else {
                                currentTable.put(key, cleanValue(valStr));
                            }
                        } else {
                            globals.put(key, cleanValue(valStr));
                        }
                    }
                }
            }
        }

        public String getString(String key, String def) {
            Object obj = globals.get(key);
            return obj != null ? obj.toString() : def;
        }

        public List<Map<String, Object>> getTables(String name) {
            return tables;
        }

        private Object cleanValue(String val) {
            val = val.trim();
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                if (val.length() >= 2) {
                    return val.substring(1, val.length() - 1);
                }
            }
            return val;
        }

        private List<String> parseJsonLikeList(String str) {
            List<String> result = new ArrayList<>();
            int start = str.indexOf("[");
            int end = str.lastIndexOf("]");
            if (start != -1 && end != -1 && start < end) {
                str = str.substring(start + 1, end);
            }
            String[] tokens = str.split(",");
            for (String tok : tokens) {
                String item = tok.trim();
                if ((item.startsWith("\"") && item.endsWith("\"")) || (item.startsWith("'") && item.endsWith("'"))) {
                    if (item.length() >= 2) {
                        item = item.substring(1, item.length() - 1);
                    }
                }
                if (!item.isEmpty()) {
                    result.add(item);
                }
            }
            return result;
        }
    }
}
