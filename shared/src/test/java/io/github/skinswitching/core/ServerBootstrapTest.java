package io.github.skinswitching.core;

import io.github.skinswitching.SkinSwitching;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerBootstrapTest {
    @Test void loadsModOnDedicatedServerWithoutResolvingClientClasses() {
        assertEquals(Dist.DEDICATED_SERVER, io.github.skinswitching.PlatformRuntime.dist());
        // ModDevGradle's forgejunitdev launcher has already bootstrapped the mods.
        assertTrue(ModList.get().isLoaded("skin_switching"));
        assertTrue(SkinSwitching.SYNC);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        NeoForge.EVENT_BUS.post(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.DEDICATED, null));
        assertNotNull(dispatcher.getRoot().getChild("Skin").getChild("Switching").getChild("username"));
        assertNotNull(dispatcher.getRoot().getChild("skin").getChild("reset"));
    }
}
