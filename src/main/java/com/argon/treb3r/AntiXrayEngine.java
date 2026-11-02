package com.argon.treb3r;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Treb3rAntiCheater - Native Packet-Level Anti-Xray Engine
 * Injects Paper's high-performance ChunkPacketBlockControllerAntiXray directly into running worlds at runtime.
 * Obfuscates chunk packets with fake ores (Engine Mode 2) or masks them as stone (Engine Mode 1) before sending over the wire.
 * Completely eliminates X-Ray / Freecam ore ESP in cheat clients (Meteor, Wurst, LiquidBounce, etc.).
 */
public class AntiXrayEngine implements Listener {

    private final Treb3rAntiCheater plugin;
    private boolean enabled;
    private int engineMode;
    private boolean lavaObscures;
    private int updateRadius;

    public AntiXrayEngine(Treb3rAntiCheater plugin) {
        this.plugin = plugin;
    }

    public void init() {
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Inject into all currently loaded worlds
        for (World world : plugin.getServer().getWorlds()) {
            injectWorld(world);
        }

        // Auto-sync server config on disk
        syncPaperConfigFile();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("anti-xray.enabled", true);
        this.engineMode = plugin.getConfig().getInt("anti-xray.engine-mode", 2);
        this.lavaObscures = plugin.getConfig().getBoolean("anti-xray.lava-obscures", true);
        this.updateRadius = plugin.getConfig().getInt("anti-xray.update-radius", 2);

        if (enabled) {
            for (World world : plugin.getServer().getWorlds()) {
                injectWorld(world);
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (enabled) {
            injectWorld(event.getWorld());
        }
    }

    public boolean injectWorld(World world) {
        if (!enabled) return false;

        try {
            // 1. Get CraftWorld.getHandle() -> ServerLevel
            Method getHandleMethod = world.getClass().getMethod("getHandle");
            Object serverLevel = getHandleMethod.invoke(world);

            // 2. Get paperConfig() -> WorldConfiguration
            Method paperConfigMethod = findMethod(serverLevel.getClass(), "paperConfig");
            if (paperConfigMethod == null) {
                plugin.getLogger().warning("Could not find paperConfig() on world: " + world.getName());
                return false;
            }
            Object paperConfig = paperConfigMethod.invoke(serverLevel);

            // 3. Get WorldConfiguration.anticheat -> WorldConfiguration$Anticheat
            Field anticheatField = findField(paperConfig.getClass(), "anticheat");
            if (anticheatField == null) return false;
            Object anticheat = anticheatField.get(paperConfig);

            // 4. Get WorldConfiguration$Anticheat.antiXray -> WorldConfiguration$Anticheat$AntiXray
            Field antiXrayField = findField(anticheat.getClass(), "antiXray");
            if (antiXrayField == null) return false;
            Object antiXrayConfig = antiXrayField.get(anticheat);

            // 5. Configure antiXray fields
            setField(antiXrayConfig, "enabled", true);
            setField(antiXrayConfig, "lavaObscures", lavaObscures);
            setField(antiXrayConfig, "updateRadius", updateRadius);

            // EngineMode: 1 = HIDE, 2 = OBFUSCATE
            try {
                Class<?> engineModeClass = Class.forName("io.papermc.paper.configuration.type.EngineMode");
                Object[] enumConstants = engineModeClass.getEnumConstants();
                if (enumConstants != null && enumConstants.length >= 2) {
                    Object targetMode = (engineMode == 1) ? enumConstants[0] : enumConstants[1];
                    setField(antiXrayConfig, "engineMode", targetMode);
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Could not resolve EngineMode enum: " + t.getMessage());
            }

            // 6. Find chunkPacketBlockController field on ServerLevel (or superclass Level)
            Field controllerField = findField(serverLevel.getClass(), "chunkPacketBlockController");
            if (controllerField == null) {
                plugin.getLogger().warning("Could not find chunkPacketBlockController field on world: " + world.getName());
                return false;
            }
            controllerField.setAccessible(true);
            Object currentController = controllerField.get(serverLevel);

            Class<?> antiXrayControllerClass = Class.forName("io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray");
            if (currentController != null && antiXrayControllerClass.isInstance(currentController)) {
                plugin.getLogger().info("Anti-Xray (Engine Mode " + engineMode + ") active on world: " + world.getName());
                return true;
            }

            // 7. Instantiate ChunkPacketBlockControllerAntiXray(Level level, Executor executor)
            Class<?> levelClass = findClassInHierarchy(serverLevel.getClass(), "net.minecraft.world.level.Level");
            if (levelClass == null) levelClass = serverLevel.getClass().getSuperclass();

            Executor workerPool = getWorkerPool();
            Constructor<?> ctor = antiXrayControllerClass.getConstructor(levelClass, Executor.class);
            Object newController = ctor.newInstance(serverLevel, workerPool);

            // 8. Assign newController to serverLevel.chunkPacketBlockController
            controllerField.set(serverLevel, newController);
            plugin.getLogger().info("Injected Packet-Level Anti-Xray (Engine Mode " + engineMode + ") into world: " + world.getName());
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to inject packet Anti-Xray into world " + world.getName() + ": " + t.getMessage());
            return false;
        }
    }

    private Executor getWorkerPool() {
        try {
            Class<?> moonriseCommon = Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon");
            Field workerPoolField = moonriseCommon.getField("WORKER_POOL");
            return (Executor) workerPoolField.get(null);
        } catch (Throwable ignored) {}
        return ForkJoinPool.commonPool();
    }

    private void syncPaperConfigFile() {
        try {
            File configFile = new File("config/paper-world-defaults.yml");
            if (!configFile.exists()) {
                configFile = new File("world/paper-world.yml");
            }
            if (configFile.exists()) {
                String content = Files.readString(configFile.toPath());
                boolean changed = false;
                if (content.contains("anti-xray:")) {
                    if (content.contains("enabled: false")) {
                        content = content.replace("enabled: false", "enabled: true");
                        changed = true;
                    }
                    if (engineMode == 2 && content.contains("engine-mode: 1")) {
                        content = content.replace("engine-mode: 1", "engine-mode: 2");
                        changed = true;
                    }
                }
                if (changed) {
                    Files.writeString(configFile.toPath(), content);
                    plugin.getLogger().info("Auto-synchronized Paper Anti-Xray settings in " + configFile.getPath());
                }
            }
        } catch (Throwable ignored) {}
    }

    private Method findMethod(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                f.set(target, value);
            }
        } catch (Throwable ignored) {}
    }

    private Class<?> findClassInHierarchy(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            if (current.getName().equals(name)) {
                return current;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getEngineMode() {
        return engineMode;
    }
}
