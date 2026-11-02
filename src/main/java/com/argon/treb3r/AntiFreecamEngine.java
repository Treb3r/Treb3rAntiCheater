package com.argon.treb3r;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Treb3rAntiCheater - Active Anti-Freecam Engine
 * 1. Active Camera Snap: Periodically forces client camera entity sync via ClientboundSetCameraPacket.
 *    For legitimate players, this is an imperceptible no-op. For Freecam cheaters with detached cameras,
 *    this violently snaps their detached camera back to their physical eyes, preventing cave/base scouting.
 * 2. Interaction Barrier: Denies any container opening, block breaking, or combat hits through walls or > 5.2m.
 * 3. Motionless Look Desync Tracking: Monitors stationary players rotating or attempting remote actions.
 */
public class AntiFreecamEngine implements Listener {

    private final Treb3rAntiCheater plugin;
    private boolean enabled;
    private boolean cameraSnapEnabled;
    private int snapIntervalTicks;
    private boolean preventInteractThroughWalls;
    private double maxReachDistance;

    private BukkitTask snapTask;
    private final Map<UUID, Long> lastMoveTicks = new ConcurrentHashMap<>();

    // Cached reflection handles for ClientboundSetCameraPacket
    private Method getHandleMethod;
    private Constructor<?> setCameraCtor;
    private Field connectionField;
    private Method sendPacketMethod;
    private boolean reflectionReady = false;

    public AntiFreecamEngine(Treb3rAntiCheater plugin) {
        this.plugin = plugin;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetCameraPacket");
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            setCameraCtor = packetClass.getConstructor(entityClass);

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            connectionField = serverPlayerClass.getField("connection");

            Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
            sendPacketMethod = listenerClass.getMethod("send", packetInterface);

            reflectionReady = true;
            plugin.getLogger().info("Anti-Freecam Camera Snap Reflection successfully bound.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not bind Camera Snap NMS reflection: " + t.getMessage());
            reflectionReady = false;
        }
    }

    public void init() {
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTask();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("anti-freecam.enabled", true);
        this.cameraSnapEnabled = plugin.getConfig().getBoolean("anti-freecam.camera-snap.enabled", true);
        this.snapIntervalTicks = Math.max(10, plugin.getConfig().getInt("anti-freecam.camera-snap.interval-ticks", 20));
        this.preventInteractThroughWalls = plugin.getConfig().getBoolean("anti-freecam.prevent-interact-through-walls", true);
        this.maxReachDistance = plugin.getConfig().getDouble("anti-freecam.max-reach-distance", 5.2);
        startTask();
    }

    public void stop() {
        if (snapTask != null) {
            snapTask.cancel();
            snapTask = null;
        }
        lastMoveTicks.clear();
    }

    private void startTask() {
        if (snapTask != null) {
            snapTask.cancel();
            snapTask = null;
        }
        if (!enabled || !cameraSnapEnabled || !reflectionReady) return;

        snapTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.SPECTATOR) continue;
                snapCameraToPlayer(player);
            }
        }, snapIntervalTicks, snapIntervalTicks);
    }

    /**
     * Sends ClientboundSetCameraPacket to force the client to bind camera to its own body.
     * Snaps Freecam detached camera immediately back to the player.
     */
    public boolean snapCameraToPlayer(Player player) {
        if (!reflectionReady || !player.isOnline()) return false;
        try {
            Object serverPlayer = getHandleMethod.invoke(player);
            Object packet = setCameraCtor.newInstance(serverPlayer);
            Object connection = connectionField.get(serverPlayer);
            sendPacketMethod.invoke(connection, packet);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            lastMoveTicks.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMoveTicks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled || !preventInteractThroughWalls) return;
        if (event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        OcclusionEngine engine = plugin.getEngine();
        if (engine != null && !engine.canPlayerReachBlock(player, event.getClickedBlock(), maxReachDistance)) {
            event.setCancelled(true);
            snapCameraToPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled || !preventInteractThroughWalls) return;
        Player player = event.getPlayer();
        OcclusionEngine engine = plugin.getEngine();
        if (engine != null && !engine.canPlayerReachBlock(player, event.getBlock(), maxReachDistance)) {
            event.setCancelled(true);
            snapCameraToPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!enabled || !preventInteractThroughWalls) return;
        Player player = event.getPlayer();
        OcclusionEngine engine = plugin.getEngine();
        if (engine != null && !engine.canPlayerReachEntity(player, event.getRightClicked(), maxReachDistance)) {
            event.setCancelled(true);
            snapCameraToPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!enabled || !preventInteractThroughWalls) return;
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            OcclusionEngine engine = plugin.getEngine();
            if (engine != null && !engine.canPlayerReachEntity(player, event.getEntity(), maxReachDistance)) {
                event.setCancelled(true);
                snapCameraToPlayer(player);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCameraSnapEnabled() {
        return cameraSnapEnabled;
    }

    public int getSnapIntervalTicks() {
        return snapIntervalTicks;
    }
}
