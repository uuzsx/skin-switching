package io.github.skinswitching.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Atomic replacement; a failed read or write never silently erases the previous selection. */
public final class SelectionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, SkinProfile> entries = new HashMap<>();
    public SelectionStore(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        if (Files.size(file) > 8 * 1024 * 1024) throw new IOException("Skin selection file too large");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("Unsupported skin selection schema");
            for (var e : root.getAsJsonObject("players").entrySet()) {
                entries.put(UUID.fromString(e.getKey()), Objects.requireNonNull(GSON.fromJson(e.getValue(), SkinProfile.class)));
            }
        } catch (RuntimeException e) { throw new IOException("Invalid skin selection file: " + file, e); }
    }
    public SkinProfile get(UUID wearer) { return entries.get(wearer); }
    public void put(UUID wearer, SkinProfile profile) throws IOException {
        Map<UUID, SkinProfile> next = new HashMap<>(entries);
        if (profile == null) next.remove(wearer); else next.put(wearer, profile);
        JsonObject root = new JsonObject(); root.addProperty("schema", 1);
        JsonObject players = new JsonObject();
        next.forEach((id, skin) -> players.add(id.toString(), GSON.toJsonTree(skin)));
        root.add("players", players);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "skin-switching-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(root));
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            entries.clear(); entries.putAll(next);
        } finally { Files.deleteIfExists(temp); }
    }
}
