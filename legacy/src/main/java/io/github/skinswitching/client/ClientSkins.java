package io.github.skinswitching.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.skinswitching.Messages;
import io.github.skinswitching.SkinSwitching;
import io.github.skinswitching.core.*;
import io.github.skinswitching.network.SkinPayload;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientSkins {
    private record Applied(SkinProfile profile, PlayerSkin skin) {}
    private static final Map<UUID, Applied> APPLIED = new HashMap<>();
    private static final Requests REQUESTS = new Requests();
    private static final ProfileService PROFILES = new ProfileService();
    private static SelectionStore store;
    private ClientSkins() {}
    public static void initialize() {
        SkinSwitching.clientReceiver = ClientSkins::receive;
        NeoForge.EVENT_BUS.addListener(ClientSkins::login);
        NeoForge.EVENT_BUS.addListener(ClientSkins::logout);
        if (!SkinSwitching.SYNC) NeoForge.EVENT_BUS.addListener(ClientSkins::commands);
    }
    private static void commands(RegisterClientCommandsEvent event) {
        CommandTree.register(event.getDispatcher(), context -> set(StringArgumentType.getString(context, "username")),
                context -> reset(), context -> {
                    var mc = Minecraft.getInstance();
                    Applied skin = mc.player == null ? null : APPLIED.get(mc.player.getUUID());
                    tell(Messages.of("status.client", skin == null ? "—" : skin.profile.name())); return 1;
                });
    }
    private static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        if (SkinSwitching.SYNC) return;
        try {
            store = new SelectionStore(FMLPaths.CONFIGDIR.get().resolve("skin-switching-client.json"));
            SkinProfile profile = store.get(event.getPlayer().getUUID());
            if (profile != null) load(event.getPlayer().getUUID(), profile,
                    REQUESTS.invalidate(event.getPlayer().getUUID()), false);
        } catch (IOException e) { store = null; storageError(e); }
    }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        REQUESTS.clear(); APPLIED.clear(); ClientTextures.clear(); store = null;
    }
    private static int set(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        if (store == null) { tell(Messages.error("storage")); return 0; }
        if (!SkinProfile.validName(name)) { tell(Messages.error("invalid_name")); return 0; }
        UUID id = mc.player.getUUID();
        long token;
        try { token = REQUESTS.begin(id, System.currentTimeMillis()); }
        catch (SkinException e) { tell(Messages.error(e.key())); return 0; }
        tell(Messages.of("query", name));
        PROFILES.find(name).whenComplete((profile, error) -> mc.execute(() -> {
            if (!REQUESTS.current(id, token)) return;
            if (error != null) tell(Messages.error(SkinException.keyOf(error)));
            else load(id, profile, token, true);
        }));
        return 1;
    }
    private static int reset() {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return 0;
        UUID id = mc.player.getUUID(); REQUESTS.invalidate(id);
        if (store == null) { tell(Messages.error("storage")); return 0; }
        try { store.put(id, null); }
        catch (IOException e) { storageError(e); return 0; }
        APPLIED.remove(id); ClientTextures.prune(); tell(Messages.of("reset")); return 1;
    }
    private static void receive(SkinPayload payload) {
        if (!SkinSwitching.SYNC) return;
        long token = REQUESTS.invalidate(payload.wearer());
        if (payload.profile() == null) {
            APPLIED.remove(payload.wearer()); ClientTextures.prune();
        } else load(payload.wearer(), payload.profile(), token, false);
    }
    private static void load(UUID id, SkinProfile profile, long token, boolean persist) {
        Minecraft mc = Minecraft.getInstance();
        ClientTextures.load(profile).whenComplete((skin, error) -> mc.execute(() -> {
            if (!REQUESTS.current(id, token)) { mc.schedule(ClientTextures::prune); return; }
            if (error != null) {
                SkinSwitching.LOGGER.warn("Cannot load skin for {}: {}", id, SkinException.keyOf(error));
                if (mc.player != null && mc.player.getUUID().equals(id)) tell(Messages.error(SkinException.keyOf(error)));
                return;
            }
            if (persist) {
                try { store.put(id, profile); }
                catch (IOException e) { storageError(e); return; }
            }
            APPLIED.put(id, new Applied(profile, skin));
            // Deferred until all callbacks sharing this download have consumed it.
            mc.schedule(ClientTextures::prune);
            if (mc.player != null && mc.player.getUUID().equals(id)) tell(Messages.of("applied", profile.name()));
        }));
    }
    public static PlayerSkin override(UUID wearer, PlayerSkin original) {
        Applied applied = APPLIED.get(wearer);
        if (applied == null) return original;
        PlayerSkin skin = applied.skin;
        return new PlayerSkin(skin.texture(), skin.textureUrl(), original.capeTexture(), original.elytraTexture(),
                skin.model(), skin.secure());
    }
    static boolean uses(ResourceLocation texture) {
        return APPLIED.values().stream().anyMatch(skin -> skin.skin.texture().equals(texture));
    }
    private static void tell(Component message) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(message, false);
    }
    private static void storageError(IOException e) {
        SkinSwitching.LOGGER.error("Cannot read/write client skin selections", e); tell(Messages.error("storage"));
    }
}
