package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingSet;

import net.citizensnpcs.CitizensOptimizations;
import net.citizensnpcs.api.event.NPCLinkToPlayerEvent;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.event.NPCUnlinkFromPlayerEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.util.NMS;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTrackerEntry;
import net.minecraft.server.v1_8_R3.MovingObjectPosition;
import net.minecraft.server.v1_8_R3.Vec3D;

public class PlayerlistTrackerEntry extends EntityTrackerEntry {
    private static final Map<Integer, PlayerActivity> PLAYER_ACTIVITY = new HashMap<Integer, PlayerActivity>();
    private static final Map<Integer, VisibilityBudget> VISIBILITY_BUDGETS = new HashMap<Integer, VisibilityBudget>();

    private Map<EntityPlayer, Boolean> trackingMap;
    private final Set<Integer> hiddenPlayers = new HashSet<Integer>();
    private final Map<Integer, VisibilityCache> visibilityCache = new HashMap<Integer, VisibilityCache>();

    public PlayerlistTrackerEntry(Entity entity, int i, int j, boolean flag) {
        super(entity, i, j, flag);
        if (TRACKING_MAP_SETTER != null) {
            try {
                Map<EntityPlayer, Boolean> delegate = (Map<EntityPlayer, Boolean>) TRACKING_MAP_GETTER.invoke(this);
                trackingMap = delegate;
                TRACKING_MAP_SETTER.invoke(this, new ForwardingMap<EntityPlayer, Boolean>() {
                    @Override
                    protected Map<EntityPlayer, Boolean> delegate() {
                        return delegate;
                    }

                    @Override
                    public Boolean put(EntityPlayer player, Boolean value) {
                        Boolean res = super.put(player, value);
                        if (res == null) {
                            updateLastPlayer(player);
                        }
                        return res;
                    }

                    @Override
                    public Boolean remove(Object conn) {
                        Boolean removed = super.remove(conn);
                        if (removed) {
                            if (conn instanceof EntityPlayer) {
                                visibilityCache.remove(((EntityPlayer) conn).getId());
                            }
                            Bukkit.getPluginManager().callEvent(new NPCUnlinkFromPlayerEvent(
                                    ((NPCHolder) tracker).getNPC(), ((EntityPlayer) conn).getBukkitEntity()));
                        }
                        return removed;
                    }
                });
            } catch (Throwable e) {
                e.printStackTrace();
            }
        } else {
            try {
                Set<EntityPlayer> delegate = super.trackedPlayers;
                TRACKING_SET_SETTER.invoke(this, new ForwardingSet<EntityPlayer>() {
                    @Override
                    public boolean add(EntityPlayer player) {
                        boolean res = super.add(player);
                        if (res) {
                            updateLastPlayer(player);
                        }
                        return res;
                    }

                    @Override
                    protected Set<EntityPlayer> delegate() {
                        return delegate;
                    }

                    @Override
                    public boolean remove(Object conn) {
                        boolean removed = super.remove(conn);
                        if (removed) {
                            if (conn instanceof EntityPlayer) {
                                visibilityCache.remove(((EntityPlayer) conn).getId());
                            }
                            Bukkit.getPluginManager().callEvent(new NPCUnlinkFromPlayerEvent(
                                    ((NPCHolder) tracker).getNPC(), ((EntityPlayer) conn).getBukkitEntity()));
                        }
                        return removed;
                    }
                });
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public PlayerlistTrackerEntry(EntityTrackerEntry entry) {
        this(entry.tracker, getB(entry), getC(entry), getU(entry));
    }

    private boolean isTracked(EntityPlayer player) {
        return trackingMap != null ? trackingMap.containsKey(player) : trackedPlayers.contains(player);
    }

    private void removeTracked(EntityPlayer player) {
        if (trackingMap != null) {
            trackingMap.remove(player);
        } else {
            trackedPlayers.remove(player);
        }
    }

    private boolean shouldHideFrom(EntityPlayer player) {
        if (!(tracker instanceof NPCHolder))
            return false;

        CitizensOptimizations config = CitizensOptimizations.get();
        if (config == null)
            return false;

        double hideAboveY = config.hideBotsAboveY();
        if (hideAboveY >= 0 && player.locY >= hideAboveY)
            return true;

        if (shouldHideWhenPlayerIdle(player, config))
            return true;

        double hideDistance = config.hideBotsDistance();
        if (hideDistance > 0) {
            double dx = tracker.locX - player.locX;
            double dy = tracker.locY - player.locY;
            double dz = tracker.locZ - player.locZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > hideDistance * hideDistance)
                return true;
            if (config.hideBotsWhenNotVisible())
                return shouldHideWhenNotVisible(player, config, distanceSquared);
            return false;
        }
        if (config.hideBotsWhenNotVisible()) {
            double dx = tracker.locX - player.locX;
            double dy = tracker.locY - player.locY;
            double dz = tracker.locZ - player.locZ;
            return shouldHideWhenNotVisible(player, config, dx * dx + dy * dy + dz * dz);
        }
        return false;
    }

    private boolean shouldHideWhenPlayerIdle(EntityPlayer player, CitizensOptimizations config) {
        if (!config.hideBotsWhenPlayerIdle())
            return false;

        int playerId = player.getId();
        int now = player.ticksLived;
        PlayerActivity activity = PLAYER_ACTIVITY.get(playerId);
        if (activity == null) {
            PLAYER_ACTIVITY.put(playerId, new PlayerActivity(player, now));
            return false;
        }

        if (activity.update(player, config)) {
            visibilityCache.remove(playerId);
        }
        return now - activity.lastActiveTick >= config.hideBotsWhenPlayerIdleSeconds() * 20;
    }

    private boolean isOutsideViewCone(EntityPlayer player, CitizensOptimizations config) {
        double eyeX = player.locX;
        double eyeY = player.locY + player.getHeadHeight();
        double eyeZ = player.locZ;
        double dx = tracker.locX - eyeX;
        double dy = tracker.locY + Math.max(0.5D, tracker.length * 0.5D) - eyeY;
        double dz = tracker.locZ - eyeZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.001D)
            return false;

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double yawDifference = Math.abs(wrapDegrees(targetYaw - player.yaw));
        if (yawDifference > config.hideBotsWhenNotVisibleHorizontalAngle() * 0.5D)
            return true;

        double targetPitch = Math.toDegrees(-Math.atan2(dy, horizontalDistance));
        double pitchDifference = Math.abs(wrapDegrees(targetPitch - player.pitch));
        return pitchDifference > config.hideBotsWhenNotVisibleVerticalAngle() * 0.5D;
    }

    private boolean isOccludedFrom(EntityPlayer player) {
        if (player.world != tracker.world)
            return true;

        Vec3D eye = new Vec3D(player.locX, player.locY + player.getHeadHeight(), player.locZ);
        Vec3D head = new Vec3D(tracker.locX, tracker.locY + tracker.getHeadHeight(), tracker.locZ);
        if (player.world.rayTrace(eye, head, false, true, false) == null)
            return false;

        Vec3D center = new Vec3D(tracker.locX, tracker.locY + Math.max(0.5D, tracker.length * 0.5D), tracker.locZ);
        MovingObjectPosition result = player.world.rayTrace(eye, center, false, true, false);
        return result != null;
    }

    private boolean shouldHideWhenNotVisible(EntityPlayer player, CitizensOptimizations config, double distanceSquared) {
        double minDistance = config.hideBotsWhenNotVisibleMinDistance();
        if (minDistance > 0 && distanceSquared < minDistance * minDistance)
            return false;

        int playerId = player.getId();
        int now = player.ticksLived;
        VisibilityCache cached = visibilityCache.get(playerId);
        if (cached != null && now < cached.nextCheckTick)
            return cached.hidden;

        boolean hidden = isOutsideViewCone(player, config);
        if (!hidden && config.hideBotsWhenNotVisibleLineOfSight()) {
            hidden = isOccludedFrom(player);
        }
        visibilityCache.put(playerId,
                new VisibilityCache(hidden, now + config.hideBotsWhenNotVisibleCheckIntervalTicks()));
        return hidden;
    }

    private boolean shouldDelayVisibilityChange(EntityPlayer player) {
        CitizensOptimizations config = CitizensOptimizations.get();
        if (config == null || !config.npcSmoothRevealEnabled())
            return false;

        int playerId = player.getId();
        VisibilityBudget budget = VISIBILITY_BUDGETS.get(playerId);
        if (budget == null) {
            budget = new VisibilityBudget();
            VISIBILITY_BUDGETS.put(playerId, budget);
        }
        return !budget.tryAcquire(player.ticksLived, config.npcSmoothRevealMaxPerPlayerPerTick());
    }

    private boolean shouldDelayReveal(EntityPlayer player) {
        int playerId = player.getId();
        if (!hiddenPlayers.contains(playerId))
            return false;

        if (shouldDelayVisibilityChange(player))
            return true;

        hiddenPlayers.remove(playerId);
        return false;
    }

    public void updateLastPlayer(EntityPlayer lastUpdatedPlayer) {
        if (lastUpdatedPlayer != null) {
            Bukkit.getPluginManager().callEvent(
                    new NPCLinkToPlayerEvent(((NPCHolder) tracker).getNPC(), lastUpdatedPlayer.getBukkitEntity(), false));
            lastUpdatedPlayer = null;
        }
    }

    @Override
    public void updatePlayer(final EntityPlayer entityplayer) {
        if (entityplayer instanceof EntityHumanNPC)
            return;
        if (shouldHideFrom(entityplayer)) {
            if (isTracked(entityplayer)) {
                if (shouldDelayVisibilityChange(entityplayer))
                    return;
                hiddenPlayers.add(entityplayer.getId());
                clear(entityplayer);
                removeTracked(entityplayer);
            } else {
                hiddenPlayers.add(entityplayer.getId());
            }
            return;
        }
        if (!isTracked(entityplayer) && tracker instanceof NPCHolder) {
            if (shouldDelayReveal(entityplayer))
                return;
            NPC npc = ((NPCHolder) tracker).getNPC();
            NPCSeenByPlayerEvent event = new NPCSeenByPlayerEvent(npc, entityplayer.getBukkitEntity());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled())
                return;
            Integer trackingRange = npc.data().get(NPC.Metadata.TRACKING_RANGE);
            if (trackingRange != null && npc.data().get("last-tracking-range", -1) != b) {
                b = trackingRange;
                npc.data().set("last-tracking-range", trackingRange);
            }
        }
        super.updatePlayer(entityplayer);
    }

    private static int getB(EntityTrackerEntry entry) {
        try {
            Entity entity = entry.tracker;
            if (entity instanceof NPCHolder)
                return ((NPCHolder) entity).getNPC().data().get(NPC.Metadata.TRACKING_RANGE, (Integer) B.get(entry));
            return (Integer) B.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static int getC(EntityTrackerEntry entry) {
        try {
            return (Integer) C.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static Set<org.bukkit.entity.Player> getSeenBy(EntityTrackerEntry tracker) {
        if (TRACKING_MAP_GETTER != null) {
            Map<EntityPlayer, Boolean> delegate;
            try {
                delegate = (Map<EntityPlayer, Boolean>) TRACKING_MAP_GETTER.invoke(tracker);
            } catch (Throwable e) {
                return null;
            }
            return delegate.keySet().stream().map((Function<? super EntityPlayer, ? extends CraftPlayer>) EntityPlayer::getBukkitEntity).collect(Collectors.toSet());
        } else
            return tracker.trackedPlayers.stream().map((Function<? super EntityPlayer, ? extends CraftPlayer>) EntityPlayer::getBukkitEntity).collect(Collectors.toSet());
    }

    public static void markActive(EntityPlayer player) {
        PlayerActivity activity = PLAYER_ACTIVITY.get(player.getId());
        if (activity == null) {
            PLAYER_ACTIVITY.put(player.getId(), new PlayerActivity(player, player.ticksLived));
            return;
        }
        activity.markActive(player);
    }

    private static boolean getU(EntityTrackerEntry entry) {
        try {
            return (Boolean) U.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static double wrapDegrees(double angle) {
        angle %= 360.0D;
        if (angle >= 180.0D) {
            angle -= 360.0D;
        }
        if (angle < -180.0D) {
            angle += 360.0D;
        }
        return angle;
    }

    private static class PlayerActivity {
        private int lastActiveTick;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private float lastPitch;

        private PlayerActivity(EntityPlayer player, int now) {
            lastActiveTick = now;
            record(player);
        }

        private void record(EntityPlayer player) {
            lastX = player.locX;
            lastY = player.locY;
            lastZ = player.locZ;
            lastYaw = player.yaw;
            lastPitch = player.pitch;
        }

        private boolean update(EntityPlayer player, CitizensOptimizations config) {
            double movementThreshold = config.hideBotsWhenPlayerIdleMovementThreshold();
            double dx = player.locX - lastX;
            double dy = player.locY - lastY;
            double dz = player.locZ - lastZ;
            boolean moved = dx * dx + dy * dy + dz * dz > movementThreshold * movementThreshold;
            boolean looked = Math.abs(wrapDegrees(player.yaw - lastYaw)) > config.hideBotsWhenPlayerIdleLookThreshold()
                    || Math.abs(wrapDegrees(player.pitch - lastPitch)) > config.hideBotsWhenPlayerIdleLookThreshold();
            if (!moved && !looked)
                return false;

            lastActiveTick = player.ticksLived;
            record(player);
            return true;
        }

        private void markActive(EntityPlayer player) {
            lastActiveTick = player.ticksLived;
            record(player);
        }
    }

    private static class VisibilityBudget {
        private int tick = -1;
        private int used;

        private boolean tryAcquire(int currentTick, int maxPerTick) {
            if (tick != currentTick) {
                tick = currentTick;
                used = 0;
            }
            if (used >= maxPerTick)
                return false;
            used++;
            return true;
        }
    }

    private static class VisibilityCache {
        private final boolean hidden;
        private final int nextCheckTick;

        private VisibilityCache(boolean hidden, int nextCheckTick) {
            this.hidden = hidden;
            this.nextCheckTick = nextCheckTick;
        }
    }

    private static Field B = NMS.getField(EntityTrackerEntry.class, "b");
    private static Field C = NMS.getField(EntityTrackerEntry.class, "c");
    private static MethodHandle TRACKING_MAP_GETTER;
    private static MethodHandle TRACKING_MAP_SETTER;
    private static final MethodHandle TRACKING_SET_SETTER = NMS.getFirstFinalSetter(EntityTrackerEntry.class,
            Set.class);
    private static Field U = NMS.getField(EntityTrackerEntry.class, "u");
    static {
        try {
            // Old paper versions override the tracked player set to be a map
            if (EntityTrackerEntry.class.getField("trackedPlayerMap") != null) {
                TRACKING_MAP_SETTER = NMS.getFirstSetter(EntityTrackerEntry.class, Map.class);
                TRACKING_MAP_GETTER = NMS.getFirstGetter(EntityTrackerEntry.class, Map.class);
            }
        } catch (Exception e) {
        }
    }
}
