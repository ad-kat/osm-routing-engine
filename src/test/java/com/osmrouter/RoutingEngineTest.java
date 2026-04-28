package com.osmrouter;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Edge;
import com.osmrouter.model.Node;
import com.osmrouter.model.RouteResult;
import com.osmrouter.routing.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for graph construction, spatial indexing,
 * Dijkstra routing, and A* routing.
 */
class RoutingEngineTest {

    private RoadGraph graph;
    private RouterService router;

    /**
     * Build a small known graph:
     *
     *   A(0,0) --100m-- B(0,1) --100m-- C(0,2)
     *       \                              /
     *        \----------350m-------------/
     *
     * Shortest A→C = via B (200m), not the direct edge (350m).
     */
    @BeforeEach
    void buildGraph() {
        graph = new RoadGraph();

        // ~0.0009 degrees ≈ 100m latitude difference
        Node a = new Node(1L, 0.0000, 0.0000);
        Node b = new Node(2L, 0.0009, 0.0000);
        Node c = new Node(3L, 0.0018, 0.0000);
        Node d = new Node(4L, 0.0009, 0.0009); // dead-end branch

        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(c);
        graph.addNode(d);

        // A-B bidirectional
        double abDist = a.distanceTo(b);
        graph.addEdge(new Edge(1L, 2L, abDist, "Main St", "primary"));
        graph.addEdge(new Edge(2L, 1L, abDist, "Main St", "primary"));

        // B-C bidirectional
        double bcDist = b.distanceTo(c);
        graph.addEdge(new Edge(2L, 3L, bcDist, "Main St", "primary"));
        graph.addEdge(new Edge(3L, 2L, bcDist, "Main St", "primary"));

        // A-C direct but longer
        double acDist = a.distanceTo(c);
        graph.addEdge(new Edge(1L, 3L, acDist, "Shortcut Rd", "tertiary"));
        graph.addEdge(new Edge(3L, 1L, acDist, "Shortcut Rd", "tertiary"));

        // B-D dead-end
        double bdDist = b.distanceTo(d);
        graph.addEdge(new Edge(2L, 4L, bdDist, "Dead End", "residential"));

        router = new RouterService(graph);
    }

    // ---------------------------------------------------------------
    // Graph structure tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Graph loads correct node and edge counts")
    void testGraphStructure() {
        assertEquals(4, graph.nodeCount());
        assertEquals(7, graph.edgeCount()); // 6 bidirectional + 1 dead-end
    }

    @Test
    @DisplayName("Spatial nearest-node lookup returns correct node")
    void testNearestNode() {
        // Query very close to node B
        Optional<Node> found = graph.nearestNode(0.00091, 0.00001);
        assertTrue(found.isPresent());
        assertEquals(2L, found.get().id(), "Should snap to node B");
    }

    @Test
    @DisplayName("Nearest node returns empty on empty graph")
    void testNearestNodeEmptyGraph() {
        RoadGraph empty = new RoadGraph();
        // Should return empty since there are no nodes in extended search
        // (fallback linear scan also finds nothing)
        Optional<Node> result = empty.nearestNode(0.0, 0.0);
        assertTrue(result.isEmpty());
    }

    // ---------------------------------------------------------------
    // Dijkstra tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Dijkstra finds route between connected nodes")
    void testDijkstraBasicRoute() {
        Optional<RouteResult> result = router.route(0.0, 0.0, 0.0018, 0.0, "dijkstra");
        assertTrue(result.isPresent(), "Route should exist");
        assertEquals("Dijkstra", result.get().algorithm());
        assertFalse(result.get().path().isEmpty());
    }

    @Test
    @DisplayName("Dijkstra chooses shortest path (via B, not direct A-C edge)")
    void testDijkstraShortestPath() {
        Optional<RouteResult> result = router.route(0.0, 0.0, 0.0018, 0.0, "dijkstra");
        assertTrue(result.isPresent());
        // Via B: ~200m. Direct A-C: ~350m. Dijkstra must choose via B.
        assertTrue(result.get().totalDistanceMetres() < 250,
                "Dijkstra should pick A->B->C path (~200m), not direct edge (~350m)");
        assertEquals(3, result.get().path().size(), "Path should have 3 nodes: A, B, C");
    }

    @Test
    @DisplayName("Dijkstra returns empty for unreachable destination")
    void testDijkstraNoRoute() {
        // Add isolated node
        graph.addNode(new Node(99L, 10.0, 10.0));
        Optional<RouteResult> result = router.route(0.0, 0.0, 10.0, 10.0, "dijkstra");
        // nearestNode will snap to closest existing node, so we test with direct node IDs
        // by checking that a truly isolated graph returns empty
        RoadGraph isolated = new RoadGraph();
        isolated.addNode(new Node(1L, 0.0, 0.0));
        isolated.addNode(new Node(2L, 1.0, 1.0));
        RouterService isolatedRouter = new RouterService(isolated);
        Optional<RouteResult> r = isolatedRouter.route(0.0, 0.0, 1.0, 1.0, "dijkstra");
        assertTrue(r.isEmpty(), "Should return empty for disconnected nodes");
    }

    // ---------------------------------------------------------------
    // A* tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("A* finds same distance as Dijkstra")
    void testAStarSameDistanceAsDijkstra() {
        Optional<RouteResult> dijkstra = router.route(0.0, 0.0, 0.0018, 0.0, "dijkstra");
        Optional<RouteResult> astar    = router.route(0.0, 0.0, 0.0018, 0.0, "astar");

        assertTrue(dijkstra.isPresent() && astar.isPresent());
        assertEquals(dijkstra.get().totalDistanceMetres(),
                     astar.get().totalDistanceMetres(), 0.001,
                "A* and Dijkstra must find identical shortest distances");
    }

    @Test
    @DisplayName("A* explores fewer nodes than Dijkstra on directed query")
    void testAStarMoreEfficientThanDijkstra() {
        Optional<RouteResult> dijkstra = router.route(0.0, 0.0, 0.0018, 0.0, "dijkstra");
        Optional<RouteResult> astar    = router.route(0.0, 0.0, 0.0018, 0.0, "astar");

        assertTrue(dijkstra.isPresent() && astar.isPresent());
        assertTrue(astar.get().nodesExplored() <= dijkstra.get().nodesExplored(),
                "A* should explore no more nodes than Dijkstra");
    }

    @Test
    @DisplayName("A* algorithm name is correct")
    void testAStarAlgorithmName() {
        Optional<RouteResult> result = router.route(0.0, 0.0, 0.0018, 0.0, "astar");
        assertTrue(result.isPresent());
        assertEquals("A*", result.get().algorithm());
    }

    // ---------------------------------------------------------------
    // Haversine distance test
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Haversine distance between known coordinates is accurate")
    void testHaversineAccuracy() {
        // London to Paris: approximately 340 km
        Node london = new Node(1L, 51.5074, -0.1278);
        Node paris  = new Node(2L, 48.8566,  2.3522);
        double dist = london.distanceTo(paris);
        assertTrue(dist > 330_000 && dist < 350_000,
                "London-Paris distance should be ~340km, got: " + dist/1000 + "km");
    }

    // ---------------------------------------------------------------
    // GeoJSON output test
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RouteResult produces valid GeoJSON")
    void testGeoJsonOutput() {
        Optional<RouteResult> result = router.route(0.0, 0.0, 0.0018, 0.0, "astar");
        assertTrue(result.isPresent());
        String geoJson = result.get().toGeoJson();
        assertTrue(geoJson.contains("\"type\":\"Feature\""));
        assertTrue(geoJson.contains("\"type\":\"LineString\""));
        assertTrue(geoJson.contains("\"coordinates\""));
        assertTrue(geoJson.contains("\"distance_km\""));
        assertTrue(geoJson.contains("A*"));
    }
}
