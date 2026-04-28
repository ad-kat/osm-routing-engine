package com.osmrouter.api;


import com.osmrouter.model.RouteResult;
import com.osmrouter.routing.RouterService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;



import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Minimal REST API server exposing two endpoints:
 *
 *   GET /route?originLat=&originLon=&destLat=&destLon=&algorithm=
 *       → GeoJSON Feature with route geometry and metadata
 *
 *   GET /health
 *       → {"status":"ok","nodes":N,"edges":M}
 *
 * Uses the JDK's built-in com.sun.net.httpserver — no external
 * framework dependency needed, keeping the binary lean.
 */
public class RoutingApiServer {

    
    private final RouterService routerService;
    private final int port;
    private HttpServer server;

    public RoutingApiServer(RouterService routerService, int port) {
        this.routerService = routerService;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/route",  this::handleRoute);
        server.createContext("/health", this::handleHealth);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // Java 21 virtual threads
        server.start();
        System.out.printf("Routing API server started on http://localhost:{}", port);
        System.out.printf("Example: http://localhost:{}/route?originLat=51.5074&originLon=-0.1278&destLat=51.5033&destLon=-0.1195&algorithm=astar", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ---------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------

    private void handleRoute(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI());
            double originLat = Double.parseDouble(params.getOrDefault("originLat", "0"));
            double originLon = Double.parseDouble(params.getOrDefault("originLon", "0"));
            double destLat   = Double.parseDouble(params.getOrDefault("destLat",   "0"));
            double destLon   = Double.parseDouble(params.getOrDefault("destLon",   "0"));
            String algorithm = params.getOrDefault("algorithm", "astar");

            Optional<RouteResult> result = routerService.route(originLat, originLon, destLat, destLon, algorithm);

            if (result.isPresent()) {
                sendResponse(ex, 200, result.get().toGeoJson());
            } else {
                sendResponse(ex, 404, "{\"error\":\"No route found between the given coordinates\"}");
            }
        } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"Invalid coordinate parameters\"}");
        } catch (Exception e) {
            System.err.printf("Route handler error", e);
            sendResponse(ex, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        String body = String.format(
                "{\"status\":\"ok\",\"nodes\":%d,\"edges\":%d}",
                routerService.getGraph().nodeCount(),
                routerService.getGraph().edgeCount()
        );
        sendResponse(ex, 200, body);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void sendResponse(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], kv[1]);
        }
        return params;
    }
}
