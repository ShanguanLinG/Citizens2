package net.citizensnpcs.trait;

import org.bukkit.DyeColor;
import org.bukkit.entity.Wolf;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

/**
 * Persists {@link Wolf} metadata.
 *
 * @see Wolf
 */
@TraitName("wolfmodifiers")
public class WolfModifiers extends Trait {
    @Persist
    private boolean angry;
    @Persist("collarColor")
    private DyeColor collarColor = DyeColor.RED;
    @Persist
    private boolean interested;
    @Persist
    private boolean sitting;
    @Persist
    private boolean tamed;
    @Persist
    private String variant;

    public WolfModifiers() {
        super("wolfmodifiers");
    }

    public DyeColor getCollarColor() {
        return collarColor;
    }

    public String getVariant() {
        return variant;
    }

    public boolean isAngry() {
        return angry;
    }

    public boolean isInterested() {
        return interested;
    }

    public boolean isSitting() {
        return sitting;
    }

    public boolean isTamed() {
        return tamed;
    }

    @Override
    public void onSpawn() {
        updateModifiers();
    }

    public void setAngry(boolean angry) {
        this.angry = angry;
        updateModifiers();
    }

    public void setCollarColor(DyeColor color) {
        collarColor = color;
        updateModifiers();
    }

    public void setInterested(boolean interested) {
        this.interested = interested;
        updateModifiers();
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
        updateModifiers();
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
        updateModifiers();
    }

    public void setVariant(String variant) {
        this.variant = variant;
        updateModifiers();
    }

    private void updateModifiers() {
        if (npc.getEntity() instanceof Wolf) {
            Wolf wolf = (Wolf) npc.getEntity();
            wolf.setCollarColor(collarColor);
            wolf.setSitting(sitting);
            wolf.setAngry(angry);
            if (angry) {
                wolf.setTarget(wolf);
            }
            wolf.setTamed(tamed);
        }
    }
}
