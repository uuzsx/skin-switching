package io.github.skinswitching;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
public final class PlatformRuntime {
    private PlatformRuntime() {}
    public static Dist dist() { return FMLEnvironment.dist; }
}
