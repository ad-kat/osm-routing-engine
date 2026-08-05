package com.osmrouter.api;


import com.osmrouter.model.RouteResult;
import com.osmrouter.routing.RouterService;
import com.osmrouter.routing.TravelProfile;
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
        server.createContext("/route/alternatives", this::handleAlternatives);
        server.createContext("/route/waypoints",    this::handleWaypoints);
        server.createContext("/route",              this::handleRoute);
        server.createContext("/isochrone",          this::handleIsochrone);
        server.createContext("/health",             this::handleHealth);
        server.createContext("/metrics",            this::handleMetrics);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // Java 21 virtual threads
        server.start();
        System.out.printf("Routing API server started on http://localhost:%d%n", port);
        System.out.printf("Example: http://localhost:%d/route?originLat=51.5074&originLon=-0.1278&destLat=51.5033&destLon=-0.1195&algorithm=astar&profile=driving%n", port);
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
            if (Math.abs(originLat) > 90 || Math.abs(destLat) > 90 ||
                Math.abs(originLon) > 180 || Math.abs(destLon) > 180)
                throw new NumberFormatException("Coordinates out of range");
            String algorithm = params.getOrDefault("algorithm", "astar");
            TravelProfile profile = TravelProfile.from(params.getOrDefault("profile", "driving"));

            Optional<RouteResult> result = routerService.route(originLat, originLon, destLat, destLon, algorithm, profile);
            if (result.isPresent()) {
                sendResponse(ex, 200, result.get().toGeoJson());
            } else {
                sendResponse(ex, 404, "{\"error\":\"No route found between the given coordinates\"}");
            }
        } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"Invalid coordinate parameters\"}");
        } catch (Exception e) {
            System.err.printf("Route handler error: %s%n", e.getMessage());
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

    private void handleIsochrone(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI());
            double lat = Double.parseDouble(params.getOrDefault("lat", "0"));
            double lon = Double.parseDouble(params.getOrDefault("lon", "0"));
            int minutes = Integer.parseInt(params.getOrDefault("minutes", "15"));
            TravelProfile profile = TravelProfile.from(params.getOrDefault("profile", "driving"));
            Optional<String> result = routerService.isochrone(lat, lon, minutes, profile);
            if (result.isPresent()) sendResponse(ex, 200, result.get());
            else sendResponse(ex, 404, "{\"error\":\"Could not compute isochrone\"}");
        } catch (Exception e) {
            sendResponse(ex, 400, "{\"error\":\"Invalid parameters\"}");
        }
    }

    private void handleAlternatives(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI());
            double oLat = Double.parseDouble(params.getOrDefault("originLat", "0"));
            double oLon = Double.parseDouble(params.getOrDefault("originLon", "0"));
            double dLat = Double.parseDouble(params.getOrDefault("destLat",   "0"));
            double dLon = Double.parseDouble(params.getOrDefault("destLon",   "0"));
            int count   = Integer.parseInt(params.getOrDefault("count", "3"));
            TravelProfile profile = TravelProfile.from(params.getOrDefault("profile", "driving"));
            sendResponse(ex, 200, routerService.alternatives(oLat, oLon, dLat, dLon, count, profile));
        } catch (Exception e) {
            sendResponse(ex, 400, "{\"error\":\"Invalid parameters\"}");
        }
    }

    private void handleWaypoints(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI());
            String[] pts = params.getOrDefault("waypoints", "").split(";");
            if (pts.length < 2) { sendResponse(ex, 400, "{\"error\":\"Need >=2 waypoints (lat,lon;lat,lon)\"}"); return; }
            double[][] waypoints = new double[pts.length][2];
            for (int i = 0; i < pts.length; i++) {
                String[] ll = pts[i].split(",");
                waypoints[i][0] = Double.parseDouble(ll[0]);
                waypoints[i][1] = Double.parseDouble(ll[1]);
            }
            String algorithm = params.getOrDefault("algorithm", "astar");
            TravelProfile profile = TravelProfile.from(params.getOrDefault("profile", "driving"));
            Optional<RouteResult> result = routerService.routeWaypoints(waypoints, algorithm, profile);
            if (result.isPresent()) sendResponse(ex, 200, result.get().toGeoJson());
            else sendResponse(ex, 404, "{\"error\":\"No route found between waypoints\"}");
        } catch (Exception e) {
            sendResponse(ex, 400, "{\"error\":\"Invalid waypoints. Use: ?waypoints=lat,lon;lat,lon\"}");
        }
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        long reqs = routerService.getRequestCount();
        double avgMs = reqs > 0 ? (double) routerService.getTotalComputeMs() / reqs : 0.0;
        String body = String.format(
            "{\"nodes\":%d,\"edges\":%d,\"total_requests\":%d,\"avg_compute_ms\":%.1f}",
            routerService.getGraph().nodeCount(), routerService.getGraph().edgeCount(), reqs, avgMs);
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
