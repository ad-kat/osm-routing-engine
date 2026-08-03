package com.osmrouter.routing;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Edge;
import com.osmrouter.model.Node;
import com.osmrouter.model.RouteResult;

import java.util.*;

/**
 * Pluggable routing engine interface — allows Dijkstra and A* to be
 * swapped at runtime (Strategy pattern), mirroring how production
 * routing platforms expose multiple algorithm backends.
 */
public interface RoutingEngine {
    Optional<RouteResult> route(RoadGraph graph, long sourceId, long targetId, TravelProfile profile);
    default Optional<RouteResult> route(RoadGraph graph, long sourceId, long targetId) {
        return route(graph, sourceId, targetId, TravelProfile.DRIVING);
    }
    String name();
}

// ===================================================================
// Dijkstra implementation — O((V + E) log V) via binary heap
// ===================================================================

class DijkstraEngine implements RoutingEngine {

    /** Average road speed in m/s used for travel-time estimation */
    private static final double AVG_SPEED_MS = 13.9; // ~50 km/h

    @Override
    public String name() { return "Dijkstra"; }

    @Override
    public Optional<RouteResult> route(RoadGraph graph, long sourceId, long targetId, TravelProfile profile) {
        long startTime = System.currentTimeMillis();

        Map<Long, Double> dist   = new HashMap<>();
        Map<Long, Long>   prev   = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[2])));

        dist.put(sourceId, 0.0);
        pq.offer(new long[]{-1L, sourceId, 0L});
        int explored = 0;

        while (!pq.isEmpty()) {
            long[] curr   = pq.poll();
            long prevId   = curr[0];
            long nodeId   = curr[1];
            double cost   = Double.longBitsToDouble(curr[2]);

            if (cost > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;
            explored++;
            if (nodeId == targetId) break;

            for (Edge e : graph.getEdges(nodeId)) {
                if (!profile.allows(e.highwayType())) continue;
                if (graph.isRestricted(prevId, nodeId, e.toId())) continue;
                double newCost = cost + e.weightMetres() / profile.speedMs(e.highwayType());
                if (newCost < dist.getOrDefault(e.toId(), Double.MAX_VALUE)) {
                    dist.put(e.toId(), newCost);
                    prev.put(e.toId(), nodeId);
                    pq.offer(new long[]{nodeId, e.toId(), Double.doubleToLongBits(newCost)});
                }
            }
        }

        if (!dist.containsKey(targetId)) return Optional.empty();
        List<Node> path = reconstructPath(graph, prev, sourceId, targetId);
        long ms = System.currentTimeMillis() - startTime;
        return Optional.of(new RouteResult(path, pathDistance(path), dist.get(targetId), name(), ms, explored));
    }

    static double pathDistance(List<Node> path) {
        double d = 0;
        for (int i = 0; i < path.size() - 1; i++) d += path.get(i).distanceTo(path.get(i + 1));
        return d;
    }

    static List<Node> reconstructPath(RoadGraph graph, Map<Long, Long> prev, long source, long target) {
        LinkedList<Node> path = new LinkedList<>();
        long curr = target;
        while (curr != source) {
            path.addFirst(graph.getNode(curr));
            Long p = prev.get(curr);
            if (p == null) return Collections.emptyList();
            curr = p;
        }
        path.addFirst(graph.getNode(source));
        return path;
    }
}

// ===================================================================
// A* implementation — Dijkstra + Haversine heuristic
// Explores significantly fewer nodes on large graphs
// ===================================================================

class AStarEngine implements RoutingEngine {

    private static final double AVG_SPEED_MS = 13.9;

    @Override
    public String name() { return "A*"; }

    @Override
    public Optional<RouteResult> route(RoadGraph graph, long sourceId, long targetId, TravelProfile profile) {
        long startTime = System.currentTimeMillis();
        Node target = graph.getNode(targetId);
        if (target == null) return Optional.empty();

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long>   prev   = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[2])));

        gScore.put(sourceId, 0.0);
        double h0 = heuristic(graph.getNode(sourceId), target, profile);
        open.offer(new long[]{-1L, sourceId, Double.doubleToLongBits(h0)});
        int explored = 0;

        while (!open.isEmpty()) {
            long[] curr   = open.poll();
            long prevId   = curr[0];
            long nodeId   = curr[1];
            explored++;
            if (nodeId == targetId) break;

            double gCurr = gScore.getOrDefault(nodeId, Double.MAX_VALUE);
            for (Edge e : graph.getEdges(nodeId)) {
                if (!profile.allows(e.highwayType())) continue;
                if (graph.isRestricted(prevId, nodeId, e.toId())) continue;
                double tentativeG = gCurr + e.weightMetres() / profile.speedMs(e.highwayType());
                if (tentativeG < gScore.getOrDefault(e.toId(), Double.MAX_VALUE)) {
                    gScore.put(e.toId(), tentativeG);
                    prev.put(e.toId(), nodeId);
                    Node neighbour = graph.getNode(e.toId());
                    double h = (neighbour != null) ? heuristic(neighbour, target, profile) : 0;
                    open.offer(new long[]{nodeId, e.toId(), Double.doubleToLongBits(tentativeG + h)});
                }
            }
        }

        if (!gScore.containsKey(targetId)) return Optional.empty();
        List<Node> path = DijkstraEngine.reconstructPath(graph, prev, sourceId, targetId);
        long ms = System.currentTimeMillis() - startTime;
        return Optional.of(new RouteResult(path, DijkstraEngine.pathDistance(path), gScore.get(targetId), name(), ms, explored));
    }

    /** Admissible heuristic: min travel time = straight-line dist / max possible speed */
    private double heuristic(Node a, Node b, TravelProfile profile) {
        return a.distanceTo(b) / profile.maxSpeedMs();
    }
}

// ===================================================================
// Factory — select algorithm by name
// ===================================================================

/**
 * Factory for routing algorithm selection.
 * Mirrors production routing platforms that expose multiple algorithm
 * backends (shortest path, fastest path, eco-routing, etc.)
 */
class RoutingEngineFactory {
    public static RoutingEngine get(String algorithm) {
        return switch (algorithm.toLowerCase()) {
            case "astar", "a*" -> new AStarEngine();
            default            -> new DijkstraEngine();
        };
    }
}
