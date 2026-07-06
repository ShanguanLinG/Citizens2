package net.citizensnpcs.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.CitizensOptimizations;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.ai.NPCHolder;

public class PacketMonitorService {
    private static final int DEFAULT_HISTORY_SECONDS = 300;
    private static final int MAX_TOP_ROWS = 10;
    private static final String BIND_HOST = "0.0.0.0";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<Class<?>, java.lang.reflect.Field[]> REFLECTION_FIELDS = new ConcurrentHashMap<>();
    private static volatile PacketMonitorService instance;

    private final Citizens plugin;
    private final int historySeconds;
    private final Bucket[] buckets;
    private final Object lock = new Object();
    private String captureMode = "limited";
    private ExecutorService executor;
    private String host;
    private HttpServer server;
    private String token;
    private int port;
    private boolean runtimeEnabled;
    private BukkitTask autoStopTask;
    private long stopAtMillis;

    public PacketMonitorService(Citizens plugin) {
        this.plugin = plugin;
        this.historySeconds = Math.max(DEFAULT_HISTORY_SECONDS, CitizensOptimizations.get().packetMonitorWindowSeconds());
        this.buckets = new Bucket[historySeconds];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket();
        }
        instance = this;
    }

    public static PacketMonitorService get() {
        return instance;
    }

    public void clearProtocolCapture() {
        captureMode = "limited";
    }

    public void disableRuntime() {
        runtimeEnabled = false;
        stop();
    }

    public boolean enableRuntime() {
        if (!CitizensOptimizations.get().packetMonitorEnabled())
            return false;
        runtimeEnabled = true;
        reset();
        start();
        return isRunning();
    }

    public String getUrl() {
        if (!isRunning())
            return "disabled";
        return getPrimaryUrl();
    }

    public String getCaptureMode() {
        return captureMode;
    }

    public List<String> getUrls() {
        if (!isRunning())
            return Collections.singletonList("disabled");
        List<String> urls = new ArrayList<>();
        String configuredHost = CitizensOptimizations.get().packetMonitorDisplayHost();
        if (!isWildcardHost(configuredHost)) {
            urls.add(buildUrl(configuredHost));
        } else if (isWildcardHost(host)) {
            String publicAddress = findNonLoopbackIPv4();
            if (publicAddress != null) {
                urls.add(buildUrl(publicAddress));
            }
        } else {
            urls.add(buildUrl(host));
        }
        return urls;
    }

    public int getRemainingSeconds() {
        if (!isRunning() || stopAtMillis <= 0)
            return 0;
        return Math.max(0, (int) ((stopAtMillis - System.currentTimeMillis() + 999L) / 1000L));
    }

    public boolean hasProtocolCapture() {
        return "protocol".equals(captureMode);
    }

    public boolean isRunning() {
        return server != null;
    }

    public void markProtocolCapture() {
        captureMode = "protocol";
    }

    public void recordInternalPacket(Player source, Player receiver, Object packet) {
        if (!isRunning() || hasProtocolCapture())
            return;
        NPC npc = source instanceof NPCHolder ? ((NPCHolder) source).getNPC() : null;
        record("internal", packet == null ? "unknown" : packet.getClass().getSimpleName(), receiver, npc,
                estimatePacketBytes(packet == null ? "unknown" : packet.getClass().getSimpleName(), packet));
    }

    public void recordProtocolPacket(String packetType, Player receiver, NPC npc, int estimatedBytes) {
        if (!isRunning())
            return;
        record("protocol", packetType, receiver, npc, estimatedBytes);
    }

    public void reset() {
        synchronized (lock) {
            for (Bucket bucket : buckets) {
                bucket.reset(-1);
            }
        }
    }

    public void restart() {
        stop();
        if (runtimeEnabled) {
            start();
        }
    }

    public void start() {
        if (isRunning())
            return;
        if (!runtimeEnabled || !CitizensOptimizations.get().packetMonitorEnabled())
            return;
        host = BIND_HOST;
        port = CitizensOptimizations.get().packetMonitorPort();
        token = CitizensOptimizations.get().packetMonitorToken();
        if (token == null || token.trim().isEmpty()) {
            token = generateToken();
        }
        int configuredPort = port;
        IOException failure = null;
        for (int offset = 0; offset < 20; offset++) {
            port = configuredPort + offset;
            try {
                server = HttpServer.create(new InetSocketAddress(host, port), 0);
                failure = null;
                break;
            } catch (IOException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            server = null;
            plugin.getLogger().warning("Could not start packet monitor on " + host + ":" + configuredPort + "-"
                    + (configuredPort + 19) + ": " + failure.getMessage());
            return;
        }
        server.createContext("/", new PageHandler());
        server.createContext("/api/snapshot", new SnapshotHandler());
        server.createContext("/api/reset", new ResetHandler());
        server.createContext("/api/report", new ReportHandler());
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Citizens Packet Monitor");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        scheduleAutoStop();
        plugin.getLogger().info("Packet monitor listening. Run /citizens packetmonitor url for the panel link.");
    }

    public void stop() {
        if (autoStopTask != null) {
            autoStopTask.cancel();
            autoStopTask = null;
        }
        stopAtMillis = 0;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void scheduleAutoStop() {
        int seconds = Math.max(1, CitizensOptimizations.get().packetMonitorMaxRuntimeSeconds());
        stopAtMillis = System.currentTimeMillis() + seconds * 1000L;
        if (autoStopTask != null) {
            autoStopTask.cancel();
        }
        autoStopTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isRunning())
                return;
            runtimeEnabled = false;
            stop();
            plugin.getLogger().info("Packet monitor auto-stopped after " + seconds + " seconds.");
        }, seconds * 20L);
    }

    public String statusLine() {
        return isRunning() ? "running (" + captureMode + ", " + getRemainingSeconds() + "s left)" : "stopped";
    }

    private void add(Map<String, Stat> map, String key, String name, int entityId, int npcId, long bytes) {
        Stat stat = map.get(key);
        if (stat == null) {
            stat = new Stat(key, name, entityId, npcId);
            map.put(key, stat);
        }
        stat.packets++;
        stat.bytes += bytes;
    }

    private void appendReportStats(StringBuilder report, String title, List<Stat> stats, boolean includePackets) {
        report.append('\n').append(title).append('\n');
        report.append(repeat('-', title.length())).append('\n');
        if (stats == null || stats.isEmpty()) {
            report.append("No data\n");
            return;
        }
        if (includePackets) {
            report.append(String.format(Locale.ROOT, "%-36s %12s %12s %10s %10s%n", "Name", "Packets", "KiB",
                    "NPC ID", "Entity ID"));
        } else {
            report.append(String.format(Locale.ROOT, "%-48s %12s%n", "Name", "KiB"));
        }
        for (Stat stat : stats) {
            if (includePackets) {
                report.append(String.format(Locale.ROOT, "%-36s %12d %12.1f %10d %10d%n",
                        truncate(stat.name, 36), stat.packets, stat.bytes / 1024.0D, stat.npcId, stat.entityId));
            } else {
                report.append(String.format(Locale.ROOT, "%-48s %12.1f%n", truncate(stat.name, 48),
                        stat.bytes / 1024.0D));
            }
        }
    }

    private String buildUrl(String address) {
        String displayHost = address;
        if (displayHost != null && displayHost.indexOf(':') != -1 && !displayHost.startsWith("[")) {
            displayHost = "[" + displayHost + "]";
        }
        return "http://" + displayHost + ":" + port + "/t/" + token;
    }

    private static String escape(String text) {
        if (text == null)
            return "";
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    public static int estimatePacketBytes(String packetType) {
        if (packetType == null)
            return 48;
        String key = packetType.toUpperCase(Locale.ROOT);
        if (key.contains("PLAYER_INFO"))
            return 220;
        if (key.contains("NAMED_ENTITY_SPAWN"))
            return 96;
        if (key.contains("SPAWN"))
            return 86;
        if (key.contains("METADATA"))
            return 72;
        if (key.contains("EQUIPMENT"))
            return 52;
        if (key.contains("TELEPORT"))
            return 48;
        if (key.contains("REL_ENTITY_MOVE") || key.contains("LOOK") || key.contains("HEAD_ROTATION"))
            return 24;
        if (key.contains("DESTROY"))
            return 18;
        if (key.contains("SCOREBOARD"))
            return 96;
        return 40;
    }

    public static int estimatePacketBytes(String packetType, Object packet) {
        int base = estimatePacketBytes(packetType);
        if (packet == null)
            return base;
        return Math.max(1, base + estimateObjectBytes(packet, 0));
    }

    private static int estimateObjectBytes(Object value, int depth) {
        if (value == null || depth > 2)
            return 0;
        if (value instanceof CharSequence)
            return Math.min(1024, ((CharSequence) value).length() * 3 + 1);
        if (value instanceof UUID)
            return 16;
        if (value instanceof Number)
            return 8;
        if (value instanceof Boolean)
            return 1;
        if (value instanceof Enum<?>)
            return 1;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            int bytes = length <= 127 ? 1 : length <= 16383 ? 2 : 3;
            int limit = Math.min(length, 24);
            for (int i = 0; i < limit; i++) {
                bytes += estimateObjectBytes(java.lang.reflect.Array.get(value, i), depth + 1);
            }
            return Math.min(4096, bytes);
        }
        if (value instanceof Iterable<?>) {
            int bytes = 1;
            int count = 0;
            for (Object item : (Iterable<?>) value) {
                bytes += estimateObjectBytes(item, depth + 1);
                if (++count >= 24) {
                    break;
                }
            }
            return Math.min(4096, bytes);
        }
        if (value instanceof Map<?, ?>) {
            int bytes = 1;
            int count = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                bytes += estimateObjectBytes(entry.getKey(), depth + 1);
                bytes += estimateObjectBytes(entry.getValue(), depth + 1);
                if (++count >= 24) {
                    break;
                }
            }
            return Math.min(4096, bytes);
        }
        String packageName = type.getName();
        if (packageName.startsWith("java.") || packageName.startsWith("javax."))
            return 0;
        int bytes = 0;
        for (java.lang.reflect.Field field : getEstimatableFields(type)) {
            try {
                bytes += estimateObjectBytes(field.get(value), depth + 1);
                if (bytes > 4096) {
                    return 4096;
                }
            } catch (Throwable ignored) {
            }
        }
        return Math.min(4096, bytes);
    }

    private static java.lang.reflect.Field[] getEstimatableFields(Class<?> type) {
        java.lang.reflect.Field[] cached = REFLECTION_FIELDS.get(type);
        if (cached != null)
            return cached;
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class && !cursor.getName().startsWith("java.")) {
            for (java.lang.reflect.Field field : cursor.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers))
                    continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }
        java.lang.reflect.Field[] array = fields.toArray(new java.lang.reflect.Field[fields.size()]);
        REFLECTION_FIELDS.put(type, array);
        return array;
    }

    private String findNonLoopbackIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual())
                    continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String hostAddress = address.getHostAddress();
                    if (hostAddress != null && hostAddress.indexOf(':') == -1 && !address.isLoopbackAddress()
                            && !address.isAnyLocalAddress()) {
                        return hostAddress;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(32);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private Bucket getBucket(long second) {
        Bucket bucket = buckets[(int) (second % buckets.length)];
        if (bucket.second != second) {
            bucket.reset(second);
        }
        return bucket;
    }

    private String getQueryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null)
            return null;
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String key = idx == -1 ? part : part.substring(0, idx);
            if (!name.equals(key))
                continue;
            String value = idx == -1 ? "" : part.substring(idx + 1);
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (Exception e) {
                return value;
            }
        }
        return null;
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String supplied = getQueryValue(exchange, "token");
        if (token != null && token.equals(supplied))
            return true;
        String path = exchange.getRequestURI().getPath();
        return path != null && path.equals("/t/" + token);
    }

    private boolean isWildcardHost(String value) {
        return value == null || value.trim().isEmpty() || "0.0.0.0".equals(value) || "::".equals(value)
                || "[::]".equals(value);
    }

    private String getPrimaryUrl() {
        String configuredHost = CitizensOptimizations.get().packetMonitorDisplayHost();
        if (!isWildcardHost(configuredHost)) {
            return buildUrl(configuredHost);
        }
        if (isWildcardHost(host)) {
            String publicAddress = findNonLoopbackIPv4();
            if (publicAddress != null) {
                return buildUrl(publicAddress);
            }
            return buildUrl("127.0.0.1");
        }
        return buildUrl(host);
    }

    private String makeReport() {
        long now = System.currentTimeMillis() / 1000L;
        int window = Math.max(1, Math.min(CitizensOptimizations.get().packetMonitorWindowSeconds(), buckets.length));
        long packets = 0;
        long bytes = 0;
        long fiveMinutePackets = 0;
        long fiveMinuteBytes = 0;
        long currentPackets = 0;
        long currentBytes = 0;
        Map<String, Stat> npcs = new HashMap<>();
        Map<String, Stat> packetTypes = new HashMap<>();
        Map<String, Stat> receivers = new HashMap<>();
        Map<String, Map<String, Stat>> npcPackets = new HashMap<>();
        Map<String, Map<String, Stat>> npcReceivers = new HashMap<>();
        Set<String> activeNPCs = new HashSet<>();
        Set<String> activeReceivers = new HashSet<>();
        long[] seriesPackets = new long[window];
        long[] seriesBytes = new long[window];

        synchronized (lock) {
            for (int i = 0; i < Math.min(300, buckets.length); i++) {
                Bucket bucket = buckets[(int) ((now - i) % buckets.length)];
                if (bucket.second != now - i)
                    continue;
                fiveMinutePackets += bucket.packets;
                fiveMinuteBytes += bucket.bytes;
            }
            for (int i = 0; i < window; i++) {
                long second = now - window + 1 + i;
                Bucket bucket = buckets[(int) (second % buckets.length)];
                if (bucket.second != second)
                    continue;
                packets += bucket.packets;
                bytes += bucket.bytes;
                seriesPackets[i] = bucket.packets;
                seriesBytes[i] = bucket.bytes;
                if (second == now) {
                    currentPackets = bucket.packets;
                    currentBytes = bucket.bytes;
                }
                merge(npcs, bucket.npcs);
                merge(packetTypes, bucket.packetTypes);
                merge(receivers, bucket.receivers);
                mergeNested(npcPackets, bucket.npcPackets);
                mergeNested(npcReceivers, bucket.npcReceivers);
                activeNPCs.addAll(bucket.npcs.keySet());
                activeReceivers.addAll(bucket.receivers.keySet());
            }
        }

        List<Stat> topNPCs = top(npcs);
        String selectedKey = topNPCs.isEmpty() ? "" : topNPCs.get(0).key;
        String selectedName = topNPCs.isEmpty() ? "N/A" : topNPCs.get(0).name;
        StringBuilder report = new StringBuilder(8192);
        report.append("Citizens NPC Packet Monitor Report\n");
        report.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        report.append("Capture mode: ").append(captureMode).append(" / detailed estimate\n");
        report.append("Remaining seconds: ").append(getRemainingSeconds()).append('\n');
        report.append("Dashboard URL: ").append(getUrl()).append('\n');
        report.append('\n');
        report.append("Summary\n");
        report.append("-------\n");
        report.append("Current packets/s: ").append(currentPackets).append('\n');
        report.append("Current KiB/s: ").append(String.format(Locale.ROOT, "%.1f", currentBytes / 1024.0D)).append('\n');
        report.append("Active NPCs: ").append(activeNPCs.size()).append('\n');
        report.append("Active receivers: ").append(activeReceivers.size()).append('\n');
        report.append("Last ").append(window).append(" seconds: ").append(packets).append(" packets / ")
                .append(String.format(Locale.ROOT, "%.1f", bytes / 1024.0D)).append(" KiB\n");
        report.append("Last five minutes: ").append(fiveMinutePackets).append(" packets / ")
                .append(String.format(Locale.ROOT, "%.1f", fiveMinuteBytes / 1024.0D)).append(" KiB\n");

        appendReportStats(report, "Top NPCs", topNPCs, true);
        appendReportStats(report, "Top Packet Types", top(packetTypes), true);
        appendReportStats(report, "Top Receivers", top(receivers), true);
        appendReportStats(report, "Highest NPC Packet Breakdown (" + selectedName + ")", top(npcPackets.get(selectedKey)),
                false);
        appendReportStats(report, "Highest NPC Receiver Breakdown (" + selectedName + ")",
                top(npcReceivers.get(selectedKey)), false);

        report.append('\n').append("Per-second series, oldest to newest\n");
        report.append("-----------------------------------\n");
        report.append("Packets: ");
        appendReportSeries(report, seriesPackets);
        report.append('\n');
        report.append("KiB: ");
        for (int i = 0; i < seriesBytes.length; i++) {
            if (i > 0) {
                report.append(", ");
            }
            report.append(String.format(Locale.ROOT, "%.1f", seriesBytes[i] / 1024.0D));
        }
        report.append('\n');
        return report.toString();
    }

    private void appendReportSeries(StringBuilder report, long[] series) {
        for (int i = 0; i < series.length; i++) {
            if (i > 0) {
                report.append(", ");
            }
            report.append(series[i]);
        }
    }

    private String makeSnapshot() {
        long now = System.currentTimeMillis() / 1000L;
        int window = Math.max(1, Math.min(CitizensOptimizations.get().packetMonitorWindowSeconds(), buckets.length));
        long packets = 0;
        long bytes = 0;
        long fiveMinutePackets = 0;
        long fiveMinuteBytes = 0;
        long currentPackets = 0;
        long currentBytes = 0;
        Map<String, Stat> npcs = new HashMap<>();
        Map<String, Stat> packetTypes = new HashMap<>();
        Map<String, Stat> receivers = new HashMap<>();
        Map<String, Map<String, Stat>> npcPackets = new HashMap<>();
        Map<String, Map<String, Stat>> npcReceivers = new HashMap<>();
        Set<String> activeNPCs = new HashSet<>();
        Set<String> activeReceivers = new HashSet<>();
        long[] seriesPackets = new long[window];
        long[] seriesBytes = new long[window];

        synchronized (lock) {
            for (int i = 0; i < Math.min(300, buckets.length); i++) {
                Bucket bucket = buckets[(int) ((now - i) % buckets.length)];
                if (bucket.second != now - i)
                    continue;
                fiveMinutePackets += bucket.packets;
                fiveMinuteBytes += bucket.bytes;
            }
            for (int i = 0; i < window; i++) {
                long second = now - window + 1 + i;
                Bucket bucket = buckets[(int) (second % buckets.length)];
                if (bucket.second != second)
                    continue;
                packets += bucket.packets;
                bytes += bucket.bytes;
                seriesPackets[i] = bucket.packets;
                seriesBytes[i] = bucket.bytes;
                if (second == now) {
                    currentPackets = bucket.packets;
                    currentBytes = bucket.bytes;
                }
                merge(npcs, bucket.npcs);
                merge(packetTypes, bucket.packetTypes);
                merge(receivers, bucket.receivers);
                mergeNested(npcPackets, bucket.npcPackets);
                mergeNested(npcReceivers, bucket.npcReceivers);
                activeNPCs.addAll(bucket.npcs.keySet());
                activeReceivers.addAll(bucket.receivers.keySet());
            }
        }

        List<Stat> topNPCs = top(npcs);
        String selectedKey = topNPCs.isEmpty() ? "" : topNPCs.get(0).key;
        StringBuilder json = new StringBuilder(8192);
        json.append('{');
        json.append("\"running\":").append(isRunning()).append(',');
        json.append("\"captureMode\":\"").append(captureMode).append("\",");
        json.append("\"accuracyMode\":\"detailed estimate\",");
        json.append("\"remainingSeconds\":").append(getRemainingSeconds()).append(',');
        json.append("\"windowSeconds\":").append(window).append(',');
        json.append("\"packetsPerSecond\":").append(currentPackets).append(',');
        json.append("\"bytesPerSecond\":").append(currentBytes).append(',');
        json.append("\"windowPackets\":").append(packets).append(',');
        json.append("\"windowBytes\":").append(bytes).append(',');
        json.append("\"fiveMinutePackets\":").append(fiveMinutePackets).append(',');
        json.append("\"fiveMinuteBytes\":").append(fiveMinuteBytes).append(',');
        json.append("\"activeNPCs\":").append(activeNPCs.size()).append(',');
        json.append("\"activeReceivers\":").append(activeReceivers.size()).append(',');
        appendSeries(json, "seriesPackets", seriesPackets);
        json.append(',');
        appendSeries(json, "seriesBytes", seriesBytes);
        json.append(',');
        appendStats(json, "topNPCs", topNPCs);
        json.append(',');
        appendStats(json, "topPacketTypes", top(packetTypes));
        json.append(',');
        appendStats(json, "topReceivers", top(receivers));
        json.append(',');
        appendStats(json, "selectedNpcPackets", top(npcPackets.get(selectedKey)));
        json.append(',');
        appendStats(json, "selectedNpcReceivers", top(npcReceivers.get(selectedKey)));
        json.append('}');
        return json.toString();
    }

    private void merge(Map<String, Stat> into, Map<String, Stat> from) {
        for (Stat stat : from.values()) {
            Stat copy = into.get(stat.key);
            if (copy == null) {
                copy = new Stat(stat.key, stat.name, stat.entityId, stat.npcId);
                into.put(stat.key, copy);
            }
            copy.packets += stat.packets;
            copy.bytes += stat.bytes;
        }
    }

    private void mergeNested(Map<String, Map<String, Stat>> into, Map<String, Map<String, Stat>> from) {
        for (Map.Entry<String, Map<String, Stat>> entry : from.entrySet()) {
            Map<String, Stat> nested = into.get(entry.getKey());
            if (nested == null) {
                nested = new HashMap<>();
                into.put(entry.getKey(), nested);
            }
            merge(nested, entry.getValue());
        }
    }

    private void record(String source, String packetType, Player receiver, NPC npc, int estimatedBytes) {
        long second = System.currentTimeMillis() / 1000L;
        String receiverName = receiver == null ? "unknown" : receiver.getName();
        String receiverKey = receiver == null ? "unknown" : receiver.getUniqueId().toString();
        String npcKey = npc == null ? "internal/unknown" : Integer.toString(npc.getId());
        String npcName = npc == null ? "internal/unknown" : npc.getName();
        int entityId = npc != null && npc.getEntity() != null ? npc.getEntity().getEntityId() : -1;
        int npcId = npc == null ? -1 : npc.getId();
        synchronized (lock) {
            Bucket bucket = getBucket(second);
            bucket.packets++;
            bucket.bytes += estimatedBytes;
            add(bucket.npcs, npcKey, npcName, entityId, npcId, estimatedBytes);
            add(bucket.packetTypes, packetType, packetType, -1, -1, estimatedBytes);
            add(bucket.receivers, receiverKey, receiverName, -1, -1, estimatedBytes);
            Map<String, Stat> packetMap = bucket.npcPackets.get(npcKey);
            if (packetMap == null) {
                packetMap = new HashMap<>();
                bucket.npcPackets.put(npcKey, packetMap);
            }
            add(packetMap, packetType, packetType, -1, -1, estimatedBytes);
            Map<String, Stat> receiverMap = bucket.npcReceivers.get(npcKey);
            if (receiverMap == null) {
                receiverMap = new HashMap<>();
                bucket.npcReceivers.put(npcKey, receiverMap);
            }
            add(receiverMap, receiverKey, receiverName, -1, -1, estimatedBytes);
        }
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        sendBytes(exchange, status, contentType + "; charset=utf-8", bytes);
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResource(HttpExchange exchange, String path, String contentType) throws IOException {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        if (input == null) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        sendBytes(exchange, 200, contentType, output.toByteArray());
    }

    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        send(exchange, 403, "text/plain", "Forbidden");
    }

    private List<Stat> top(Map<String, Stat> map) {
        if (map == null || map.isEmpty())
            return Collections.emptyList();
        List<Stat> list = new ArrayList<>(map.values());
        Collections.sort(list, Comparator.comparingLong((Stat stat) -> stat.bytes).reversed());
        if (list.size() > MAX_TOP_ROWS) {
            return list.subList(0, MAX_TOP_ROWS);
        }
        return list;
    }

    private String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private String truncate(String text, int max) {
        if (text == null)
            return "";
        if (text.length() <= max)
            return text;
        if (max <= 1)
            return text.substring(0, max);
        return text.substring(0, max - 1) + "~";
    }

    private void appendSeries(StringBuilder json, String name, long[] series) {
        json.append('"').append(name).append("\":[");
        for (int i = 0; i < series.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(series[i]);
        }
        json.append(']');
    }

    private void appendStats(StringBuilder json, String name, List<Stat> stats) {
        json.append('"').append(name).append("\":[");
        for (int i = 0; i < stats.size(); i++) {
            Stat stat = stats.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            json.append("\"key\":\"").append(escape(stat.key)).append("\",");
            json.append("\"name\":\"").append(escape(stat.name)).append("\",");
            json.append("\"packets\":").append(stat.packets).append(',');
            json.append("\"bytes\":").append(stat.bytes).append(',');
            json.append("\"entityId\":").append(stat.entityId).append(',');
            json.append("\"npcId\":").append(stat.npcId);
            json.append('}');
        }
        json.append(']');
    }

    private static class Bucket {
        private long bytes;
        private final Map<String, Stat> npcs = new HashMap<>();
        private final Map<String, Map<String, Stat>> npcPackets = new HashMap<>();
        private final Map<String, Map<String, Stat>> npcReceivers = new HashMap<>();
        private long packets;
        private final Map<String, Stat> packetTypes = new HashMap<>();
        private final Map<String, Stat> receivers = new HashMap<>();
        private long second = -1;

        private void reset(long second) {
            this.second = second;
            packets = 0;
            bytes = 0;
            npcs.clear();
            packetTypes.clear();
            receivers.clear();
            npcPackets.clear();
            npcReceivers.clear();
        }
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/assets/packet-monitor.css".equals(path)) {
                sendResource(exchange, "packet-monitor/packet-monitor.css", "text/css; charset=utf-8");
                return;
            }
            if ("/assets/packet-monitor.js".equals(path)) {
                sendResource(exchange, "packet-monitor/packet-monitor.js", "application/javascript; charset=utf-8");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendUnauthorized(exchange);
                return;
            }
            if ("/".equals(path) || "".equals(path) || (path != null && path.startsWith("/t/"))) {
                sendResource(exchange, "packet-monitor/index.html", "text/html; charset=utf-8");
                return;
            }
            send(exchange, 404, "text/plain", "Not found");
        }
    }

    private class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendUnauthorized(exchange);
                return;
            }
            reset();
            send(exchange, 200, "application/json", "{\"ok\":true}");
        }
    }

    private class ReportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendUnauthorized(exchange);
                return;
            }
            String filename = "citizens-packet-report-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt";
            byte[] bytes = makeReport().getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            sendBytes(exchange, 200, "text/plain; charset=utf-8", bytes);
        }
    }

    private class SnapshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                sendUnauthorized(exchange);
                return;
            }
            send(exchange, 200, "application/json", makeSnapshot());
        }
    }

    private static class Stat {
        private long bytes;
        private final int entityId;
        private final String key;
        private final String name;
        private final int npcId;
        private long packets;

        private Stat(String key, String name, int entityId, int npcId) {
            this.key = key;
            this.name = name;
            this.entityId = entityId;
            this.npcId = npcId;
        }
    }

}
