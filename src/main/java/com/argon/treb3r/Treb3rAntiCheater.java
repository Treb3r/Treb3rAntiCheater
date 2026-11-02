package com.argon.treb3r;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class Treb3rAntiCheater extends JavaPlugin implements Listener, CommandExecutor {

    public static final String CAMERA_CHANNEL = "treb3r:camera";

    private double maxTrackingDistance;
    private double nearDistance;
    private int timeSlices;
    private boolean hidePlayers;
    private boolean hideMonsters;
    private boolean hideAnimals;
    private boolean hideMisc;
    private boolean bypassOnGlowing;
    private int combatGraceTicks;

    // Anti-Freecam Wall & Reach Settings
    private boolean antiFreecamEnabled;
    private boolean preventInteractThroughWalls;
    private double maxReachDistance;

    private OcclusionEngine engine;
    private AntiXrayEngine antiXrayEngine;
    private AntiFreecamEngine antiFreecamEngine;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        purgeOrphanTextDisplays();

        engine = new OcclusionEngine(this);
        startTask();

        antiXrayEngine = new AntiXrayEngine(this);
        antiXrayEngine.init();

        antiFreecamEngine = new AntiFreecamEngine(this);
        antiFreecamEngine.init();

        // Register Camera Communication Channel (Companion / Client Mod support)
        getServer().getMessenger().registerIncomingPluginChannel(this, CAMERA_CHANNEL, (channel, player, message) -> {
            if (!channel.equals(CAMERA_CHANNEL) || message.length == 0) return;
            int mode = message[0] & 0xFF; // 0 = 1st person, 1 = 3rd person back, 2 = 3rd person front
            if (engine != null) {
                engine.setClientCameraMode(player.getUniqueId(), mode);
            }
        });
        getServer().getMessenger().registerOutgoingPluginChannel(this, CAMERA_CHANNEL);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("treber") != null) {
            getCommand("treber").setExecutor(this);
        }

        getLogger().info("Treb3rAntiCheater v" + getDescription().getVersion() + " initialized with Camera Channel & Click Tracking.");
    }

    @Override
    public void onDisable() {
        stopTask();
        if (engine != null) {
            engine.restoreAll();
        }
        if (antiFreecamEngine != null) {
            antiFreecamEngine.stop();
        }
        purgeOrphanTextDisplays();
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getLogger().info("Treb3rAntiCheater disabled. Restored all visibility states.");
    }

    public void loadConfiguration() {
        reloadConfig();
        maxTrackingDistance = getConfig().getDouble("max-tracking-distance", 64.0);
        nearDistance = getConfig().getDouble("near-distance", 0.0);
        timeSlices = getConfig().getInt("time-slices", 1);
        hidePlayers = getConfig().getBoolean("hide-players", true);
        hideMonsters = getConfig().getBoolean("hide-monsters", true);
        hideAnimals = getConfig().getBoolean("hide-animals", false);
        hideMisc = getConfig().getBoolean("hide-misc", true);
        bypassOnGlowing = getConfig().getBoolean("bypass-on-glowing", true);
        combatGraceTicks = getConfig().getInt("combat-grace-ticks", 30);

        antiFreecamEnabled = getConfig().getBoolean("anti-freecam.enabled", true);
        preventInteractThroughWalls = getConfig().getBoolean("anti-freecam.prevent-interact-through-walls", true);
        maxReachDistance = getConfig().getDouble("anti-freecam.max-reach-distance", 5.2);

        if (antiXrayEngine != null) {
            antiXrayEngine.reload();
        }
        if (antiFreecamEngine != null) {
            antiFreecamEngine.reload();
        }
    }

    private void startTask() {
        stopTask();
        task = getServer().getScheduler().runTaskTimer(this, engine, 1L, 1L);
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void purgeOrphanTextDisplays() {
        for (World world : getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof TextDisplay) {
                entity.remove();
            }
        }
    }

    public OcclusionEngine getEngine() {
        return engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (engine == null) return;
        Player player = event.getPlayer();
        Location hitLoc = event.getInteractionPoint();
        if (hitLoc == null && event.getClickedBlock() != null) {
            hitLoc = event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        if (hitLoc != null) {
            engine.registerClickInteraction(player, hitLoc);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (engine == null || combatGraceTicks <= 0) return;

        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof Entity) {
                damager = (Entity) proj.getShooter();
            }
        }

        if (damager != null && victim != null) {
            engine.triggerCombatGrace(damager.getUniqueId(), victim.getUniqueId(), combatGraceTicks);
        }
    }


    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (engine != null) {
            engine.cleanupPlayer(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("treb3r.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            loadConfiguration();
            startTask();
            sender.sendMessage("§aTreb3rAntiCheater configuration reloaded.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("hideitems")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Must be player");
                return true;
            }
            Player p = (Player) sender;
            int count = 0;
            for (Entity e : p.getWorld().getEntities()) {
                if (e instanceof org.bukkit.entity.Item) {
                    boolean before = p.canSee(e);
                    p.hideEntity(this, e);
                    boolean after = p.canSee(e);
                    getLogger().info("Item " + e.getName() + " id=" + e.getEntityId() + " canSee before=" + before + " after=" + after);
                    count++;
                }
            }
            sender.sendMessage("Hid " + count + " items.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§e=== Treb3rAntiCheater Status ===");
            sender.sendMessage("§7Max Distance: §f" + maxTrackingDistance);
            sender.sendMessage("§7Near Distance: §f" + nearDistance);
            sender.sendMessage("§7Time Slices: §f" + timeSlices);
            sender.sendMessage("§7Hide Players: §f" + hidePlayers);
            sender.sendMessage("§7Hide Monsters: §f" + hideMonsters);
            sender.sendMessage("§7Hide Animals: §f" + hideAnimals);
            sender.sendMessage("§7Hide Misc: §f" + hideMisc);
            sender.sendMessage("§7Bypass On Glowing: §f" + bypassOnGlowing);
            sender.sendMessage("§7Combat Grace: §f" + combatGraceTicks + " ticks");
            if (antiXrayEngine != null) {
                sender.sendMessage("§7Anti-Xray: " + (antiXrayEngine.isEnabled() ? "§aEnabled (Engine Mode " + antiXrayEngine.getEngineMode() + ")" : "§cDisabled"));
            }
            sender.sendMessage("§7Anti-Freecam: " + (antiFreecamEnabled ? "§aEnabled (Reach: " + maxReachDistance + "m, Wall-Block: " + preventInteractThroughWalls + ")" : "§cDisabled"));
            return true;
        }

        sender.sendMessage("§eTreb3rAntiCheater commands: /treber reload, /treber status");
        return true;
    }

    public boolean isAntiFreecamEnabled() {
        return antiFreecamEnabled;
    }

    public boolean isPreventInteractThroughWalls() {
        return preventInteractThroughWalls;
    }

    public double getMaxReachDistance() {
        return maxReachDistance;
    }

    public double getMaxTrackingDistance() {
        return maxTrackingDistance;
    }

    public double getNearDistance() {
        return nearDistance;
    }

    public int getTimeSlices() {
        return timeSlices;
    }

    public boolean isHidePlayers() {
        return hidePlayers;
    }

    public boolean isHideMonsters() {
        return hideMonsters;
    }

    public boolean isHideAnimals() {
        return hideAnimals;
    }

    public boolean isHideMisc() {
        return hideMisc;
    }

    public boolean isBypassOnGlowing() {
        return bypassOnGlowing;
    }

    public int getCombatGraceTicks() {
        return combatGraceTicks;
    }
}
