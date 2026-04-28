package com.osmrouter;

import com.osmrouter.api.RoutingApiServer;
import com.osmrouter.graph.RoadGraph;
import com.osmrouter.parser.OsmParser;
import com.osmrouter.parser.SyntheticOsmGenerator;
import com.osmrouter.routing.RouterService;

/**
 * Entry point for the OSM Routing Engine.
 *
 * Usage:
 *   java -jar osm-router.jar                  # Demo mode: synthetic grid network
 *   java -jar osm-router.jar path/to/map.osm  # Load a real OSM extract
 *   java -jar osm-router.jar demo --serve     # Demo + start REST API on port 8080
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        OSM Graph Routing Engine          ║");
        System.out.println("║   Dijkstra & A* on OpenStreetMap data    ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        String osmFile = "/tmp/synthetic.osm";
        boolean serveApi = false;
        boolean realData = false;

        // Parse args
        for (String arg : args) {
            if (arg.endsWith(".osm")) { osmFile = arg; realData = true; }
            if (arg.equals("--serve")) serveApi = true;
        }

        // Generate synthetic data if no real OSM file provided
        if (!realData) {
            System.out.println("No OSM file provided — generating synthetic 15x15 grid network...");
            System.out.println("(Pass a real .osm file as argument for real-world routing)\n");
            // 15x15 grid around central London coordinates
            SyntheticOsmGenerator.generate(osmFile, 15, 15, 51.490, -0.180, 0.003);
        }

        // Parse OSM and build graph
        OsmParser parser = new OsmParser();
        RoadGraph graph  = parser.parse(osmFile);

        System.out.printf("%nGraph loaded: %,d nodes, %,d edges%n%n", graph.nodeCount(), graph.edgeCount());

        // Create routing service
        RouterService router = new RouterService(graph);

        // Demo: route across the synthetic/real network
        System.out.println("── Demo Routes ──────────────────────────────────────");

        // Route 1: SW corner to NE corner (long route)
        System.out.println("\n[Route 1] SW corner → NE corner (cross-city)");
        router.benchmark(51.490, -0.180, 51.531, -0.138);

        // Route 2: Short hop — adjacent blocks
        System.out.println("[Route 2] Short hop — adjacent intersection");
        router.benchmark(51.493, -0.177, 51.496, -0.174);

        // Route 3: Mid-network diagonal
        System.out.println("[Route 3] Mid-network point → far corner");
        router.benchmark(51.505, -0.162, 51.528, -0.141);

        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("\nKey insight: A* explores fewer nodes than Dijkstra");
        System.out.println("because its Haversine heuristic guides search toward");
        System.out.println("the destination — critical for large production maps.");

        // Optionally start REST API
        if (serveApi) {
            RoutingApiServer apiServer = new RoutingApiServer(router, 8080);
            apiServer.start();
            System.out.println("\nAPI server running. Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(apiServer::stop));
            Thread.currentThread().join();
        }
    }
}
