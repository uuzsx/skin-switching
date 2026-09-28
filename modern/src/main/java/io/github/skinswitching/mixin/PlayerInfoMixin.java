package io.github.skinswitching.mixin;

import com.mojang.authlib.GameProfile;
import io.github.skinswitching.client.ClientSkins;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Shadow public abstract GameProfile getProfile();
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void skinSwitching$skin(CallbackInfoReturnable<PlayerSkin> cir) {
        cir.setReturnValue(ClientSkins.override(getProfile().id(), cir.getReturnValue()));
    }
}
