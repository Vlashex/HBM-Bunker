package dev.deadearth.bridge;

public final class BridgeFailure extends RuntimeException {
    private final String code;
    public BridgeFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
