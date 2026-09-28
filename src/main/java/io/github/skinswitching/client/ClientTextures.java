package io.github.skinswitching.client;

import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.skinswitching.core.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Downloads are bounded and retryable; vanilla handles legacy skin conversion and GPU upload. */
final class ClientTextures {
    private record Download(Path file, URI uri, PlayerSkin.Model model) {}
    private static final Map<SkinProfile, CompletableFuture<PlayerSkin>> LOADING = new HashMap<>();
    private static final Set<ResourceLocation> OWNED = new HashSet<>();
    private static long epoch;
    private ClientTextures() {}

    static CompletableFuture<PlayerSkin> load(SkinProfile profile) {
        CompletableFuture<PlayerSkin> existing = LOADING.get(profile);
        if (existing != null) return existing;
        Minecraft mc = Minecraft.getInstance();
        long session = epoch;
        CompletableFuture<PlayerSkin> result = new CompletableFuture<>();
        LOADING.put(profile, result);
        HttpTransport.async(() -> download(profile, mc)).whenComplete((download, error) -> mc.execute(() -> {
            if (epoch != session) { result.completeExceptionally(new CancellationException()); return; }
            if (error != null) { LOADING.remove(profile); result.completeExceptionally(error); return; }
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("skin_switching",
                    "skins/" + download.uri().getPath().substring("/texture/".length()));
            try {
                HttpTexture texture = new HttpTexture(download.file().toFile(), download.uri().toString(),
                        DefaultPlayerSkin.getDefaultTexture(), true, () -> result.complete(new PlayerSkin(
                                location, download.uri().toString(), null, null, download.model(), true)));
                OWNED.add(location);
                mc.getTextureManager().register(location, texture);
                // The validated local PNG invokes its callback synchronously during registration.
                if (!result.isDone()) throw new SkinException("texture");
            } catch (RuntimeException e) {
                LOADING.remove(profile); result.completeExceptionally(new SkinException("texture", e));
            }
        }));
        return result;
    }
    private static Download download(SkinProfile profile, Minecraft mc) {
        var unpacked = mc.getMinecraftSessionService().unpackTextures(profile.property());
        if (unpacked.signatureState() != SignatureState.SIGNED) throw new SkinException("signature");
        MinecraftProfileTexture skin = unpacked.skin();
        if (skin == null) throw new SkinException("no_skin");
        URI uri = SkinProfile.checkedTextureUri(skin.getUrl());
        Path dir = mc.gameDirectory.toPath().resolve("skin-switching-cache");
        Path file = dir.resolve(uri.getPath().substring("/texture/".length()) + ".png");
        try {
            Files.createDirectories(dir);
            if (!Files.isRegularFile(file) || !validImage(file)) {
                byte[] bytes = HttpTransport.get(uri, 1_048_576);
                Path temp = Files.createTempFile(dir, "download-", ".png");
                try {
                    Files.write(temp, bytes);
                    if (!validImage(temp)) throw new SkinException("texture");
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temp); }
            }
            return new Download(file, uri, PlayerSkin.Model.byName(skin.getMetadata("model")));
        } catch (IOException e) { throw new SkinException("texture", e); }
    }
    private static boolean validImage(Path file) {
        try {
            if (Files.size(file) > 1_048_576) return false;
            // Check dimensions before native decoding, including for a manually modified cache file.
            try (var input = new java.io.DataInputStream(Files.newInputStream(file))) {
                if (input.readLong() != 0x89504e470d0a1a0aL || input.readInt() != 13
                        || input.readInt() != 0x49484452 || input.readInt() != 64) return false;
                int height = input.readInt(); if (height != 32 && height != 64) return false;
            }
            try (var input = Files.newInputStream(file); NativeImage image = NativeImage.read(input)) {
                return image.getWidth() == 64 && (image.getHeight() == 32 || image.getHeight() == 64);
            }
        } catch (IOException | RuntimeException e) { return false; }
    }
    static void prune() {
        // Keep active skins; release textures of previously selected or disconnected players.
        var iterator = LOADING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().isDone() || entry.getValue().isCompletedExceptionally()) continue;
            PlayerSkin skin = entry.getValue().getNow(null);
            if (skin != null && !ClientSkins.uses(skin.texture())) iterator.remove();
        }
        Set<ResourceLocation> retained = new HashSet<>();
        for (var future : LOADING.values()) {
            if (future.isDone() && !future.isCompletedExceptionally()) retained.add(future.join().texture());
        }
        var textures = OWNED.iterator();
        while (textures.hasNext()) {
            ResourceLocation id = textures.next();
            if (!retained.contains(id) && !ClientSkins.uses(id)) {
                Minecraft.getInstance().getTextureManager().release(id); textures.remove();
            }
        }
    }
    static void clear() {
        epoch++;
        LOADING.clear();
        OWNED.forEach(Minecraft.getInstance().getTextureManager()::release);
        OWNED.clear();
    }
}
