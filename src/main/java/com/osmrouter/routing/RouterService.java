package com.osmrouter.routing;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Node;
import com.osmrouter.model.RouteResult;



import java.util.Optional;

/**
 * High-level routing service that:
 *  1. Snaps arbitrary lat/lon coordinates to the nearest graph node
 *  2. Delegates shortest-path computation to the chosen algorithm
 *  3. Returns a {@link RouteResult} with GeoJSON output ready for map rendering
 */
public class RouterService {

    
    private final RoadGraph graph;

    public RouterService(RoadGraph graph) {
        this.graph = graph;
    }

    /**
     * Route between two geographic coordinates using the specified algorithm.
     *
     * @param originLat    origin latitude
     * @param originLon    origin longitude
     * @param destLat      destination latitude
     * @param destLon      destination longitude
     * @param algorithm    "dijkstra" or "astar"
     */
    public Optional<RouteResult> route(double originLat, double originLon,
                                       double destLat,   double destLon,
                                       String algorithm) {

        // Snap coordinates to nearest graph nodes
        Optional<Node> originNode = graph.nearestNode(originLat, originLon);
        Optional<Node> destNode   = graph.nearestNode(destLat, destLon);

        if (originNode.isEmpty() || destNode.isEmpty()) {
            System.err.printf("Could not snap coordinates to graph nodes%n");
            return Optional.empty();
        }

        System.out.printf("Routing %d -> %d via %s%n",
                originNode.get().id(), destNode.get().id(), algorithm);

        RoutingEngine engine = RoutingEngineFactory.get(algorithm);
        Optional<RouteResult> result = engine.route(graph, originNode.get().id(), destNode.get().id());

        result.ifPresentOrElse(
                r -> System.out.printf("Route found: %s%n", r),
                ()  -> System.err.printf("No route found between the given coordinates%n")
        );

        return result;
    }

    /** Compare Dijkstra vs A* on the same query and print performance stats */
    public void benchmark(double originLat, double originLon, double destLat, double destLon) {
        System.out.println("\n=== Routing Benchmark ===");
        for (String algo : new String[]{"dijkstra", "astar"}) {
            Optional<RouteResult> r = route(originLat, originLon, destLat, destLon, algo);
            r.ifPresentOrElse(
                    result -> System.out.printf(
                            "%-10s | %.2f km | %s | %d nodes explored | %d ms%n",
                            result.algorithm(), result.totalDistanceKm(),
                            result.formattedTime(), result.nodesExplored(), result.computeTimeMs()),
                    () -> System.out.printf("%-10s | NO ROUTE FOUND%n", algo)
            );
        }
        System.out.println("=========================\n");
    }

    public RoadGraph getGraph() { return graph; }
}