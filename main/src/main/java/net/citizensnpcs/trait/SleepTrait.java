package net.citizensnpcs.trait;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.trait.EntityPoseTrait.EntityPose;
import net.citizensnpcs.util.NMS;

@TraitName("sleeptrait")
public class SleepTrait extends Trait {
    @Persist
    private Location at;
    private boolean sleeping;

    public SleepTrait() {
        super("sleeptrait");
    }

    @Override
    public void onDespawn() {
        sleeping = false;
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;

        if (at == null) {
            if (sleeping) {
                wakeup();
            }
            return;
        }
        if (npc.getEntity() instanceof Player) {
            Player player = (Player) npc.getEntity();
            npc.getOrAddTrait(EntityPoseTrait.class).setPose(EntityPose.SLEEPING);
            NMS.sleep(player, true);
            sleeping = true;
        }
    }

    public void setSleeping(Location at) {
        this.at = at != null ? at.clone() : null;
        wakeup();
    }

    private void wakeup() {
        npc.getOrAddTrait(EntityPoseTrait.class).setPose(null);
        if (npc.getEntity() instanceof Player) {
            NMS.sleep((Player) npc.getEntity(), false);
        }
        sleeping = false;
    }
}
