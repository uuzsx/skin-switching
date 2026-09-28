package io.github.skinswitching;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class Messages {
    private Messages() {}
    public static MutableComponent of(String key, Object... args) {
        return Component.literal("[Skin Switching] ").withStyle(ChatFormatting.AQUA)
                .append(Component.translatable("skin_switching." + key, args).withStyle(ChatFormatting.WHITE));
    }
    public static MutableComponent error(String key) {
        return Component.literal("[Skin Switching] ").withStyle(ChatFormatting.RED)
                .append(Component.translatable("skin_switching.error." + key));
    }
}
