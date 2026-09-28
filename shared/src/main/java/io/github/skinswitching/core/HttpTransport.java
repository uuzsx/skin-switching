package io.github.skinswitching.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class HttpTransport {
    private static final ExecutorService IO = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "skin-switching-io"); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private HttpTransport() {}
    public static <T> CompletableFuture<T> async(Supplier<T> task) {
        try { return CompletableFuture.supplyAsync(task, IO); }
        catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(new SkinException("busy")); }
    }
    public static byte[] get(URI uri, int limit) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "SkinSwitching/0.2.0");
            int status = connection.getResponseCode();
            if (status == 404 || status == 204) throw new SkinException("not_found");
            if (status == 429) throw new SkinException("rate_limit");
            if (status != 200) throw new SkinException("network");
            if (connection.getContentLengthLong() > limit) throw new SkinException("invalid_profile");
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(limit + 1);
                if (body.length > limit) throw new SkinException("invalid_profile");
                return body;
            }
        } catch (IOException e) { throw new SkinException("network", e); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
