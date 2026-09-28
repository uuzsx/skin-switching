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
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;

/** Downloads are bounded and retryable; vanilla handles legacy skin conversion and GPU upload. */
final class ClientTextures {
    private record Download(Path file, URI uri, PlayerModelType model) {}
    private static final Map<SkinProfile, CompletableFuture<PlayerSkin>> LOADING = new HashMap<>();
    private static final Set<Identifier> OWNED = new HashSet<>();
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
            Identifier location = Identifier.fromNamespaceAndPath("skin_switching",
                    "skins/" + session + "/" + download.uri().getPath().substring("/texture/".length()));
            // The file was fetched with bounded I/O and validated above. Vanilla only reads it,
            // converts legacy skins and registers the texture on the render thread.
            var downloader = new SkinTextureDownloader(mc.getProxy(), mc.getTextureManager(), mc);
            downloader.downloadAndRegisterSkin(location, download.file(), download.uri().toString(), true)
                    .whenComplete((texture, failure) -> mc.execute(() -> {
                        if (epoch != session) {
                            mc.getTextureManager().release(location);
                            result.completeExceptionally(new CancellationException());
                            return;
                        }
                        if (failure != null) {
                            LOADING.remove(profile);
                            result.completeExceptionally(new SkinException("texture", failure));
                        } else {
                            OWNED.add(location);
                            result.complete(new PlayerSkin(texture, null, null, download.model(), true));
                        }
                    }));
        }));
        return result;
    }
    private static Download download(SkinProfile profile, Minecraft mc) {
        var unpacked = mc.services().sessionService().unpackTextures(profile.property());
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
            return new Download(file, uri, PlayerModelType.byLegacyServicesName(skin.getMetadata("model")));
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
            if (skin != null && !ClientSkins.uses(skin.body().texturePath())) iterator.remove();
        }
        Set<Identifier> retained = new HashSet<>();
        for (var future : LOADING.values()) {
            if (future.isDone() && !future.isCompletedExceptionally()) retained.add(future.join().body().texturePath());
        }
        var textures = OWNED.iterator();
        while (textures.hasNext()) {
            Identifier id = textures.next();
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
