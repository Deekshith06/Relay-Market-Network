package com.relaydelivery.util;

public final class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
    public static ApiException badRequest(String message) { return new ApiException(400, message); }
    public static ApiException unauthorized() { return new ApiException(401, "Authentication required"); }
    public static ApiException forbidden() { return new ApiException(403, "You cannot perform this action"); }
    public static ApiException notFound(String resource) { return new ApiException(404, resource + " not found"); }
    public static ApiException conflict(String message) { return new ApiException(409, message); }
}
