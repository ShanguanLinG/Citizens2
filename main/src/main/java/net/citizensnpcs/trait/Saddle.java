package net.citizensnpcs.trait;

import org.bukkit.entity.Pig;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

/**
 * Persists saddle metadata.
 *
 * @see Pig#hasSaddle()
 */
@TraitName("saddle")
public class Saddle extends Trait implements Toggleable {
    @Persist("")
    private boolean saddle;
    private boolean steerable;

    public Saddle() {
        super("saddle");
    }

    @Override
    public void onSpawn() {
        if (npc.getEntity() instanceof Pig) {
            steerable = true;
            updateSaddleState();
        } else {
            steerable = false;
        }
    }

    @Override
    public boolean toggle() {
        saddle = !saddle;
        if (steerable) {
            updateSaddleState();
        }
        return saddle;
    }

    @Override
    public String toString() {
        return "Saddle{" + saddle + "}";
    }

    private void updateSaddleState() {
        if (npc.getEntity() instanceof Pig) {
            ((Pig) npc.getEntity()).setSaddle(saddle);
        }
    }

    public boolean useSaddle() {
        return saddle;
    }
}
