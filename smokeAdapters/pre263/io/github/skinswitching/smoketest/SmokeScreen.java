package io.github.skinswitching.smoketest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
public final class SmokeScreen {
    private SmokeScreen() {}
    public static Screen current(Minecraft mc) { return mc.screen; }
}
