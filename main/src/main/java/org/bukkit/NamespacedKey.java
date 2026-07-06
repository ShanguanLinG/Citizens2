package org.bukkit;

import java.util.Locale;

public final class NamespacedKey {
    private final String key;
    private final String namespace;

    public NamespacedKey(String namespace, String key) {
        if (namespace == null || key == null)
            throw new IllegalArgumentException("namespace and key cannot be null");
        this.namespace = namespace.toLowerCase(Locale.ROOT);
        this.key = key.toLowerCase(Locale.ROOT);
    }

    public String getKey() {
        return key;
    }

    public String getNamespace() {
        return namespace;
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + key.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof NamespacedKey))
            return false;
        NamespacedKey that = (NamespacedKey) obj;
        return namespace.equals(that.namespace) && key.equals(that.key);
    }

    @Override
    public String toString() {
        return namespace + ":" + key;
    }
}
