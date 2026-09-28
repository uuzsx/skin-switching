package io.github.skinswitching.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {
    private HttpServer server;
    private URI base;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = switch (path) { case "/missing" -> 404; case "/limited" -> 429; case "/redirect" -> 302; default -> 200; };
            if (status == 302) exchange.getResponseHeaders().add("Location", "/ok");
            byte[] body = path.equals("/large") ? new byte[128] : new byte[]{1,2,3};
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void handlesSuccess() { assertArrayEquals(new byte[]{1,2,3}, HttpTransport.get(base.resolve("/ok"), 16)); }
    @Test void distinguishesNotFoundAndRateLimit() {
        assertEquals("not_found", assertThrows(SkinException.class, () -> HttpTransport.get(base.resolve("/missing"), 16)).key());
        assertEquals("rate_limit", assertThrows(SkinException.class, () -> HttpTransport.get(base.resolve("/limited"), 16)).key());
    }
    @Test void rejectsOversizedBodiesAndRedirects() {
        assertEquals("invalid_profile", assertThrows(SkinException.class, () -> HttpTransport.get(base.resolve("/large"), 16)).key());
        assertEquals("network", assertThrows(SkinException.class, () -> HttpTransport.get(base.resolve("/redirect"), 16)).key());
    }
}
