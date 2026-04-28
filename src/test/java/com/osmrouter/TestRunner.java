package com.osmrouter;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Edge;
import com.osmrouter.model.Node;
import com.osmrouter.model.RouteResult;
import com.osmrouter.routing.RouterService;

import java.util.Optional;

/**
 * Self-contained test runner (no external test framework needed).
 * Each test method asserts a condition and prints PASS/FAIL.
 */
public class TestRunner {

    static int passed = 0, failed = 0;

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.printf("  ✓ PASS: %s%n", name); passed++; }
        else           { System.out.printf("  ✗ FAIL: %s%n", name); failed++; }
    }

    static void assertEquals(String name, double expected, double actual, double tol) {
        assertTrue(name, Math.abs(expected - actual) <= tol);
    }

    static RoadGraph buildTestGraph() {
        RoadGraph g = new RoadGraph();
        Node a = new Node(1L, 0.0000, 0.0000);
        Node b = new Node(2L, 0.0009, 0.0000);
        Node c = new Node(3L, 0.0018, 0.0000);
        Node d = new Node(4L, 0.0009, 0.0009);
        g.addNode(a); g.addNode(b); g.addNode(c); g.addNode(d);

        double ab = a.distanceTo(b), bc = b.distanceTo(c), ac = a.distanceTo(c), bd = b.distanceTo(d);
        g.addEdge(new Edge(1L,2L,ab,"Main St","primary")); g.addEdge(new Edge(2L,1L,ab,"Main St","primary"));
        g.addEdge(new Edge(2L,3L,bc,"Main St","primary")); g.addEdge(new Edge(3L,2L,bc,"Main St","primary"));
        g.addEdge(new Edge(1L,3L,ac,"Shortcut","tertiary")); g.addEdge(new Edge(3L,1L,ac,"Shortcut","tertiary"));
        g.addEdge(new Edge(2L,4L,bd,"Dead End","residential"));
        return g;
    }

    public static void main(String[] args) {
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  OSM Routing Engine — Test Suite");
        System.out.println("══════════════════════════════════════════\n");

        RoadGraph graph = buildTestGraph();
        RouterService router = new RouterService(graph);

        // Graph structure
        System.out.println("[Graph Structure]");
        assertTrue("nodeCount == 4", graph.nodeCount() == 4);
        assertTrue("edgeCount == 7", graph.edgeCount() == 7);

        // Spatial index
        System.out.println("\n[Spatial Index]");
        Optional<Node> snapped = graph.nearestNode(0.00091, 0.00001);
        assertTrue("nearestNode snaps to node B (id=2)", snapped.isPresent() && snapped.get().id() == 2L);
        assertTrue("empty graph nearestNode returns empty", new RoadGraph().nearestNode(0,0).isEmpty());

        // Dijkstra
        System.out.println("\n[Dijkstra]");
        Optional<RouteResult> dijk = router.route(0.0, 0.0, 0.0018, 0.0, "dijkstra");
        assertTrue("Dijkstra finds route", dijk.isPresent());
        assertTrue("Dijkstra finds valid path (2+ nodes)", dijk.get().path().size() >= 2);
        assertTrue("Dijkstra picks ~200m path, not 350m direct", dijk.get().totalDistanceMetres() < 250);
        assertTrue("Dijkstra algorithm name correct", "Dijkstra".equals(dijk.get().algorithm()));

        // A*
        System.out.println("\n[A*]");
        Optional<RouteResult> astar = router.route(0.0, 0.0, 0.0018, 0.0, "astar");
        assertTrue("A* finds route", astar.isPresent());
        assertTrue("A* same distance as Dijkstra",
                Math.abs(dijk.get().totalDistanceMetres() - astar.get().totalDistanceMetres()) < 0.001);
        assertTrue("A* explores <= nodes vs Dijkstra",
                astar.get().nodesExplored() <= dijk.get().nodesExplored());
        assertTrue("A* algorithm name correct", "A*".equals(astar.get().algorithm()));

        // Haversine accuracy
        System.out.println("\n[Haversine Distance]");
        Node london = new Node(1L, 51.5074, -0.1278);
        Node paris  = new Node(2L, 48.8566,  2.3522);
        double dist = london.distanceTo(paris);
        assertTrue("London-Paris ~340km", dist > 330_000 && dist < 350_000);

        // GeoJSON
        System.out.println("\n[GeoJSON Output]");
        String gj = astar.get().toGeoJson();
        assertTrue("GeoJSON has Feature type",   gj.contains("\"type\":\"Feature\""));
        assertTrue("GeoJSON has LineString",      gj.contains("\"type\":\"LineString\""));
        assertTrue("GeoJSON has coordinates",     gj.contains("\"coordinates\""));
        assertTrue("GeoJSON has distance_km",     gj.contains("\"distance_km\""));

        // Summary
        System.out.printf("%n══════════════════════════════════════════%n");
        System.out.printf("  Results: %d passed, %d failed%n", passed, failed);
        System.out.printf("══════════════════════════════════════════%n%n");
        if (failed > 0) System.exit(1);
    }
}
