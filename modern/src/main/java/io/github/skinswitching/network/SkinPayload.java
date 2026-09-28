package io.github.skinswitching.network;

import io.github.skinswitching.core.SkinProfile;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The wearer and source UUIDs are deliberately separate. A null source restores the wearer. */
public record SkinPayload(UUID wearer, SkinProfile profile) implements CustomPacketPayload {
    public static final Type<SkinPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("skin_switching", "selection"));
    public static final StreamCodec<FriendlyByteBuf, SkinPayload> CODEC = StreamCodec.ofMember(SkinPayload::write, SkinPayload::read);
    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(wearer);
        buffer.writeBoolean(profile != null);
        if (profile != null) {
            buffer.writeUUID(profile.id()); buffer.writeUtf(profile.name(), 16);
            buffer.writeUtf(profile.value(), 16384); buffer.writeUtf(profile.signature(), 2048);
        }
    }
    private static SkinPayload read(FriendlyByteBuf buffer) {
        UUID wearer = buffer.readUUID();
        return new SkinPayload(wearer, buffer.readBoolean() ? new SkinProfile(buffer.readUUID(),
                buffer.readUtf(16), buffer.readUtf(16384), buffer.readUtf(2048)) : null);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
