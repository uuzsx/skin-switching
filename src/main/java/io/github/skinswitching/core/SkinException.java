package io.github.skinswitching.core;

public final class SkinException extends RuntimeException {
    private final String key;
    public SkinException(String key) { super(key); this.key = key; }
    public SkinException(String key, Throwable cause) { super(key, cause); this.key = key; }
    public String key() { return key; }
    public static String keyOf(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof SkinException skin) return skin.key();
        }
        return "network";
    }
}
