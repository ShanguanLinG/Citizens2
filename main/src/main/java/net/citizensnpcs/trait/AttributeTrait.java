package net.citizensnpcs.trait;

import java.util.Map;

import com.google.common.collect.Maps;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

@TraitName("attributetrait")
public class AttributeTrait extends Trait {
    @Persist
    private final Map<String, Double> attributes = Maps.newHashMap();

    public AttributeTrait() {
        super("attributetrait");
    }

    @Override
    public void onSpawn() {
    }

    public void setAttributeValue(String attribute, double value) {
        attributes.put(attribute, value);
        onSpawn();
    }

    public void setDefaultAttribute(String attribute) {
        attributes.remove(attribute);
    }
}
