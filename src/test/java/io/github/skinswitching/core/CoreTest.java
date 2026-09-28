package io.github.skinswitching.core;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.skinswitching.network.SkinPayload;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir Path dir;
    static final String ID = "6b0f5181a7324ee2b53ac5a05d6af32e";
    static byte[] fixture() {
        try (var stream = CoreTest.class.getResourceAsStream("/sxuuz-profile.json")) { return stream.readAllBytes(); }
        catch (IOException e) { throw new RuntimeException(e); }
    }
    static byte[] account() { return ("{\"id\":\"" + ID + "\",\"name\":\"SXUUZ\"}").getBytes(StandardCharsets.UTF_8); }
    static SkinProfile profile() {
        return new ProfileService(uri -> uri.getHost().equals("api.mojang.com") ? account() : fixture(), Clock.systemUTC())
                .find("SXUUZ").join();
    }
    @Test void resolvesRealProfileAndCachesCaseInsensitively() {
        AtomicInteger calls = new AtomicInteger();
        var service = new ProfileService(uri -> {
            calls.incrementAndGet();
            assertEquals("https", uri.getScheme());
            if (uri.getHost().equals("api.mojang.com")) return account();
            assertEquals("unsigned=false", uri.getQuery()); return fixture();
        }, Clock.systemUTC());
        SkinProfile skin = service.find("SXUUZ").join();
        assertEquals(SkinProfile.parseId(ID), skin.id());
        assertEquals("SXUUZ", skin.name());
        assertEquals(skin, service.find("sxuuz").join());
        assertEquals(2, calls.get());
    }
    @Test void rejectsMalformedNamesBeforeNetworking() {
        var service = new ProfileService(uri -> { fail("Must not make request"); return null; }, Clock.systemUTC());
        for (String name : new String[]{"", "../admin", "name with space", "名字", "12345678901234567", "https://bad"}) {
            assertEquals("invalid_name", SkinException.keyOf(assertThrows(CompletionException.class, () -> service.find(name).join())));
        }
        assertTrue(SkinProfile.validName("a")); assertTrue(SkinProfile.validName("Some_User123"));
    }
    @Test void failedQueriesCanBeRetried() {
        AtomicInteger calls = new AtomicInteger();
        var service = new ProfileService(uri -> {
            if (calls.getAndIncrement() == 0) throw new SkinException("rate_limit");
            return uri.getHost().equals("api.mojang.com") ? account() : fixture();
        }, Clock.systemUTC());
        assertThrows(CompletionException.class, () -> service.find("SXUUZ").join());
        assertEquals("SXUUZ", service.find("SXUUZ").join().name());
    }
    @Test void coalescesConcurrentLookups() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var service = new ProfileService(uri -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return uri.getHost().equals("api.mojang.com") ? account() : fixture();
        }, Clock.systemUTC());
        var first = service.find("SXUUZ");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertSame(first, service.find("sxuuz")); release.countDown();
        assertEquals("SXUUZ", first.get(5, TimeUnit.SECONDS).name());
    }
    @Test void rejectsMismatchedAccountAndMissingSignature() {
        JsonObject json = JsonParser.parseString(new String(fixture(), StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("id", "00000000000000000000000000000000");
        var service = new ProfileService(uri -> uri.getHost().equals("api.mojang.com") ? account() : json.toString().getBytes(StandardCharsets.UTF_8), Clock.systemUTC());
        assertEquals("invalid_profile", SkinException.keyOf(assertThrows(CompletionException.class, () -> service.find("SXUUZ").join())));
        SkinProfile p = profile();
        assertThrows(SkinException.class, () -> new SkinProfile(p.id(), p.name(), p.value(), null));
        assertThrows(SkinException.class, () -> new SkinProfile(UUID.randomUUID(), p.name(), p.value(), p.signature()));
    }
    @Test void onlyAcceptsOfficialTextureHostAndUpgradesHttps() {
        String hash = "a".repeat(64);
        assertEquals("https://textures.minecraft.net/texture/" + hash,
                SkinProfile.checkedTextureUri("http://textures.minecraft.net/texture/" + hash).toString());
        for (String url : List.of("http://127.0.0.1/texture/" + hash, "https://textures.minecraft.net.evil.test/texture/" + hash,
                "file:///tmp/skin.png", "https://textures.minecraft.net:8443/texture/" + hash,
                "https://user@textures.minecraft.net/texture/" + hash, "https://textures.minecraft.net/texture/" + hash + "?x=1")) {
            assertThrows(RuntimeException.class, () -> SkinProfile.checkedTextureUri(url));
        }
    }
    @Test void preservesSelectionsAcrossRestartAndReset() throws Exception {
        Path file = dir.resolve("data/skins.json"); UUID wearer = UUID.randomUUID(), other = UUID.randomUUID();
        SkinProfile source = profile();
        SelectionStore store = new SelectionStore(file); store.put(wearer, source); store.put(other, source);
        SelectionStore restored = new SelectionStore(file);
        assertEquals(source, restored.get(wearer)); assertEquals(source, restored.get(other));
        restored.put(wearer, null);
        assertNull(new SelectionStore(file).get(wearer)); assertEquals(source, new SelectionStore(file).get(other));
        assertNotEquals(wearer, restored.get(other).id());
    }
    @Test void corruptSavedDataIsNotOverwritten() throws Exception {
        Path file = dir.resolve("skins.json"); String broken = "{incomplete";
        Files.writeString(file, broken);
        assertThrows(IOException.class, () -> new SelectionStore(file));
        assertEquals(broken, Files.readString(file));
    }
    @Test void failedSaveRetainsInMemorySelection() throws Exception {
        Path file = dir.resolve("skins.json"); UUID wearer = UUID.randomUUID(); SkinProfile source = profile();
        SelectionStore store = new SelectionStore(file); store.put(wearer, source);
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("occupied"), "x");
        assertThrows(IOException.class, () -> store.put(wearer, null));
        assertEquals(source, store.get(wearer));
    }
    @Test void resetAndReconnectInvalidateOldAsyncRequests() {
        Requests requests = new Requests(); UUID wearer = UUID.randomUUID();
        long old = requests.begin(wearer, 1000);
        assertTrue(requests.current(wearer, old));
        requests.invalidate(wearer); assertFalse(requests.current(wearer, old));
        assertThrows(SkinException.class, () -> requests.begin(wearer, 1001));
        requests.forget(wearer); long newer = requests.begin(wearer, 1002);
        assertFalse(requests.current(wearer, old)); assertTrue(requests.current(wearer, newer));
        requests.clear(); assertFalse(requests.current(wearer, newer));
    }
    @Test void independentPlayersDoNotCancelEachOther() {
        Requests requests = new Requests(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        long ta = requests.begin(a, 1), tb = requests.begin(b, 1);
        requests.invalidate(a); assertFalse(requests.current(a, ta)); assertTrue(requests.current(b, tb));
    }
    @Test void exactRequestedCommandAndAliasesWorkWithoutTargetPlayerArgument() throws Exception {
        CommandDispatcher<List<String>> dispatcher = new CommandDispatcher<>();
        CommandTree.register(dispatcher, c -> { c.getSource().add(StringArgumentType.getString(c,"username")); return 1; },
                c -> { c.getSource().add("reset"); return 1; }, c -> { c.getSource().add("status"); return 1; });
        List<String> result = new ArrayList<>();
        for (String command : List.of("Skin Switching SXUUZ", "skin switching SXUUZ", "skinswitch set SXUUZ", "skin reset", "skin status")) {
            assertEquals(1, dispatcher.execute(command, result));
        }
        assertEquals(List.of("SXUUZ", "SXUUZ", "SXUUZ", "reset", "status"), result);
        assertThrows(Exception.class, () -> dispatcher.execute("skin set SXUUZ someoneElse", result));
    }
    @Test void networkRoundTripKeepsWearerSeparateFromSourceAndSupportsReset() {
        UUID wearer = UUID.randomUUID(); SkinProfile source = profile();
        for (SkinPayload payload : List.of(new SkinPayload(wearer, source), new SkinPayload(wearer, null))) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SkinPayload.CODEC.encode(buffer, payload);
                SkinPayload decoded = SkinPayload.CODEC.decode(buffer);
                assertEquals(payload, decoded); assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
}
