package net.citizensnpcs.trait;

import org.bukkit.entity.Boat;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

@TraitName("boattrait")
public class BoatTrait extends Trait {
    @Persist
    private String type;

    public BoatTrait() {
        super("boattrait");
    }

    public String getType() {
        return type;
    }

    @Override
    public void onSpawn() {
        // Boat wood types were added after 1.8.8.
    }

    public void setType(String type) {
        this.type = type;
        onSpawn();
    }
}
