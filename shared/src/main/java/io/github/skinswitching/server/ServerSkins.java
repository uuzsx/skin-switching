package io.github.skinswitching.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.skinswitching.Messages;
import io.github.skinswitching.SkinSwitching;
import io.github.skinswitching.core.*;
import io.github.skinswitching.network.SkinPayload;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerSkins {
    private static final ProfileService PROFILES = new ProfileService();
    private static final Requests REQUESTS = new Requests();
    private static MinecraftServer activeServer;
    private static SelectionStore store;
    private ServerSkins() {}
    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(ServerSkins::starting);
        NeoForge.EVENT_BUS.addListener(ServerSkins::stopped);
        NeoForge.EVENT_BUS.addListener(ServerSkins::commands);
        NeoForge.EVENT_BUS.addListener(ServerSkins::login);
        NeoForge.EVENT_BUS.addListener(ServerSkins::logout);
    }
    private static void starting(ServerStartingEvent event) {
        activeServer = event.getServer(); REQUESTS.clear(); store = null;
        try { store = new SelectionStore(activeServer.getWorldPath(LevelResource.ROOT).resolve("data/skin-switching.json")); }
        catch (IOException e) { SkinSwitching.LOGGER.error("Cannot read skin selections; preserving file and disabling changes", e); }
    }
    private static void stopped(ServerStoppedEvent event) { activeServer = null; store = null; REQUESTS.clear(); }
    private static void commands(RegisterCommandsEvent event) {
        CommandTree.register(event.getDispatcher(), context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return set(player, StringArgumentType.getString(context, "username"));
        }, context -> reset(context.getSource().getPlayerOrException()), context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            SkinProfile profile = store == null ? null : store.get(player.getUUID());
            player.sendSystemMessage(Messages.of("status.sync", profile == null ? "—" : profile.name()));
            return 1;
        });
    }
    private static int set(ServerPlayer player, String name) {
        if (store == null) { player.sendSystemMessage(Messages.error("storage")); return 0; }
        if (!SkinProfile.validName(name)) { player.sendSystemMessage(Messages.error("invalid_name")); return 0; }
        UUID wearer = player.getUUID();
        long token;
        try { token = REQUESTS.begin(wearer, System.currentTimeMillis()); }
        catch (SkinException e) { player.sendSystemMessage(Messages.error(e.key())); return 0; }
        MinecraftServer server = activeServer;
        player.sendSystemMessage(Messages.of("query", name));
        PROFILES.find(name).whenComplete((profile, error) -> server.execute(() -> {
            if (activeServer != server || !REQUESTS.current(wearer, token)) return;
            // Resolve the current entity: death/dimension changes can replace the original player object.
            ServerPlayer current = server.getPlayerList().getPlayer(wearer);
            if (current == null) return;
            if (error != null) { current.sendSystemMessage(Messages.error(SkinException.keyOf(error))); return; }
            try { store.put(wearer, profile); }
            catch (IOException e) { storageError(current, e); return; }
            PacketDistributor.sendToAllPlayers(new SkinPayload(wearer, profile));
            current.sendSystemMessage(Messages.of("sync.sent", profile.name()));
        }));
        return 1;
    }
    private static int reset(ServerPlayer player) {
        UUID wearer = player.getUUID(); REQUESTS.invalidate(wearer);
        if (store == null) { player.sendSystemMessage(Messages.error("storage")); return 0; }
        try { store.put(wearer, null); }
        catch (IOException e) { storageError(player, e); return 0; }
        PacketDistributor.sendToAllPlayers(new SkinPayload(wearer, null));
        player.sendSystemMessage(Messages.of("reset"));
        return 1;
    }
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || store == null) return;
        // Send only online wearers. Broadcast the joiner's saved selection for existing players.
        for (ServerPlayer online : player.level().getServer().getPlayerList().getPlayers()) {
            SkinProfile profile = store.get(online.getUUID());
            if (profile != null) PacketDistributor.sendToPlayer(player, new SkinPayload(online.getUUID(), profile));
        }
        SkinProfile own = store.get(player.getUUID());
        if (own != null) PacketDistributor.sendToAllPlayers(new SkinPayload(player.getUUID(), own));
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            REQUESTS.forget(player.getUUID());
            PacketDistributor.sendToAllPlayers(new SkinPayload(player.getUUID(), null));
        }
    }
    private static void storageError(ServerPlayer player, IOException error) {
        SkinSwitching.LOGGER.error("Cannot save skin selection", error);
        player.sendSystemMessage(Messages.error("storage"));
    }
}
