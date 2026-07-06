package net.citizensnpcs;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import net.citizensnpcs.api.npc.NPC;

public class CitizensOptimizations {
    private static final String CONFIG_HEADER = "Citizens patched configuration\n"
            + "\n"
            + "Optimization patch author: ShanguanLinG.\n"
            + "\n"
            + "This file contains performance and diagnostic options added by this patched build.\n"
            + "It is intentionally separate from the upstream Citizens config so the original plugin\n"
            + "settings remain clean and easy to compare.\n"
            + "\n"
            + "NPC visibility options reduce tracking and packet output when a player should not\n"
            + "need live NPC updates. These options are conservative by default: they hide bots\n"
            + "when the viewer is too high, too far away, idle for a long time, or when the bot is\n"
            + "outside the viewer's field of view / blocked by terrain. When an NPC becomes visible\n"
            + "again, smooth-reveal spreads the re-spawn packets over multiple ticks to avoid a\n"
            + "short bandwidth spike.\n"
            + "\n"
            + "Performance options control how often player-type NPCs are updated. A movement\n"
            + "update multiplier of 1.0 means normal speed. Lower values such as 0.8 or 0.5 rotate\n"
            + "NPC updates across ticks, reducing CPU and packet pressure at the cost of slightly\n"
            + "less fluid movement.\n"
            + "\n"
            + "Packet monitor options control the built-in web dashboard. The monitor is a diagnostic\n"
            + "tool for short measurement sessions. The display-host value is only the host shown in\n"
            + "generated links; the HTTP server itself binds locally by default in the monitor service.\n"
            + "Keep the token private when exposing the page beyond localhost.\n"
            + "\n"
            + "Quick guide:\n"
            + "- npc.visibility.hide-above-y: hide bots from players at or above this Y level; set -1 to disable.\n"
            + "- npc.visibility.hide-distance: hide bots farther than this distance in blocks; set -1 to disable.\n"
            + "- npc.visibility.hide-display-name: enable display-name hiding for the listed NPC names only.\n"
            + "- npc.visibility.hide-display-name-names: raw NPC names whose floating name should be hidden.\n"
            + "- npc.visibility.hide-when-player-idle: stop bot tracking for players who have not moved or looked around.\n"
            + "- npc.visibility.hide-when-not-visible: hide bots outside the viewer's likely visible area.\n"
            + "- npc.visibility.smooth-reveal.max-per-player-per-tick: maximum hidden bots restored per player each tick.\n"
            + "- npc.performance.movement-update-multiplier: 1.0 is normal, lower values trade smoothness for performance.\n"
            + "- packet-monitor.max-runtime-seconds: safety timeout for manual diagnostic sessions.\n";
    private static final String FILE_NAME = "patched-config.yml";
    private static CitizensOptimizations instance;

    private final File configFile;

    private double hideBotsAboveY = 128;
    private double hideBotsDistance = 32;
    private boolean hideBotsWhenPlayerIdle = true;
    private double hideBotsWhenPlayerIdleLookThreshold = 0.5;
    private double hideBotsWhenPlayerIdleMovementThreshold = 0.03;
    private int hideBotsWhenPlayerIdleSeconds = 30;
    private boolean hideBotsWhenNotVisible = true;
    private int hideBotsWhenNotVisibleCheckIntervalTicks = 10;
    private double hideBotsWhenNotVisibleHorizontalAngle = 140;
    private boolean hideBotsWhenNotVisibleLineOfSight = true;
    private double hideBotsWhenNotVisibleMinDistance = 12;
    private double hideBotsWhenNotVisibleVerticalAngle = 100;
    private boolean hideDisplayName;
    private Set<String> hideDisplayNameNames = Collections.emptySet();
    private boolean hideNameInTabList = true;
    private boolean humanFastRespawn = true;
    private double npcMovementUpdateMultiplier = 1.0;
    private boolean npcSmoothRevealEnabled = true;
    private int npcSmoothRevealMaxPerPlayerPerTick = 2;
    private int packetMonitorMaxRuntimeSeconds = 600;
    private int packetMonitorPort = 8765;
    private int packetMonitorWindowSeconds = 60;
    private boolean packetMonitorEnabled = true;
    private String packetMonitorDisplayHost = "";
    private String packetMonitorToken = "";

    public CitizensOptimizations(File folder) {
        this.configFile = new File(folder, FILE_NAME);
        instance = this;
        reload(folder);
    }

    public static CitizensOptimizations get() {
        return instance;
    }

    public double hideBotsAboveY() {
        return hideBotsAboveY;
    }

    public double hideBotsDistance() {
        return hideBotsDistance;
    }

    public boolean hideBotsWhenPlayerIdle() {
        return hideBotsWhenPlayerIdle;
    }

    public double hideBotsWhenPlayerIdleLookThreshold() {
        return hideBotsWhenPlayerIdleLookThreshold;
    }

    public double hideBotsWhenPlayerIdleMovementThreshold() {
        return hideBotsWhenPlayerIdleMovementThreshold;
    }

    public int hideBotsWhenPlayerIdleSeconds() {
        return hideBotsWhenPlayerIdleSeconds;
    }

    public boolean hideBotsWhenNotVisible() {
        return hideBotsWhenNotVisible;
    }

    public int hideBotsWhenNotVisibleCheckIntervalTicks() {
        return hideBotsWhenNotVisibleCheckIntervalTicks;
    }

    public double hideBotsWhenNotVisibleHorizontalAngle() {
        return hideBotsWhenNotVisibleHorizontalAngle;
    }

    public boolean hideBotsWhenNotVisibleLineOfSight() {
        return hideBotsWhenNotVisibleLineOfSight;
    }

    public double hideBotsWhenNotVisibleMinDistance() {
        return hideBotsWhenNotVisibleMinDistance;
    }

    public double hideBotsWhenNotVisibleVerticalAngle() {
        return hideBotsWhenNotVisibleVerticalAngle;
    }

    public boolean hideDisplayName() {
        return hideDisplayName;
    }

    public boolean hideDisplayName(NPC npc) {
        if (!hideDisplayName || npc == null || hideDisplayNameNames.isEmpty()) {
            return false;
        }
        return hideDisplayNameNames.contains(normalizeName(npc.getName()))
                || hideDisplayNameNames.contains(normalizeName(npc.getRawName()))
                || hideDisplayNameNames.contains(normalizeName(npc.getFullName()));
    }

    public boolean hideNameInTabList() {
        return hideNameInTabList;
    }

    public boolean humanFastRespawn() {
        return humanFastRespawn;
    }

    public double npcMovementUpdateMultiplier() {
        return npcMovementUpdateMultiplier;
    }

    public boolean npcSmoothRevealEnabled() {
        return npcSmoothRevealEnabled;
    }

    public int npcSmoothRevealMaxPerPlayerPerTick() {
        return npcSmoothRevealMaxPerPlayerPerTick;
    }

    public boolean packetMonitorEnabled() {
        return packetMonitorEnabled;
    }

    public String packetMonitorDisplayHost() {
        return packetMonitorDisplayHost;
    }

    public int packetMonitorMaxRuntimeSeconds() {
        return packetMonitorMaxRuntimeSeconds;
    }

    public int packetMonitorPort() {
        return packetMonitorPort;
    }

    public String packetMonitorToken() {
        return packetMonitorToken;
    }

    public int packetMonitorWindowSeconds() {
        return packetMonitorWindowSeconds;
    }

    public void reload(File folder) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        setDefault(config, "npc.human.fastrespawn", humanFastRespawn);
        setDefault(config, "npc.visibility.hide-above-y", hideBotsAboveY);
        setDefault(config, "npc.visibility.hide-distance", hideBotsDistance);
        setDefault(config, "npc.visibility.hide-when-player-idle.enabled", hideBotsWhenPlayerIdle);
        setDefault(config, "npc.visibility.hide-when-player-idle.seconds", hideBotsWhenPlayerIdleSeconds);
        setDefault(config, "npc.visibility.hide-when-player-idle.movement-threshold",
                hideBotsWhenPlayerIdleMovementThreshold);
        setDefault(config, "npc.visibility.hide-when-player-idle.look-threshold", hideBotsWhenPlayerIdleLookThreshold);
        setDefault(config, "npc.visibility.hide-when-not-visible.enabled", hideBotsWhenNotVisible);
        setDefault(config, "npc.visibility.hide-when-not-visible.min-distance", hideBotsWhenNotVisibleMinDistance);
        setDefault(config, "npc.visibility.hide-when-not-visible.horizontal-angle", hideBotsWhenNotVisibleHorizontalAngle);
        setDefault(config, "npc.visibility.hide-when-not-visible.vertical-angle", hideBotsWhenNotVisibleVerticalAngle);
        setDefault(config, "npc.visibility.hide-when-not-visible.line-of-sight", hideBotsWhenNotVisibleLineOfSight);
        setDefault(config, "npc.visibility.hide-when-not-visible.check-interval-ticks",
                hideBotsWhenNotVisibleCheckIntervalTicks);
        setDefault(config, "npc.visibility.hide-display-name", hideDisplayName);
        setDefault(config, "npc.visibility.hide-display-name-names", Collections.emptyList());
        setDefault(config, "npc.visibility.hide-name-in-tab-list", hideNameInTabList);
        setDefault(config, "npc.visibility.smooth-reveal.enabled", npcSmoothRevealEnabled);
        setDefault(config, "npc.visibility.smooth-reveal.max-per-player-per-tick",
                npcSmoothRevealMaxPerPlayerPerTick);
        setDefault(config, "npc.performance.movement-update-multiplier", npcMovementUpdateMultiplier);
        setDefault(config, "packet-monitor.enabled", packetMonitorEnabled);
        setDefault(config, "packet-monitor.display-host", defaultDisplayHost());
        setDefault(config, "packet-monitor.port", packetMonitorPort);
        setDefault(config, "packet-monitor.token", packetMonitorToken);
        setDefault(config, "packet-monitor.max-runtime-seconds", packetMonitorMaxRuntimeSeconds);
        setDefault(config, "packet-monitor.window-seconds", packetMonitorWindowSeconds);

        humanFastRespawn = config.getBoolean("npc.human.fastrespawn", humanFastRespawn);
        hideBotsAboveY = config.getDouble("npc.visibility.hide-above-y", hideBotsAboveY);
        hideBotsDistance = config.getDouble("npc.visibility.hide-distance", hideBotsDistance);
        hideBotsWhenPlayerIdle = config.getBoolean("npc.visibility.hide-when-player-idle.enabled",
                hideBotsWhenPlayerIdle);
        hideBotsWhenPlayerIdleSeconds = Math.max(1,
                config.getInt("npc.visibility.hide-when-player-idle.seconds", hideBotsWhenPlayerIdleSeconds));
        hideBotsWhenPlayerIdleMovementThreshold = Math.max(0,
                config.getDouble("npc.visibility.hide-when-player-idle.movement-threshold",
                        hideBotsWhenPlayerIdleMovementThreshold));
        hideBotsWhenPlayerIdleLookThreshold = Math.max(0,
                config.getDouble("npc.visibility.hide-when-player-idle.look-threshold",
                        hideBotsWhenPlayerIdleLookThreshold));
        hideBotsWhenNotVisible = config.getBoolean("npc.visibility.hide-when-not-visible.enabled",
                hideBotsWhenNotVisible);
        hideBotsWhenNotVisibleMinDistance = Math.max(0, config.getDouble(
                "npc.visibility.hide-when-not-visible.min-distance", hideBotsWhenNotVisibleMinDistance));
        hideBotsWhenNotVisibleHorizontalAngle = Math.max(1, Math.min(360, config.getDouble(
                "npc.visibility.hide-when-not-visible.horizontal-angle", hideBotsWhenNotVisibleHorizontalAngle)));
        hideBotsWhenNotVisibleVerticalAngle = Math.max(1, Math.min(180, config.getDouble(
                "npc.visibility.hide-when-not-visible.vertical-angle", hideBotsWhenNotVisibleVerticalAngle)));
        hideBotsWhenNotVisibleLineOfSight = config.getBoolean("npc.visibility.hide-when-not-visible.line-of-sight",
                hideBotsWhenNotVisibleLineOfSight);
        hideBotsWhenNotVisibleCheckIntervalTicks = Math.max(1, config.getInt(
                "npc.visibility.hide-when-not-visible.check-interval-ticks",
                hideBotsWhenNotVisibleCheckIntervalTicks));
        hideDisplayName = config.getBoolean("npc.visibility.hide-display-name", hideDisplayName);
        hideDisplayNameNames = readNameSet(config, "npc.visibility.hide-display-name-names");
        hideNameInTabList = config.getBoolean("npc.visibility.hide-name-in-tab-list", hideNameInTabList);
        npcSmoothRevealEnabled = config.getBoolean("npc.visibility.smooth-reveal.enabled", npcSmoothRevealEnabled);
        npcSmoothRevealMaxPerPlayerPerTick = Math.max(1, config.getInt(
                "npc.visibility.smooth-reveal.max-per-player-per-tick", npcSmoothRevealMaxPerPlayerPerTick));
        npcMovementUpdateMultiplier = Math.max(0.01,
                config.getDouble("npc.performance.movement-update-multiplier", npcMovementUpdateMultiplier));
        packetMonitorEnabled = config.getBoolean("packet-monitor.enabled", packetMonitorEnabled);
        packetMonitorDisplayHost = config.getString("packet-monitor.display-host", packetMonitorDisplayHost);
        packetMonitorPort = config.getInt("packet-monitor.port", packetMonitorPort);
        packetMonitorToken = config.getString("packet-monitor.token", packetMonitorToken);
        packetMonitorMaxRuntimeSeconds = Math.max(1,
                config.getInt("packet-monitor.max-runtime-seconds", packetMonitorMaxRuntimeSeconds));
        packetMonitorWindowSeconds = Math.max(1,
                config.getInt("packet-monitor.window-seconds", packetMonitorWindowSeconds));

        save(config, configFile);
    }

    private String defaultDisplayHost() {
        return "127.0.0.1";
    }

    private void save(YamlConfiguration config, File file) {
        try {
            config.options().header(CONFIG_HEADER);
            config.options().copyHeader(true);
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setDefault(YamlConfiguration config, String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }

    private Set<String> readNameSet(YamlConfiguration config, String path) {
        Set<String> names = new HashSet<String>();
        for (String name : config.getStringList(path)) {
            String normalized = normalizeName(name);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        return names.isEmpty() ? Collections.<String>emptySet() : names;
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return ChatColor.stripColor(name).trim().toLowerCase(Locale.ROOT);
    }
}
