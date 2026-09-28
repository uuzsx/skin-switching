package io.github.skinswitching;

import com.mojang.logging.LogUtils;
import io.github.skinswitching.client.ClientSkins;
import io.github.skinswitching.network.SkinPayload;
import io.github.skinswitching.server.ServerSkins;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(SkinSwitching.ID)
public final class SkinSwitching {
    public static final String ID = "skin_switching";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean SYNC = readMode();
    public static Consumer<SkinPayload> clientReceiver = payload -> {};

    public SkinSwitching(IEventBus bus) {
        if (SYNC) {
            bus.addListener(this::registerPayloads);
            ServerSkins.initialize();
        }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientSkins.initialize();
        LOGGER.info("Skin Switching 0.1.0: {} edition", SYNC ? "multiplayer sync" : "client only");
    }
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(SkinPayload.TYPE, SkinPayload.CODEC,
                (payload, context) -> clientReceiver.accept(payload));
    }
    private static boolean readMode() {
        try (var stream = SkinSwitching.class.getResourceAsStream("/skin-switching-mode.txt")) {
            if (stream == null) throw new IllegalStateException("Missing edition marker");
            String mode = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!mode.equals("sync") && !mode.equals("client")) throw new IllegalStateException("Invalid edition marker");
            return mode.equals("sync");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
