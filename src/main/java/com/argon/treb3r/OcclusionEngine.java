package com.argon.treb3r;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Treb3rAntiCheater - Hardened Anti-ESP Engine
 * Architecture:
 * 1. Raycasted Multi-Point Occlusion: samples crowns, head, chest, waist, knees, feet, lateral shoulders.
 * 2. Solid vs Transparent Barrier Discrimination: respects open windows in oak/iron/bamboo trapdoors while
 *    blocking line-of-sight on solid wooden spruce, dark oak, pale oak trapdoors.
 * 3. Ephemeral Nametag Passthrough: when a player body is occluded behind walls, their vanilla-style nametag
 *    is projected via client-filtered TextDisplay unless sneaking. This disables Skeleton/Box/Chams/Hitboxes
 *    while preserving vanilla player location cues for legitimate players.
 * 4. Per-Viewer Entity Hysteresis: eliminates cross-viewer entity leaks (e.g. dropped items).
 */
public class OcclusionEngine implements Runnable {

    private static final boolean[] IGNORED_MATERIALS = new boolean[Material.values().length];
    private static final boolean[] HOLE_CAPABLE_MATERIALS = new boolean[Material.values().length];

    static {
        for (Material mat : Material.values()) {
            if (mat.isAir()) {
                IGNORED_MATERIALS[mat.ordinal()] = true;
                continue;
            }
            String name = mat.name();
            if (name.contains("GLASS") ||
                name.contains("BARS") ||
                name.contains("CHAIN") ||
                name.contains("GRATE") ||
                name.contains("ROD") ||
                name.endsWith("_FENCE") ||
                name.endsWith("_FENCE_GATE") ||
                name.endsWith("_LEAVES") ||
                name.contains("LANTERN") ||
                name.equals("BELL") ||
                name.contains("AMETHYST") ||
                name.equals("COBWEB") ||
                name.equals("SCAFFOLDING") ||
                name.equals("TRIPWIRE") ||
                name.equals("STRING") ||
                name.equals("BARRIER") ||
                name.equals("LIGHT")) {
                IGNORED_MATERIALS[mat.ordinal()] = true;
            }

            if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.equals("CHEST") ||
                name.equals("TRAPPED_CHEST") || name.equals("ENDER_CHEST") || name.endsWith("_SLAB") ||
                name.endsWith("_STAIRS") || name.equals("DECORATED_POT")) {
                HOLE_CAPABLE_MATERIALS[mat.ordinal()] = true;
            }
        }
    }

    private final Treb3rAntiCheater plugin;
    private final NametagDisplayManager nametagManager;

    private final Map<UUID, Map<UUID, Long>> combatGraceMap = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> clickGraceMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> clientCameraModes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> entityHysteresis = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> playerHysteresis = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewerRevealedEntities = new ConcurrentHashMap<>();

    private long currentTick = 0;

    public OcclusionEngine(Treb3rAntiCheater plugin) {
        this.plugin = plugin;
        this.nametagManager = new NametagDisplayManager(plugin);
        this.nametagManager.purgeAll();
    }

    public void setClientCameraMode(UUID uuid, int mode) {
        clientCameraModes.put(uuid, mode);
    }

    public void registerClickInteraction(Player player, Location hitLoc) {
        UUID viewerId = player.getUniqueId();
        long expire = currentTick + 20; // 1 second visibility boost around clicked target
        for (Player target : player.getWorld().getPlayers()) {
            if (target.equals(player)) continue;
            if (target.getLocation().distanceSquared(hitLoc) <= 9.0) {
                clickGraceMap.computeIfAbsent(viewerId, k -> new ConcurrentHashMap<>()).put(target.getUniqueId(), expire);
            }
        }
    }

    public boolean isEntityVisibleTo(Player viewer, Entity entity) {
        if (viewer.equals(entity)) return true;
        if (viewer.getGameMode() == GameMode.SPECTATOR) return true;

        if (plugin.isBypassOnGlowing()) {
            if (entity.isGlowing()) return true;
            if (entity instanceof LivingEntity && ((LivingEntity) entity).hasPotionEffect(PotionEffectType.GLOWING)) return true;
        }

        double maxDist = plugin.getMaxTrackingDistance();
        Location viewerLoc = viewer.getLocation();
        Location entLoc = entity.getLocation();
        if (viewerLoc.getWorld() != entLoc.getWorld()) return false;

        double distSq = viewerLoc.distanceSquared(entLoc);
        if (distSq > maxDist * maxDist) return false;

        double nearDist = plugin.getNearDistance();
        if (nearDist > 0.001 && distSq <= nearDist * nearDist) return true;

        Map<UUID, Long> viewerCombat = combatGraceMap.get(viewer.getUniqueId());
        if (viewerCombat != null) {
            Long expire = viewerCombat.get(entity.getUniqueId());
            if (expire != null && currentTick <= expire) return true;
        }

        if (entity instanceof Player) {
            if (!plugin.isHidePlayers()) return true;
            Player targetPlayer = (Player) entity;
            Map<UUID, Long> inner = playerHysteresis.get(viewer.getUniqueId());
            Long grace = inner != null ? inner.get(targetPlayer.getUniqueId()) : null;
            if (grace != null && currentTick <= grace) return true;

            PlayerState viewerState = capturePlayerState(viewer);
            PlayerState targetState = capturePlayerState(targetPlayer);
            boolean vis = evaluatePlayerVisibility(viewerState, targetState);
            if (vis) {
                playerHysteresis.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>())
                        .put(targetPlayer.getUniqueId(), currentTick + 8);
            }
            return vis;
        }

        if (shouldFilter(entity, plugin.isHideMonsters(), plugin.isHideAnimals(), plugin.isHideMisc())) {
            PlayerState viewerState = capturePlayerState(viewer);
            return evaluateEntityVisibility(viewerState, entity);
        }

        return true;
    }

    public void triggerCombatGrace(UUID entityId1, UUID entityId2, long durationTicks) {
        long expireTick = currentTick + durationTicks;
        combatGraceMap.computeIfAbsent(entityId1, k -> new ConcurrentHashMap<>()).put(entityId2, expireTick);
        combatGraceMap.computeIfAbsent(entityId2, k -> new ConcurrentHashMap<>()).put(entityId1, expireTick);
    }

    @Override
    public void run() {
        currentTick++;

        double nearDist = plugin.getNearDistance();
        double nearDistSq = nearDist * nearDist;
        double maxDist = plugin.getMaxTrackingDistance();
        double maxDistSq = maxDist * maxDist;
        int timeSlices = Math.max(1, plugin.getTimeSlices());

        boolean hidePlayers = plugin.isHidePlayers();
        boolean hideMonsters = plugin.isHideMonsters();
        boolean hideAnimals = plugin.isHideAnimals();
        boolean hideMisc = plugin.isHideMisc();
        boolean bypassOnGlowing = plugin.isBypassOnGlowing();

        List<Player> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        int totalPlayers = onlinePlayers.size();
        if (totalPlayers == 0) {
            return;
        }

        // Update ephemeral nametag positions for all players
        for (Player p : onlinePlayers) {
            if (p.isOnline()) {
                nametagManager.updateNametagPosition(p);
            }
        }

        // Periodic cleanup of expired hysteresis entries
        if (currentTick % 20 == 0) {
            long now = currentTick;
            for (Map<UUID, Long> inner : entityHysteresis.values()) {
                inner.entrySet().removeIf(e -> e.getValue() < now);
            }
            for (Map<UUID, Long> inner : playerHysteresis.values()) {
                inner.entrySet().removeIf(e -> e.getValue() < now);
            }
        }

        List<PlayerState> states = new ArrayList<>(totalPlayers);
        for (Player p : onlinePlayers) {
            if (!p.isOnline()) continue;
            states.add(capturePlayerState(p));
        }

        int stateCount = states.size();
        int currentSlice = (int) (currentTick % timeSlices);

        for (int i = 0; i < stateCount; i++) {
            if (stateCount > 8 && (i % timeSlices) != currentSlice) {
                continue;
            }

            PlayerState viewer = states.get(i);
            Player viewerPlayer = viewer.player;

            if (viewerPlayer.hasPermission("treb3r.bypass") || viewerPlayer.getGameMode() == GameMode.SPECTATOR) {
                revealAllPlayersTo(viewerPlayer, states);
                continue;
            }

            Map<UUID, Long> viewerCombat = combatGraceMap.get(viewer.uuid);

            // 1. PLAYER-TO-PLAYER OCCLUSION
            if (hidePlayers) {
                for (int j = 0; j < stateCount; j++) {
                    if (i == j) continue;
                    PlayerState target = states.get(j);

                    if (target.world != viewer.world) continue;

                    // Fast Manhattan rejection
                    double dx = target.x - viewer.x;
                    if (dx > maxDist || dx < -maxDist) {
                        applyPlayerVisibility(viewerPlayer, target.player, false);
                        continue;
                    }
                    double dz = target.z - viewer.z;
                    if (dz > maxDist || dz < -maxDist) {
                        applyPlayerVisibility(viewerPlayer, target.player, false);
                        continue;
                    }
                    double dy = target.y - viewer.y;
                    if (dy > 32.0 || dy < -32.0) {
                        applyPlayerVisibility(viewerPlayer, target.player, false);
                        continue;
                    }

                    double distSq = dx * dx + dy * dy + dz * dz;

                    // Proximity Safety Bubble (if nearDist > 0 in config)
                    if (nearDistSq > 0.0001 && distSq <= nearDistSq) {
                        applyPlayerVisibility(viewerPlayer, target.player, true);
                        continue;
                    }

                    // Combat Grace Check
                    if (viewerCombat != null) {
                        Long expire = viewerCombat.get(target.uuid);
                        if (expire != null && currentTick <= expire) {
                            applyPlayerVisibility(viewerPlayer, target.player, true);
                            continue;
                        }
                    }

                    // Click Interaction Grace Check
                    Map<UUID, Long> viewerClicks = clickGraceMap.get(viewer.uuid);
                    if (viewerClicks != null) {
                        Long expire = viewerClicks.get(target.uuid);
                        if (expire != null && currentTick <= expire) {
                            applyPlayerVisibility(viewerPlayer, target.player, true);
                            continue;
                        }
                    }

                    // Glowing Check
                    if (bypassOnGlowing && target.isGlowing) {
                        applyPlayerVisibility(viewerPlayer, target.player, true);
                        continue;
                    }

                    // Max Distance Check
                    if (distSq > maxDistSq) {
                        applyPlayerVisibility(viewerPlayer, target.player, false);
                        continue;
                    }

                    // Real-time raytracing check with 8-tick debounce hysteresis (eliminates 20Hz flicker)
                    Map<UUID, Long> viewerPlayerHysteresis = playerHysteresis.computeIfAbsent(viewer.uuid, k -> new ConcurrentHashMap<>());
                    boolean visible = evaluatePlayerVisibility(viewer, target);
                    if (visible) {
                        viewerPlayerHysteresis.put(target.uuid, currentTick + 8);
                        applyPlayerVisibility(viewerPlayer, target.player, true);
                    } else {
                        Long grace = viewerPlayerHysteresis.get(target.uuid);
                        if (grace != null && currentTick <= grace) {
                            applyPlayerVisibility(viewerPlayer, target.player, true);
                        } else {
                            applyPlayerVisibility(viewerPlayer, target.player, false);
                        }
                    }
                }
            }

            // 2. MONSTERS, ANIMALS, MISC OCCLUSION (Sweep every 3 ticks, full tracking radius)
            if ((hideMonsters || hideAnimals || hideMisc) && (currentTick % 3 == 0)) {
                double entityRadius = Math.max(maxDist, 64.0);
                Collection<Entity> nearby = viewer.world.getNearbyEntities(viewer.eyeLoc, entityRadius, entityRadius, entityRadius);
                Map<UUID, Long> viewerHysteresis = entityHysteresis.computeIfAbsent(viewer.uuid, k -> new ConcurrentHashMap<>());
                Set<UUID> previouslyRevealed = viewerRevealedEntities.computeIfAbsent(viewer.uuid, k -> ConcurrentHashMap.newKeySet());
                Set<UUID> currentPassRevealed = new HashSet<>();

                for (Entity entity : nearby) {
                    if (entity instanceof Player || entity.equals(viewerPlayer)) continue;
                    if (!shouldFilter(entity, hideMonsters, hideAnimals, hideMisc)) continue;

                    UUID entId = entity.getUniqueId();
                    boolean visible = evaluateEntityVisibility(viewer, entity);

                    if (visible) {
                        viewerHysteresis.put(entId, currentTick + 6);
                        reveal(viewerPlayer, entity);
                        currentPassRevealed.add(entId);
                    } else {
                        Long grace = viewerHysteresis.get(entId);
                        if (grace != null && currentTick <= grace) {
                            reveal(viewerPlayer, entity);
                            currentPassRevealed.add(entId);
                        } else {
                            conceal(viewerPlayer, entity);
                        }
                    }
                }

                // Strictly conceal any entity that was previously shown but is no longer visible/in-range
                previouslyRevealed.removeIf(oldId -> {
                    if (!currentPassRevealed.contains(oldId)) {
                        Entity e = plugin.getServer().getEntity(oldId);
                        if (e != null && e.isValid()) {
                            conceal(viewerPlayer, e);
                        }
                        return true;
                    }
                    return false;
                });
                previouslyRevealed.addAll(currentPassRevealed);
            }
        }
    }

    private void applyPlayerVisibility(Player viewer, Player targetPlayer, boolean visible) {
        if (visible) {
            reveal(viewer, targetPlayer);
            syncVehicle(viewer, targetPlayer, true);
            nametagManager.setNametagVisible(viewer, targetPlayer, false);
        } else {
            conceal(viewer, targetPlayer);
            syncVehicle(viewer, targetPlayer, false);
            nametagManager.setNametagVisible(viewer, targetPlayer, true);
        }
    }

    /**
     * Precision Raytracing Engine with Crosshair LoS & Multi-Point Voxel Sampling:
     * Covers narrow slits, decorated pots, chest gaps, slab slits, fence holes, and peekers.
     */
    private boolean evaluatePlayerVisibility(PlayerState viewer, PlayerState target) {
        World world = viewer.world;
        Location eyeLoc = viewer.eyeLoc;
        Vector start = eyeLoc.toVector();

        BoundingBox box = target.box;

        // 1. Crosshair Line-of-Sight Check (Direct peeking through narrow slits, holes, cracks)
        Vector lookDir = new Vector(viewer.dirX, viewer.dirY, viewer.dirZ);
        RayTraceResult crosshairHit = box.rayTrace(start, lookDir, 48.0);
        if (crosshairHit != null) {
            Vector hitPos = crosshairHit.getHitPosition();
            if (isRayClear(world, eyeLoc, start, hitPos)) return true;
        }

        double minX = box.getMinX();
        double maxX = box.getMaxX();
        double minZ = box.getMinZ();
        double maxZ = box.getMaxZ();
        double minY = box.getMinY();
        double maxY = box.getMaxY();
        double midX = (minX + maxX) * 0.5;
        double midZ = (minZ + maxZ) * 0.5;

        double offX = (maxX - minX) * 0.42;
        double offZ = (maxZ - minZ) * 0.42;

        // Multi-tier height sampling covering entire player silhouette and micro-slits
        double[] heights = {
            minY + 0.08, // bottom of shoes
            minY + 0.28, // lower shins
            minY + 0.48, // mid shins
            minY + 0.68, // knees
            minY + 0.88, // waist / chest-slit level (gap above 14/16 blocks)
            minY + 1.08, // mid torso
            minY + 1.32, // upper torso / shoulders
            minY + 1.55, // eyes
            maxY - 0.05  // crown
        };

        // 2. Fast Coarse Triage (Eye, Center of Mass, Torso, Feet)
        // Resolves 90-95% of visible targets in 1-4 coarse rays with minimal CPU overhead
        Vector targetEye = target.eyeLoc.toVector();
        if (isRayClear(world, eyeLoc, start, targetEye)) return true;

        Vector center = box.getCenter();
        if (isRayClear(world, eyeLoc, start, center)) return true;

        Vector feet = new Vector(midX, minY + 0.25, midZ);
        if (isRayClear(world, eyeLoc, start, feet)) return true;

        Vector chest = new Vector(midX, minY + 1.25, midZ);
        if (isRayClear(world, eyeLoc, start, chest)) return true;

        // 3. Frustum / Cone Pruning (Behind-the-back culling)
        // If target is behind viewer's back (> 98 degrees off-axis) and not in F5 camera mode,
        // they cannot be seen in 1st person. Strictly hides targets outside legitimate FOV!
        Integer reportedCam = clientCameraModes.get(viewer.uuid);
        boolean isF5 = reportedCam != null && reportedCam != 0;
        if (!isF5) {
            Vector toCenter = center.clone().subtract(start).normalize();
            if (lookDir.dot(toCenter) < -0.15) {
                return false;
            }
        }

        // 4. Dense multi-point volume & perimeter sweep (Step ~ 8.5 cm across X and Z)
        // Checks front, back, left, right, and center faces across all 9 height tiers.
        // In narrow apertures (chests, decorated pots, copper bars, grates), this guarantees
        // rays pass cleanly through even when the viewer sees the slit from peripheral vision.
        int subdivisions = 7;
        double stepX = (maxX - minX - 0.06) / (subdivisions - 1);
        double stepZ = (maxZ - minZ - 0.06) / (subdivisions - 1);

        double frontZ = minZ + 0.03;
        double backZ = maxZ - 0.03;
        double leftX = minX + 0.03;
        double rightX = maxX - 0.03;

        for (double y : heights) {
            // Front, back, and midZ sweeps along X
            for (int k = 0; k < subdivisions; k++) {
                double sx = minX + 0.03 + k * stepX;
                if (isRayClear(world, eyeLoc, start, new Vector(sx, y, frontZ))) return true;
                if (isRayClear(world, eyeLoc, start, new Vector(sx, y, backZ))) return true;
                if (isRayClear(world, eyeLoc, start, new Vector(sx, y, midZ))) return true;
            }
            // Left, right, and midX sweeps along Z
            for (int k = 0; k < subdivisions; k++) {
                double sz = minZ + 0.03 + k * stepZ;
                if (isRayClear(world, eyeLoc, start, new Vector(leftX, y, sz))) return true;
                if (isRayClear(world, eyeLoc, start, new Vector(rightX, y, sz))) return true;
                if (isRayClear(world, eyeLoc, start, new Vector(midX, y, sz))) return true;
            }
        }

        // 3. Velocity extrapolation (compensates for 50-100ms peeker's advantage around corners)
        if (viewer.velocity.lengthSquared() > 0.005) {
            Location extLoc = eyeLoc.clone().add(viewer.velocity.clone().multiply(1.5));
            Vector extStart = extLoc.toVector();
            for (double y : heights) {
                if (isRayClear(world, extLoc, extStart, new Vector(midX, y, midZ))) return true;
                if (isRayClear(world, extLoc, extStart, new Vector(midX + offX, y, midZ))) return true;
                if (isRayClear(world, extLoc, extStart, new Vector(midX - offX, y, midZ))) return true;
            }
        }

        // --- F5 REAR CAMERA (Strictly when reported by client companion mod) ---
        Integer reportedMode = clientCameraModes.get(viewer.uuid);
        if (reportedMode != null && reportedMode == 1) {
            float yawRad = (float) Math.toRadians(viewer.yaw);
            float pitchRad = (float) Math.toRadians(viewer.pitch);

            double f5DirX = -Math.sin(yawRad) * Math.cos(pitchRad);
            double f5DirY = -Math.sin(pitchRad);
            double f5DirZ = Math.cos(yawRad) * Math.cos(pitchRad);
            Vector f5LookDir = new Vector(f5DirX, f5DirY, f5DirZ).normalize();
            Vector backDir = f5LookDir.clone().multiply(-1.0);

            double backDist = clipRayDistance(world, eyeLoc, backDir, 4.0);
            if (backDist > 0.25) {
                Location f5Cam = eyeLoc.clone().add(backDir.multiply(backDist));
                Vector f5Start = f5Cam.toVector();

                Vector crown = new Vector(midX, maxY - 0.05, midZ);
                Vector toTarget = crown.clone().subtract(f5Start).normalize();
                double dot = f5LookDir.dot(toTarget);

                if (dot >= 0.48) {
                    if (isRayClear(world, f5Cam, f5Start, crown)) return true;
                    if (isRayClear(world, f5Cam, f5Start, new Vector(midX, minY + 1.55, midZ))) return true;
                    if (isRayClear(world, f5Cam, f5Start, new Vector(midX, minY + 1.25, midZ))) return true;
                    if (isRayClear(world, f5Cam, f5Start, new Vector(midX, minY + 0.95, midZ))) return true;
                }
            }
        }

        return false;
    }

    public boolean evaluateEntityVisibility(PlayerState viewer, Entity entity) {
        World world = viewer.world;
        Location eyeLoc = viewer.eyeLoc;
        Vector start = eyeLoc.toVector();
        BoundingBox box = entity.getBoundingBox();

        // 1. Crosshair LoS check
        Vector lookDir = new Vector(viewer.dirX, viewer.dirY, viewer.dirZ);
        RayTraceResult crossHit = box.rayTrace(start, lookDir, 48.0);
        if (crossHit != null && isRayClear(world, eyeLoc, start, crossHit.getHitPosition())) {
            return true;
        }

        // 2. Center of entity
        Vector center = box.getCenter();

        // Frustum / Field of View Culling:
        // Strictly hides entities outside the player's legitimate view cone (> 98 degrees off look axis).
        // Cheaters flying Freecam behind their back cannot see any mobs or entities.
        Integer reportedCam = clientCameraModes.get(viewer.uuid);
        boolean isF5 = reportedCam != null && reportedCam != 0;
        if (!isF5) {
            Vector toCenter = center.clone().subtract(start).normalize();
            if (lookDir.dot(toCenter) < -0.15) {
                return false;
            }
        }

        if (isRayClear(world, eyeLoc, start, center)) return true;

        // 3. Top of entity
        Vector top = new Vector(box.getCenterX(), box.getMaxY() - 0.05, box.getCenterZ());
        if (isRayClear(world, eyeLoc, start, top)) return true;

        // Early-out: if entity is further than 5 blocks and both center & top were blocked,
        // it is behind solid terrain (caves, deepslate, walls). Skip expensive perimeter rays!
        double distSq = eyeLoc.distanceSquared(entity.getLocation());
        if (distSq > 25.0) {
            return false;
        }

        // 4. Perimeter sampling for point-blank peekers & narrow slits (< 5m)
        double minX = box.getMinX() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        double centerY = box.getCenterY();

        if (isRayClear(world, eyeLoc, start, new Vector(minX, centerY, minZ))) return true;
        if (isRayClear(world, eyeLoc, start, new Vector(maxX, centerY, minZ))) return true;
        if (isRayClear(world, eyeLoc, start, new Vector(minX, centerY, maxZ))) return true;
        if (isRayClear(world, eyeLoc, start, new Vector(maxX, centerY, maxZ))) return true;

        // Bottom of tall entities (mobs/armor stands)
        if (!(entity instanceof Item)) {
            Vector bottom = new Vector(box.getCenterX(), box.getMinY() + 0.1, box.getCenterZ());
            if (isRayClear(world, eyeLoc, start, bottom)) return true;
        }

        return false;
    }

    /**
     * Voxel-Accurate Multi-Layer Ray Marching Engine:
     * Traverses multiple perforated barriers (copper grates, doors with windows, iron bars, chains).
     * If a ray strikes a HOLE in a perforated barrier, it continues through to the other side.
     * If a ray strikes SOLID material (solid door bottom, door frame, solid wall), it is immediately BLOCKED.
     */
    public boolean isRayClear(World world, Location origin, Vector start, Vector targetPoint) {
        Vector dir = targetPoint.clone().subtract(start);
        double totalDist = dir.length();
        if (totalDist < 0.05) {
            return true;
        }
        dir.normalize();

        Vector currentStart = start.clone();
        Location currentLoc = origin.clone();
        double remainingDist = Math.max(0.01, totalDist - 0.03);

        int maxHops = 8;
        for (int hop = 0; hop < maxHops; hop++) {
            RayTraceResult hit = world.rayTraceBlocks(
                    currentLoc,
                    dir,
                    remainingDist,
                    FluidCollisionMode.NEVER,
                    true,
                    b -> !isCompletelyIgnored(b)
            );

            if (hit == null) {
                // Ray reached target without hitting any collision!
                return true;
            }

            Block hitBlock = hit.getHitBlock();
            Vector hitPos = hit.getHitPosition();
            if (hitBlock == null || hitPos == null) {
                return false;
            }

            // O(1) Fast Exit: If block cannot have holes/apertures (stone, deepslate, dirt, etc.), immediately blocked!
            if (!HOLE_CAPABLE_MATERIALS[hitBlock.getType().ordinal()]) {
                return false;
            }

            // Check if ray struck through a hole/aperture in this block
            if (isHitInsideHole(hitBlock, hitPos, dir)) {
                // Ray pierced a hole (e.g. window in top half of door or open trapdoor)!
                // Step past the thin 3/16 door/trapdoor plane along the ray direction
                Vector advanced = hitPos.clone().add(dir.clone().multiply(0.22));
                double stepDist = advanced.distance(currentStart);
                remainingDist -= stepDist;
                if (remainingDist <= 0.02) {
                    return true;
                }
                currentStart = advanced;
                currentLoc = advanced.toLocation(world);
            } else {
                // Struck solid part of the block (solid door bottom, frame, solid block) -> blocked!
                return false;
            }
        }

        return false;
    }

    private double clipRayDistance(World world, Location start, Vector dir, double maxDist) {
        RayTraceResult hit = world.rayTraceBlocks(
                start,
                dir,
                maxDist,
                FluidCollisionMode.NEVER,
                true,
                b -> !isCompletelyIgnored(b)
        );
        if (hit != null) {
            return Math.max(0.0, hit.getHitPosition().distance(start.toVector()) - 0.2);
        }
        return maxDist;
    }

    public static boolean isCompletelyIgnored(Block block) {
        if (block.isPassable()) {
            return true;
        }
        return IGNORED_MATERIALS[block.getType().ordinal()];
    }

    /**
     * Voxel-level discrimination for perforated blocks:
     * Returns TRUE only if the ray strikes an actual physical opening / hole.
     * Returns FALSE if the ray strikes a solid frame, solid door bottom, or solid body.
     */
    public static boolean isHitInsideHole(Block block, Vector hitPos, Vector rayDir) {
        Material mat = block.getType();
        String name = mat.name();

        double bx = block.getX();
        double by = block.getY();
        double bz = block.getZ();

        double lx = Math.max(0.0, Math.min(1.0, hitPos.getX() - bx));
        double ly = Math.max(0.0, Math.min(1.0, hitPos.getY() - by));
        double lz = Math.max(0.0, Math.min(1.0, hitPos.getZ() - bz));

        // 1. DOORS
        if (name.endsWith("_DOOR")) {
            // Completely solid doors (Spruce, Dark Oak, Pale Oak, Crimson, Warped) have NO holes anywhere!
            if (name.contains("SPRUCE") || name.contains("DARK_OAK") || name.contains("PALE_OAK") ||
                name.contains("CRIMSON") || name.contains("WARPED")) {
                return false;
            }

            if (!(block.getBlockData() instanceof Door)) {
                return false;
            }
            Door door = (Door) block.getBlockData();

            // BOTTOM HALF: 100% solid in Copper, Oak, Iron, Birch, Cherry, Mangrove doors!
            if (door.getHalf() == Bisected.Half.BOTTOM) {
                if (name.contains("ACACIA")) {
                    double modY = (ly * 16.0) % 4.0;
                    return modY > 1.0 && modY < 3.0 && lx > 0.15 && lx < 0.85 && lz > 0.15 && lz < 0.85;
                }
                if (name.contains("BAMBOO")) {
                    double hCoord = (door.getFacing() == BlockFace.NORTH || door.getFacing() == BlockFace.SOUTH) ? lx : lz;
                    double modH = (hCoord * 16.0) % 4.0;
                    return modH > 1.2 && modH < 2.8;
                }
                // All other doors (COPPER, OAK, IRON, etc.): bottom half is completely solid!
                return false;
            }

            // TOP HALF: contains window/hole
            BlockFace facing = door.getFacing();
            boolean open = door.isOpen();
            double u;
            if (open) {
                u = (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) ? lz : lx;
            } else {
                u = (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) ? lx : lz;
            }

            // Frame border (solid)
            if (u < 0.16 || u > 0.84 || ly < 0.12 || ly > 0.88) {
                return false;
            }

            // Central vertical divider / mullion (solid)
            if (u >= 0.44 && u <= 0.56) {
                return false;
            }

            // Copper door horizontal divider (solid)
            if (name.contains("COPPER") && ly >= 0.44 && ly <= 0.56) {
                return false;
            }

            // Cleanly inside window opening
            return true;
        }

        // 2. TRAPDOORS
        if (name.endsWith("_TRAPDOOR")) {
            if (name.contains("SPRUCE") || name.contains("DARK_OAK") || name.contains("PALE_OAK") ||
                name.contains("CRIMSON") || name.contains("WARPED")) {
                return false;
            }
            if (!(block.getBlockData() instanceof TrapDoor)) {
                return false;
            }
            TrapDoor trapdoor = (TrapDoor) block.getBlockData();
            if (trapdoor.isOpen()) {
                double u = (trapdoor.getFacing() == BlockFace.NORTH || trapdoor.getFacing() == BlockFace.SOUTH) ? lx : lz;
                return u > 0.18 && u < 0.82 && ly > 0.18 && ly < 0.82;
            } else {
                return lx > 0.18 && lx < 0.82 && lz > 0.18 && lz < 0.82;
            }
        }

        // 3. COPPER GRATES (COPPER_GRATE, WEATHERED, EXPOSED, OXIDIZED, WAXED)
        if (name.contains("GRATE")) {
            boolean inHoleX = (lx > 0.15 && lx < 0.45) || (lx > 0.55 && lx < 0.85);
            boolean inHoleY = (ly > 0.15 && ly < 0.45) || (ly > 0.55 && ly < 0.85);
            boolean inHoleZ = (lz > 0.15 && lz < 0.45) || (lz > 0.55 && lz < 0.85);
            int holeAxes = (inHoleX ? 1 : 0) + (inHoleY ? 1 : 0) + (inHoleZ ? 1 : 0);
            return holeAxes >= 2;
        }

        // 4. BARS (IRON_BARS, COPPER_BARS)
        if (name.contains("BARS")) {
            boolean onCenterPost = (lx >= 0.42 && lx <= 0.58) && (lz >= 0.42 && lz <= 0.58);
            return !onCenterPost;
        }

        // 5. CHAINS (CHAIN, COPPER_CHAIN)
        if (name.contains("CHAIN")) {
            boolean onChain = (lx >= 0.40 && lx <= 0.60) && (lz >= 0.40 && lz <= 0.60);
            return !onChain;
        }

        // 6. FENCES & FENCE GATES
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE")) {
            if (ly > 0.38 && ly < 0.75) return true;
            if (ly > 0.88) return true;
            return false;
        }

        // 7. RODS (LIGHTNING_ROD, END_ROD)
        if (name.contains("ROD")) {
            boolean onRod = (lx >= 0.40 && lx <= 0.60) && (lz >= 0.40 && lz <= 0.60);
            return !onRod;
        }

        // 8. LANTERNS / BELLS
        if (name.contains("LANTERN") || name.equals("BELL")) {
            return lx < 0.30 || lx > 0.70 || lz < 0.30 || lz > 0.70;
        }

        // All other standard blocks (Stone, Wood, Wool, Bricks, Chest bodies) are SOLID
        return false;
    }

    private void syncVehicle(Player viewer, Player target, boolean visible) {
        Entity vehicle = target.getVehicle();
        if (vehicle != null) {
            if (visible) reveal(viewer, vehicle);
            else conceal(viewer, vehicle);
        }
    }

    public boolean shouldFilter(Entity entity, boolean monsters, boolean animals, boolean misc) {
        if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast || entity instanceof Phantom || entity instanceof Boss) return monsters;
        if (entity instanceof Animals || entity instanceof Ambient || entity instanceof WaterMob || entity instanceof NPC || entity instanceof Golem || entity instanceof Allay) return animals;
        if (entity instanceof ArmorStand || entity instanceof Item || entity instanceof ItemFrame || entity instanceof Painting || entity instanceof Vehicle) return misc;
        return false;
    }

    /**
     * Anti-Freecam Wall & Reach Verification for Block Interactions.
     * Ensures player has direct physical line-of-sight and is within reach distance.
     */
    public boolean canPlayerReachBlock(Player player, Block targetBlock, double maxReach) {
        if (player.getGameMode() == GameMode.SPECTATOR) return true;
        Location eyeLoc = player.getEyeLocation();
        World world = player.getWorld();
        if (!world.equals(targetBlock.getWorld())) return false;

        Location blockCenter = targetBlock.getLocation().add(0.5, 0.5, 0.5);
        double distSq = eyeLoc.distanceSquared(blockCenter);
        if (distSq > maxReach * maxReach) return false;

        Vector start = eyeLoc.toVector();

        // 1. Center of target block check
        Vector dirToCenter = blockCenter.toVector().subtract(start);
        double distToCenter = dirToCenter.length();
        if (distToCenter > 0.05) {
            RayTraceResult hit = world.rayTraceBlocks(
                    eyeLoc,
                    dirToCenter.normalize(),
                    distToCenter,
                    FluidCollisionMode.NEVER,
                    true,
                    b -> !isCompletelyIgnored(b)
            );
            if (hit == null || targetBlock.equals(hit.getHitBlock()) || isCompletelyIgnored(hit.getHitBlock())) {
                return true;
            }
        }

        // 2. Multi-face sample (top, bottom, 4 sides) to account for corners/edges
        double bx = targetBlock.getX();
        double by = targetBlock.getY();
        double bz = targetBlock.getZ();
        Vector[] facePoints = new Vector[]{
                new Vector(bx + 0.5, by + 0.95, bz + 0.5),
                new Vector(bx + 0.5, by + 0.05, bz + 0.5),
                new Vector(bx + 0.05, by + 0.5, bz + 0.5),
                new Vector(bx + 0.95, by + 0.5, bz + 0.5),
                new Vector(bx + 0.5, by + 0.5, bz + 0.05),
                new Vector(bx + 0.5, by + 0.5, bz + 0.95)
        };

        for (Vector facePoint : facePoints) {
            Vector dir = facePoint.clone().subtract(start);
            double dist = dir.length();
            if (dist <= 0.05) return true;
            RayTraceResult hit = world.rayTraceBlocks(
                    eyeLoc,
                    dir.normalize(),
                    dist,
                    FluidCollisionMode.NEVER,
                    true,
                    b -> !isCompletelyIgnored(b)
            );
            if (hit == null || targetBlock.equals(hit.getHitBlock()) || isCompletelyIgnored(hit.getHitBlock())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Anti-Freecam Wall & Reach Verification for Entity Interactions and Combat.
     * Prevents attacking or interacting with entities through solid barriers or out of range.
     */
    public boolean canPlayerReachEntity(Player player, Entity targetEntity, double maxReach) {
        if (player.getGameMode() == GameMode.SPECTATOR) return true;
        Location eyeLoc = player.getEyeLocation();
        if (!player.getWorld().equals(targetEntity.getWorld())) return false;
        double distSq = eyeLoc.distanceSquared(targetEntity.getLocation());
        if (distSq > maxReach * maxReach) return false;

        PlayerState viewerState = capturePlayerState(player);
        if (targetEntity instanceof Player) {
            PlayerState targetState = capturePlayerState((Player) targetEntity);
            return evaluatePlayerVisibility(viewerState, targetState);
        } else {
            return evaluateEntityVisibility(viewerState, targetEntity);
        }
    }

    private void reveal(Player viewer, Entity target) {
        if (!viewer.canSee(target)) {
            viewer.showEntity(plugin, target);
        }
    }

    private void conceal(Player viewer, Entity target) {
        if (viewer.canSee(target)) {
            viewer.hideEntity(plugin, target);
        }
    }

    private void revealAllPlayersTo(Player viewer, List<PlayerState> states) {
        for (PlayerState s : states) {
            if (!viewer.equals(s.player)) {
                if (!viewer.canSee(s.player)) {
                    viewer.showEntity(plugin, s.player);
                }
                nametagManager.setNametagVisible(viewer, s.player, false);
            }
        }
    }

    public void cleanupPlayer(Player player) {
        UUID id = player.getUniqueId();
        nametagManager.removeNametag(id);
        clientCameraModes.remove(id);
        clickGraceMap.remove(id);
        combatGraceMap.remove(id);
        entityHysteresis.remove(id);
        playerHysteresis.remove(id);
        for (Map<UUID, Long> inner : combatGraceMap.values()) {
            inner.remove(id);
        }
    }

    public void restoreAll() {
        nametagManager.purgeAll();
        entityHysteresis.clear();
        playerHysteresis.clear();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            for (World w : plugin.getServer().getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (!viewer.equals(e) && !viewer.canSee(e)) {
                        viewer.showEntity(plugin, e);
                    }
                }
            }
        }
        combatGraceMap.clear();
        clickGraceMap.clear();
    }

    private PlayerState capturePlayerState(Player player) {
        UUID id = player.getUniqueId();
        Location eyeLoc = player.getEyeLocation();
        Vector dir = eyeLoc.getDirection().normalize();
        Vector vel = player.getVelocity();
        boolean glowing = player.isGlowing() || player.hasPotionEffect(PotionEffectType.GLOWING);

        return new PlayerState(
                player,
                id,
                player.getWorld(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                eyeLoc,
                dir.getX(),
                dir.getY(),
                dir.getZ(),
                player.getLocation().getYaw(),
                player.getLocation().getPitch(),
                vel,
                player.getBoundingBox(),
                glowing
        );
    }

    private static class PlayerState {
        final Player player;
        final UUID uuid;
        final World world;
        final double x, y, z;
        final Location eyeLoc;
        final double dirX, dirY, dirZ;
        final float yaw;
        final float pitch;
        final Vector velocity;
        final BoundingBox box;
        final boolean isGlowing;

        PlayerState(Player player, UUID uuid, World world, double x, double y, double z,
                    Location eyeLoc, double dirX, double dirY, double dirZ,
                    float yaw, float pitch, Vector velocity,
                    BoundingBox box, boolean isGlowing) {
            this.player = player;
            this.uuid = uuid;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.eyeLoc = eyeLoc;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.velocity = velocity;
            this.box = box;
            this.isGlowing = isGlowing;
        }
    }
}
