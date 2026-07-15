package com.relaydelivery.controller;

import com.relaydelivery.config.Database;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class StaticFileHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
        "js", "text/javascript; charset=utf-8", "png", "image/png", "svg", "image/svg+xml");
    private final Path root;
    private final String imageSources;

    public StaticFileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
        imageSources = Arrays.stream(Database.env("IMAGE_HOSTS", "images.unsplash.com").split(","))
            .map(String::trim).filter(host -> host.matches("[a-zA-Z0-9.-]+"))
            .map(host -> "https://" + host.toLowerCase()).distinct().collect(Collectors.joining(" "));
        if (imageSources.isBlank()) throw new IllegalArgumentException("IMAGE_HOSTS must contain a valid hostname");
    }

    @Override public void handle(HttpExchange x) throws IOException {
        try {
            if (!x.getRequestMethod().equals("GET") && !x.getRequestMethod().equals("HEAD")) {
                x.getResponseHeaders().set("Allow", "GET, HEAD");
                x.sendResponseHeaders(405, -1);
                return;
            }
            String requestPath = x.getRequestURI().getPath();
            String name = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                byte[] body = "Not found".getBytes();
                x.sendResponseHeaders(404, body.length);
                try (OutputStream out = x.getResponseBody()) { out.write(body); }
                return;
            }
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            byte[] body = Files.readAllBytes(file);
            x.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
            boolean revalidate = extension.equals("html") || extension.equals("css") || extension.equals("js");
            // Shared assets are not content-hashed, so browsers must revalidate them after a deployment.
            x.getResponseHeaders().set("Cache-Control", revalidate ? "no-cache" : "public, max-age=3600");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            x.getResponseHeaders().set("X-Frame-Options", "DENY");
            x.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: " + imageSources + "; connect-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            if (x.getRequestMethod().equals("HEAD")) {
                x.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                x.sendResponseHeaders(200, -1);
            } else {
                x.sendResponseHeaders(200, body.length);
                try (OutputStream out = x.getResponseBody()) { out.write(body); }
            }
        } finally {
            x.close();
        }
    }
}
