package net.citizensnpcs.npc.skin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.SkinProperty;

class SkinProfileCache {
    private static final String FILE_NAME = "skin-cache.yml";
    private static YamlConfiguration cache;
    private static File cacheFile;

    static CachedSkin get(String skinName) {
        if (skinName == null)
            return null;

        synchronized (SkinProfileCache.class) {
            load();
            if (cache == null)
                return null;

            String path = path(skinName);
            String value = cache.getString(path + ".value");
            if (value == null || value.isEmpty())
                return null;

            String uuidRaw = cache.getString(path + ".uuid");
            UUID uuid = null;
            if (uuidRaw != null && !uuidRaw.isEmpty()) {
                try {
                    uuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException e) {
                    Messaging.debug("Invalid cached skin UUID for", skinName, uuidRaw);
                }
            }
            return new CachedSkin(uuid, new SkinProperty("textures", value, cache.getString(path + ".signature")));
        }
    }

    static void put(String skinName, UUID skinId, SkinProperty skinProperty) {
        if (skinName == null || skinProperty == null || skinProperty.value == null || skinProperty.value.isEmpty())
            return;

        synchronized (SkinProfileCache.class) {
            load();
            if (cache == null)
                return;

            String path = path(skinName);
            cache.set(path + ".name", skinName.toLowerCase(Locale.ROOT));
            cache.set(path + ".uuid", skinId == null ? null : skinId.toString());
            cache.set(path + ".value", skinProperty.value);
            cache.set(path + ".signature", skinProperty.signature);
            cache.set(path + ".updated", System.currentTimeMillis());
            save();
        }
    }

    private static void load() {
        if (cache != null)
            return;
        if (!CitizensAPI.hasImplementation())
            return;

        cacheFile = new File(CitizensAPI.getDataFolder(), FILE_NAME);
        cache = YamlConfiguration.loadConfiguration(cacheFile);
    }

    private static String path(String skinName) {
        String normalized = skinName.toLowerCase(Locale.ROOT);
        return "skins." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static void save() {
        try {
            cache.save(cacheFile);
        } catch (IOException e) {
            Messaging.debug("Failed to save skin cache", e.getMessage());
        }
    }

    static class CachedSkin {
        final SkinProperty skinData;
        final UUID skinId;

        CachedSkin(UUID skinId, SkinProperty skinData) {
            this.skinId = skinId;
            this.skinData = skinData;
        }
    }
}
