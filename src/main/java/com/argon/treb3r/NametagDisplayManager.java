package com.argon.treb3r;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral TextDisplay manager for occluded players.
 * When a player body is occluded from view (defeating Box ESP, Skeleton ESP, and Hitboxes),
 * this manager displays their vanilla-style nametag through walls unless they are sneaking.
 * All entities are non-persistent (never written to chunk files).
 */
public class NametagDisplayManager {

    private final Treb3rAntiCheater plugin;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastPositions = new ConcurrentHashMap<>();

    public NametagDisplayManager(Treb3rAntiCheater plugin) {
        this.plugin = plugin;
    }

    public void updateNametagPosition(Player target) {
        if (!target.isOnline()) {
            removeNametag(target.getUniqueId());
            return;
        }

        double tagY = target.isSneaking() ? 1.85 : 2.15;
        Location headLoc = target.getLocation().clone().add(0, tagY, 0);

        Location prev = lastPositions.get(target.getUniqueId());
        // Skip teleporting if the player hasn't moved significantly (> 2mm)
        if (prev != null && prev.getWorld().equals(headLoc.getWorld()) && prev.distanceSquared(headLoc) < 0.0004) {
            return;
        }

        TextDisplay display = displays.get(target.getUniqueId());
        if (display == null || !display.isValid()) {
            display = target.getWorld().spawn(headLoc, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBillboard(Billboard.CENTER);
                d.setSeeThrough(true);
                d.text(Component.text(target.getName()));
            });
            displays.put(target.getUniqueId(), display);
        } else {
            display.teleport(headLoc);
        }
        lastPositions.put(target.getUniqueId(), headLoc);
    }

    public void setNametagVisible(Player viewer, Player target, boolean shouldShowNametag) {
        TextDisplay display = displays.get(target.getUniqueId());
        if (display == null || !display.isValid()) return;

        // Never show player their own nametag display
        if (viewer.equals(target)) {
            viewer.hideEntity(plugin, display);
            return;
        }

        // Sneaking players hide their nametags through walls (vanilla mechanic)
        if (target.isSneaking()) {
            viewer.hideEntity(plugin, display);
            return;
        }

        if (shouldShowNametag) {
            viewer.showEntity(plugin, display);
        } else {
            viewer.hideEntity(plugin, display);
        }
    }

    public void removeNametag(UUID targetId) {
        lastPositions.remove(targetId);
        TextDisplay display = displays.remove(targetId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public void purgeAll() {
        lastPositions.clear();
        for (TextDisplay display : displays.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
    }
}
