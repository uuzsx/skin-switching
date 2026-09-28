package io.github.skinswitching.core;

import java.util.*;

/** Confined to the owning game thread; monotonically increasing tokens survive reconnects. */
public final class Requests {
    private final Map<UUID, Long> tokens = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private long sequence;
    public long begin(UUID id, long now) {
        if (now < cooldowns.getOrDefault(id, Long.MIN_VALUE)) throw new SkinException("cooldown");
        cooldowns.put(id, now + 5000);
        return invalidate(id);
    }
    public long invalidate(UUID id) { long token = ++sequence; tokens.put(id, token); return token; }
    public boolean current(UUID id, long token) { return Objects.equals(tokens.get(id), token); }
    public void forget(UUID id) { tokens.remove(id); cooldowns.remove(id); }
    public void clear() { tokens.clear(); cooldowns.clear(); }
}
