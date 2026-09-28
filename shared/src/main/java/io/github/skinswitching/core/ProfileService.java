package io.github.skinswitching.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class ProfileService {
    private record Cached(SkinProfile profile, long expires) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<SkinProfile>> pending = new HashMap<>();
    private final Function<URI, byte[]> transport;
    private final Clock clock;
    public ProfileService() { this(uri -> HttpTransport.get(uri, 65536), Clock.systemUTC()); }
    public ProfileService(Function<URI, byte[]> transport, Clock clock) { this.transport = transport; this.clock = clock; }
    public synchronized CompletableFuture<SkinProfile> find(String name) {
        if (!SkinProfile.validName(name)) return CompletableFuture.failedFuture(new SkinException("invalid_name"));
        String key = name.toLowerCase(Locale.ROOT);
        Cached hit = cache.get(key);
        if (hit != null && hit.expires > clock.millis()) return CompletableFuture.completedFuture(hit.profile);
        if (pending.containsKey(key)) return pending.get(key);
        CompletableFuture<SkinProfile> request = new CompletableFuture<>();
        pending.put(key, request);
        HttpTransport.async(() -> query(name)).whenComplete((profile, error) -> {
            synchronized (this) {
                pending.remove(key);
                if (error == null) {
                    if (cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
                    cache.put(key, new Cached(profile, clock.millis() + 600_000));
                }
            }
            if (error == null) request.complete(profile); else request.completeExceptionally(error);
        });
        return request;
    }
    private SkinProfile query(String name) {
        try {
            JsonObject account = json(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name));
            UUID id = SkinProfile.parseId(account.get("id").getAsString());
            JsonObject profile = json(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + id.toString().replace("-", "") + "?unsigned=false"));
            if (!id.equals(SkinProfile.parseId(profile.get("id").getAsString()))) throw new SkinException("invalid_profile");
            for (var element : profile.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if ("textures".equals(property.get("name").getAsString())) {
                    return new SkinProfile(id, profile.get("name").getAsString(),
                            property.get("value").getAsString(), property.get("signature").getAsString());
                }
            }
            throw new SkinException("no_skin");
        } catch (SkinException e) { throw e; }
        catch (RuntimeException e) { throw new SkinException("invalid_profile", e); }
    }
    private JsonObject json(URI uri) {
        return JsonParser.parseString(new String(transport.apply(uri), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
