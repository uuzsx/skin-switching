package io.github.skinswitching.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** A source account's public texture property. Never replaces the wearer's identity. */
public record SkinProfile(UUID id, String name, String value, String signature) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public SkinProfile {
        if (id == null || !validName(name) || value == null || value.length() > 16384
                || signature == null || signature.isBlank() || signature.length() > 2048) {
            throw new SkinException("invalid_profile");
        }
        try {
            JsonObject texture = JsonParser.parseString(new String(Base64.getDecoder().decode(value),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (!parseId(texture.get("profileId").getAsString()).equals(id)) throw new IllegalArgumentException();
            JsonObject skin = texture.getAsJsonObject("textures").getAsJsonObject("SKIN");
            if (skin == null) throw new SkinException("no_skin");
            checkedTextureUri(skin.get("url").getAsString());
        } catch (SkinException e) { throw e; }
        catch (RuntimeException e) { throw new SkinException("invalid_profile", e); }
    }
    public static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }
    public static UUID parseId(String id) {
        if (!id.matches("[0-9a-fA-F]{32}")) throw new SkinException("invalid_profile");
        return UUID.fromString(id.substring(0,8) + "-" + id.substring(8,12) + "-" + id.substring(12,16)
                + "-" + id.substring(16,20) + "-" + id.substring(20));
    }
    public static URI checkedTextureUri(String url) {
        URI uri = URI.create(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || !"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/texture/[0-9a-fA-F]{32,64}")) {
            throw new SkinException("invalid_profile");
        }
        return URI.create("https://textures.minecraft.net" + uri.getPath());
    }
    public Property property() { return new Property("textures", value, signature); }
}
