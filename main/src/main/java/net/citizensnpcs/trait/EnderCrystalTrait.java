package net.citizensnpcs.trait;

import org.bukkit.entity.EnderCrystal;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

/**
 * Persists EnderCrystal metadata.
 *
 * @see EnderCrystal
 */
@TraitName("endercrystaltrait")
public class EnderCrystalTrait extends Trait {
    @Persist
    private boolean showBase;

    public EnderCrystalTrait() {
        super("endercrystaltrait");
    }

    public boolean isShowBase() {
        return showBase;
    }

    @Override
    public void onSpawn() {
        updateModifiers();
    }

    public void setShowBase(boolean showBase) {
        this.showBase = showBase;
        updateModifiers();
    }

    private void updateModifiers() {
        if (!(npc.getEntity() instanceof EnderCrystal))
            return;
    }
}
