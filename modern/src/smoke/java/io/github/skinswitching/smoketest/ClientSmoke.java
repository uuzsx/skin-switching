package io.github.skinswitching.smoketest;

import com.mojang.authlib.GameProfile;
import io.github.skinswitching.SkinSwitching;
import io.github.skinswitching.client.ClientSkins;
import io.github.skinswitching.core.*;
import io.github.skinswitching.network.SkinPayload;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Development-only smoke harness, never included in either distributable jar. */
@Mod(value = "skin_switching_smoke", dist = Dist.CLIENT)
public final class ClientSmoke {
    private final UUID wearer = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private int stage;
    private long deadline;
    private PlayerInfo info;
    private PlayerSkin original;
    private SkinProfile profile;
    private Path selections;
    private UUID actualWearer;
    private long switchedAt;
    public ClientSmoke() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (Boolean.getBoolean("skin_switching.gameplaySmoke")) { gameplay(mc); return; }
            if (stage == 0 && SmokeScreen.current(mc) instanceof TitleScreen) {
                stage = 1; deadline = System.currentTimeMillis() + 60_000;
                info = new PlayerInfo(new GameProfile(wearer, "SkinSmoke"), false);
                original = info.getSkin();
                new ProfileService().find("SXUUZ").whenComplete((skin, error) -> mc.execute(() -> {
                    if (error != null) { finish(false, "Profile lookup: " + error); return; }
                    profile = skin;
                    SkinSwitching.clientReceiver.accept(new SkinPayload(wearer, profile));
                    stage = 2;
                }));
            } else if (stage == 2 && !info.getSkin().body().texturePath().equals(original.body().texturePath())) {
                PlayerSkin changed = info.getSkin();
                require(info.getProfile().id().equals(wearer), "Wearer UUID changed");
                require(info.getProfile().name().equals("SkinSmoke"), "Wearer name changed");
                require(changed.secure(), "Texture signature was not verified");
                require(((net.minecraft.core.ClientAsset.DownloadedTexture) changed.body()).url().startsWith("https://textures.minecraft.net/texture/"), "Unexpected skin URL");
                var cape = new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("smoke/cape"));
                var elytra = new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("smoke/elytra"));
                PlayerSkin withCape = new PlayerSkin(original.body(), cape, elytra, original.model(), false);
                PlayerSkin replaced = ClientSkins.override(wearer, withCape);
                require(cape.equals(replaced.cape()) && elytra.equals(replaced.elytra()), "Wearer's cape changed");
                SkinSwitching.clientReceiver.accept(new SkinPayload(wearer, null));
                require(info.getSkin().body().texturePath().equals(original.body().texturePath()), "Reset did not restore original skin");
                SkinSwitching.clientReceiver.accept(new SkinPayload(wearer, profile));
                SkinSwitching.clientReceiver.accept(new SkinPayload(wearer, null));
                stage = 3; deadline = System.currentTimeMillis() + 3000;
                SkinSwitching.LOGGER.info("SMOKE: signed skin loaded, model={}, mixin applied, UUID/name/cape preserved, reset passed", changed.model());
            } else if (stage == 3 && System.currentTimeMillis() >= deadline) {
                require(info.getSkin().body().texturePath().equals(original.body().texturePath()), "Late result overwrote reset");
                finish(true, "Live SXUUZ lookup; verified signature; PNG download and GPU upload; PlayerInfo mixin; identity/cape preservation; reset and stale-result cancellation.");
            } else if ((stage == 1 || stage == 2) && System.currentTimeMillis() > deadline) {
                finish(false, "Timed out at stage " + stage);
            }
        } catch (Throwable error) { finish(false, error.toString()); }
    }
    private void gameplay(Minecraft mc) throws Exception {
        if (stage == 0 && SmokeScreen.current(mc) instanceof TitleScreen) {
            stage = 10; deadline = System.currentTimeMillis() + 120_000;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(3);
            mc.options.simulationDistance().set(5);
            String world = "SkinSmoke-" + System.currentTimeMillis();
            mc.createWorldOpenFlows().createFreshLevel(world,
                    new LevelSettings(world, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), false, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(12345L, false, false),
                    registry -> registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                            .value().createWorldDimensions(), new TitleScreen());
        } else if (stage == 10 && mc.player != null && mc.getConnection() != null && SmokeScreen.current(mc) == null) {
            actualWearer = mc.player.getUUID(); original = mc.player.getSkin();
            selections = SkinSwitching.SYNC
                    ? mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("data/skin-switching.json")
                    : FMLPaths.CONFIGDIR.get().resolve("skin-switching-client.json");
            mc.getConnection().sendCommand("Skin Switching SXUUZ");
            switchedAt = System.currentTimeMillis(); stage = 11; deadline = switchedAt + 60_000;
        } else if (stage == 11 && mc.player.getSkin().body().texturePath().getNamespace().equals("skin_switching")) {
            require(actualWearer.equals(mc.player.getUUID()), "Command changed player identity");
            require(new SelectionStore(selections).get(actualWearer).name().equals("SXUUZ"), "Command did not persist selection");
            mc.getConnection().sendCommand("skin reset"); stage = 12;
        } else if (stage == 12 && mc.player.getSkin().body().texturePath().equals(original.body().texturePath())) {
            require(new SelectionStore(selections).get(actualWearer) == null, "Reset did not persist");
            stage = 13; deadline = System.currentTimeMillis() + 10_000;
        } else if (stage == 13 && System.currentTimeMillis() > switchedAt + 5100) {
            mc.getConnection().sendCommand("skin switching SXUUZ");
            mc.getConnection().sendCommand("skin reset");
            stage = 14; deadline = System.currentTimeMillis() + 3000;
        } else if (stage == 14 && System.currentTimeMillis() >= deadline) {
            require(mc.player.getSkin().body().texturePath().equals(original.body().texturePath()), "Late command result overwrote reset");
            require(new SelectionStore(selections).get(actualWearer) == null, "Late command result overwrote saved reset");
            finish(true, (SkinSwitching.SYNC ? "Sync" : "Client-only")
                    + " gameplay: fresh integrated world without cheats; exact /Skin Switching SXUUZ command; live lookup; displayed skin; disk persistence; /skin reset; cancellation of in-flight command."
                    + (SkinSwitching.SYNC ? " Included actual server-to-client payload delivery." : " Included actual NeoForge client-command dispatch."));
        } else if (stage >= 10 && stage < 14 && System.currentTimeMillis() > deadline) {
            finish(false, "Gameplay timeout at stage " + stage);
        }
    }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void finish(boolean success, String message) {
        if (stage == 99) return;
        stage = 99;
        try { Files.writeString(Path.of("smoke-result-" + (SkinSwitching.SYNC ? "sync" : "client") + ".txt"), (success ? "PASS: " : "FAIL: ") + message); }
        catch (Exception e) { SkinSwitching.LOGGER.error("Cannot write smoke result", e); }
        SkinSwitching.LOGGER.info("SMOKE {}: {}", success ? "PASS" : "FAIL", message);
        Minecraft.getInstance().stop();
    }
}
