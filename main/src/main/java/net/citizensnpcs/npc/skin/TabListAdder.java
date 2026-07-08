package net.citizensnpcs.npc.skin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.util.NMS;

/**
 * Sends add packets in batches per player.
 */
public class TabListAdder {
    private final Map<UUID, PlayerEntry> pending = new HashMap<>(
            Math.max(128, Math.min(1024, Bukkit.getMaxPlayers() / 2)));

    TabListAdder() {
        Bukkit.getScheduler().runTaskTimer(CitizensAPI.getPlugin(), new Sender(), 1, 1);
    }

    public void cancelPackets(Player player) {
        Objects.requireNonNull(player);

        PlayerEntry entry = pending.remove(player.getUniqueId());
        if (entry == null)
            return;

        for (SkinnableEntity entity : entry.toAdd) {
            entity.getSkinTracker().notifyAddPacketCancelled(player.getUniqueId());
        }
    }

    public void cancelPackets(Player player, SkinnableEntity skinnable) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(skinnable);

        PlayerEntry entry = pending.get(player.getUniqueId());
        if (entry == null)
            return;

        if (entry.toAdd.remove(skinnable)) {
            skinnable.getSkinTracker().notifyAddPacketCancelled(player.getUniqueId());
        }
        if (entry.toAdd.isEmpty()) {
            pending.remove(player.getUniqueId());
        }
    }

    private PlayerEntry getEntry(Player player) {
        PlayerEntry entry = pending.get(player.getUniqueId());
        if (entry == null) {
            entry = new PlayerEntry(player);
            pending.put(player.getUniqueId(), entry);
        }
        return entry;
    }

    public void sendPacket(Player player, SkinnableEntity entity) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(entity);

        PlayerEntry entry = getEntry(player);
        entry.toAdd.add(entity);
    }

    private static class PlayerEntry {
        Player player;
        Set<SkinnableEntity> toAdd = new HashSet<>(20);

        PlayerEntry(Player player) {
            this.player = player;
        }
    }

    private class Sender implements Runnable {
        @Override
        public void run() {
            int maxPacketEntries = 15;

            Iterator<Map.Entry<UUID, PlayerEntry>> entryIterator = pending.entrySet().iterator();
            while (entryIterator.hasNext()) {
                Map.Entry<UUID, PlayerEntry> mapEntry = entryIterator.next();
                PlayerEntry entry = mapEntry.getValue();
                if (!entry.player.isOnline()) {
                    for (SkinnableEntity entity : entry.toAdd) {
                        entity.getSkinTracker().notifyAddPacketCancelled(entry.player.getUniqueId());
                    }
                    entryIterator.remove();
                    continue;
                }
                int listSize = Math.min(maxPacketEntries, entry.toAdd.size());

                List<Player> skinnableList = new ArrayList<>(listSize);
                List<SkinnableEntity> sent = new ArrayList<>(listSize);

                int i = 0;
                for (Iterator<SkinnableEntity> skinIterator = entry.toAdd.iterator(); skinIterator.hasNext();) {
                    if (i >= maxPacketEntries)
                        break;

                    SkinnableEntity next = skinIterator.next();
                    skinnableList.add(next.getBukkitEntity());
                    sent.add(next);
                    skinIterator.remove();
                    i++;
                }
                if (NMS.sendTabListAdd(entry.player, skinnableList)) {
                    for (SkinnableEntity next : sent) {
                        next.getSkinTracker().notifyAddPacketSent(entry.player.getUniqueId());
                    }
                } else {
                    for (SkinnableEntity next : sent) {
                        next.getSkinTracker().notifyAddPacketCancelled(entry.player.getUniqueId());
                    }
                }
                if (entry.toAdd.isEmpty()) {
                    entryIterator.remove();
                }
            }
        }
    }
}
