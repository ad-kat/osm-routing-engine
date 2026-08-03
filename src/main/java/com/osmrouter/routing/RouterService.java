package com.osmrouter.routing;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Edge;
import com.osmrouter.model.Node;
import com.osmrouter.model.RouteResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * High-level routing service that:
 *  1. Snaps arbitrary lat/lon coordinates to the nearest graph node
 *  2. Delegates shortest-path computation to the chosen algorithm
 *  3. Returns a {@link RouteResult} with GeoJSON output ready for map rendering
 */
public class RouterService {

    
    private final RoadGraph graph;
    private final AtomicLong requestCount  = new AtomicLong();
    private final AtomicLong totalComputeMs = new AtomicLong();

    public RouterService(RoadGraph graph) { this.graph = graph; }

    /** Backward-compatible overload — defaults to DRIVING. */
    public Optional<RouteResult> route(double originLat, double originLon,
                                       double destLat, double destLon, String algorithm) {
        return route(originLat, originLon, destLat, destLon, algorithm, TravelProfile.DRIVING);
    }

    public Optional<RouteResult> route(double originLat, double originLon,
                                       double destLat, double destLon,
                                       String algorithm, TravelProfile profile) {
        Optional<Node> originNode = graph.nearestNode(originLat, originLon);
        Optional<Node> destNode   = graph.nearestNode(destLat, destLon);
        if (originNode.isEmpty() || destNode.isEmpty()) {
            System.err.printf("Could not snap coordinates to graph nodes%n");
            return Optional.empty();
        }
        System.out.printf("Routing %d -> %d via %s [%s]%n",
                originNode.get().id(), destNode.get().id(), algorithm, profile);

        RoutingEngine engine = RoutingEngineFactory.get(algorithm);
        Optional<RouteResult> result =
            engine.route(graph, originNode.get().id(), destNode.get().id(), profile)
                  .map(r -> r.withDirections(buildDirections(r.path())));

        result.ifPresentOrElse(
                r -> { System.out.printf("Route found: %s%n", r); requestCount.incrementAndGet(); totalComputeMs.addAndGet(r.computeTimeMs()); },
                ()  -> System.err.printf("No route found%n"));
        return result;
    }

    /** Multi-stop waypoint routing: chains A→B, B→C, etc. into one result. */
    public Optional<RouteResult> routeWaypoints(double[][] waypoints, String algorithm, TravelProfile profile) {
        if (waypoints.length < 2) return Optional.empty();
        List<Node> fullPath = new ArrayList<>();
        double totalDist = 0, totalTime = 0;
        int totalExplored = 0;
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < waypoints.length - 1; i++) {
            Optional<RouteResult> seg = route(
                waypoints[i][0], waypoints[i][1], waypoints[i+1][0], waypoints[i+1][1], algorithm, profile);
            if (seg.isEmpty()) return Optional.empty();
            RouteResult r = seg.get();
            List<Node> p = r.path();
            fullPath.addAll(fullPath.isEmpty() ? p : p.subList(1, p.size()));
            totalDist    += r.totalDistanceMetres();
            totalTime    += r.estimatedTimeSeconds();
            totalExplored += r.nodesExplored();
        }
        long ms = System.currentTimeMillis() - startMs;
        return Optional.of(new RouteResult(fullPath, totalDist, totalTime, algorithm, ms, totalExplored)
                               .withDirections(buildDirections(fullPath)));
    }

    /** Generate human-readable turn-by-turn steps from a node path. */
    private List<String> buildDirections(List<Node> path) {
        if (path.size() < 2) return List.of("Start at destination");
        List<String> steps = new ArrayList<>();
        String currentRoad = graph.getEdgeRoadName(path.get(0).id(), path.get(1).id());
        double segDist = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            String road = graph.getEdgeRoadName(path.get(i).id(), path.get(i + 1).id());
            double dist = path.get(i).distanceTo(path.get(i + 1));
            if (!road.equals(currentRoad) && !currentRoad.isEmpty()) {
                steps.add(String.format("Follow %s for %.0f m", currentRoad, segDist));
                currentRoad = road;
                segDist = dist;
            } else { segDist += dist; }
        }
        if (segDist > 0)
            steps.add(String.format("Follow %s for %.0f m", currentRoad.isEmpty() ? "road" : currentRoad, segDist));
        steps.add("Arrive at destination");
        return steps;
    }

    public void benchmark(double originLat, double originLon, double destLat, double destLon) {
        benchmark(originLat, originLon, destLat, destLon, TravelProfile.DRIVING);
    }

    public void benchmark(double originLat, double originLon, double destLat, double destLon, TravelProfile profile) {
        System.out.printf("%n=== Routing Benchmark [%s] ===%n", profile);
        for (String algo : new String[]{"dijkstra", "astar"}) {
            Optional<RouteResult> r = route(originLat, originLon, destLat, destLon, algo, profile);
            r.ifPresentOrElse(
                result -> System.out.printf("%-10s | %.2f km | %s | %d nodes explored | %d ms%n",
                    result.algorithm(), result.totalDistanceKm(),
                    result.formattedTime(), result.nodesExplored(), result.computeTimeMs()),
                () -> System.out.printf("%-10s | NO ROUTE FOUND%n", algo));
        }
        System.out.println("=========================\n");
    }

    /** Isochrone: all nodes reachable within `minutes` travel time. Returns GeoJSON Polygon. */
    public Optional<String> isochrone(double lat, double lon, int minutes, TravelProfile profile) {
        Optional<Node> origin = graph.nearestNode(lat, lon);
        if (origin.isEmpty()) return Optional.empty();

        double maxSec = minutes * 60.0;
        java.util.Map<Long, Double> dist = new java.util.HashMap<>();
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(
            java.util.Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        dist.put(origin.get().id(), 0.0);
        pq.offer(new long[]{origin.get().id(), 0L});

        while (!pq.isEmpty()) {
            long[] c = pq.poll();
            long nodeId = c[0];
            double cost = Double.longBitsToDouble(c[1]);
            if (cost > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;
            for (Edge e : graph.getEdges(nodeId)) {
                if (!profile.allows(e.highwayType())) continue;
                double nc = cost + e.weightMetres() / profile.speedMs(e.highwayType());
                if (nc <= maxSec && nc < dist.getOrDefault(e.toId(), Double.MAX_VALUE)) {
                    dist.put(e.toId(), nc);
                    pq.offer(new long[]{e.toId(), Double.doubleToLongBits(nc)});
                }
            }
        }

        List<double[]> pts = dist.keySet().stream()
            .map(graph::getNode).filter(Objects::nonNull)
            .map(n -> new double[]{n.lon(), n.lat()})
            .collect(Collectors.toList());

        if (pts.size() < 3) return Optional.empty();
        List<double[]> hull = convexHull(pts);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[");
        for (double[] p : hull) sb.append(String.format("[%.6f,%.6f],", p[0], p[1]));
        sb.append(String.format("[%.6f,%.6f]", hull.get(0)[0], hull.get(0)[1]));
        sb.append(String.format("]]},\"properties\":{\"minutes\":%d,\"reachable_nodes\":%d}}", minutes, pts.size()));
        return Optional.of(sb.toString());
    }

    private List<double[]> convexHull(List<double[]> pts) {
        double[] pivot = pts.stream().min(java.util.Comparator.comparingDouble(p -> p[1] + p[0]*1e-9)).orElse(pts.get(0));
        List<double[]> sorted = pts.stream().filter(p -> p != pivot)
            .sorted(java.util.Comparator.comparingDouble(p -> Math.atan2(p[1]-pivot[1], p[0]-pivot[0])))
            .collect(Collectors.toList());
        sorted.add(0, pivot);
        LinkedList<double[]> hull = new LinkedList<>();
        for (double[] p : sorted) {
            while (hull.size() >= 2) {
                double[] a = hull.get(hull.size()-2), b = hull.getLast();
                if ((b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0]) <= 0) hull.removeLast();
                else break;
            }
            hull.add(p);
        }
        return hull;
    }

    private static final double PENALTY = 5.0;

    /** Up to `count` alternative routes using edge-penalty A*. Returns GeoJSON FeatureCollection. */
    public String alternatives(double oLat, double oLon, double dLat, double dLon,
                                int count, TravelProfile profile) {
        Set<String> penalized = new HashSet<>();
        List<String> features = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Optional<RouteResult> r = routeWithPenalty(oLat, oLon, dLat, dLon, profile, penalized);
            if (r.isEmpty()) break;
            features.add(r.get().toGeoJson());
            List<Node> path = r.get().path();
            for (int j = 0; j < path.size()-1; j++)
                penalized.add(path.get(j).id() + "," + path.get(j+1).id());
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
    }

    private Optional<RouteResult> routeWithPenalty(double oLat, double oLon, double dLat, double dLon,
                                                     TravelProfile profile, Set<String> penalized) {
        Optional<Node> origin = graph.nearestNode(oLat, oLon);
        Optional<Node> dest   = graph.nearestNode(dLat, dLon);
        if (origin.isEmpty() || dest.isEmpty()) return Optional.empty();

        long src = origin.get().id(), tgt = dest.get().id();
        Node target = graph.getNode(tgt);

        java.util.Map<Long, Double> gScore = new java.util.HashMap<>();
        java.util.Map<Long, Long>   prev   = new java.util.HashMap<>();
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>(
            java.util.Comparator.comparingDouble(a -> Double.longBitsToDouble(a[2])));

        gScore.put(src, 0.0);
        open.offer(new long[]{-1L, src, Double.doubleToLongBits(graph.getNode(src).distanceTo(target) / profile.maxSpeedMs())});
        int explored = 0;
        long startMs = System.currentTimeMillis();

        while (!open.isEmpty()) {
            long[] c = open.poll();
            long prevId = c[0], nodeId = c[1];
            double g = gScore.getOrDefault(nodeId, Double.MAX_VALUE);
            explored++;
            if (nodeId == tgt) break;
            for (Edge e : graph.getEdges(nodeId)) {
                if (!profile.allows(e.highwayType())) continue;
                if (graph.isRestricted(prevId, nodeId, e.toId())) continue;
                double cost = e.weightMetres() / profile.speedMs(e.highwayType());
                if (penalized.contains(nodeId + "," + e.toId())) cost *= PENALTY;
                double ng = g + cost;
                if (ng < gScore.getOrDefault(e.toId(), Double.MAX_VALUE)) {
                    gScore.put(e.toId(), ng);
                    prev.put(e.toId(), nodeId);
                    Node nb = graph.getNode(e.toId());
                    double h = nb != null ? nb.distanceTo(target) / profile.maxSpeedMs() : 0;
                    open.offer(new long[]{nodeId, e.toId(), Double.doubleToLongBits(ng + h)});
                }
            }
        }

        if (!gScore.containsKey(tgt)) return Optional.empty();
        LinkedList<Node> path = new LinkedList<>();
        long curr = tgt;
        while (curr != src) {
            path.addFirst(graph.getNode(curr));
            Long p = prev.get(curr);
            if (p == null) return Optional.empty();
            curr = p;
        }
        path.addFirst(graph.getNode(src));
        long ms = System.currentTimeMillis() - startMs;
        return Optional.of(new RouteResult(path, DijkstraEngine.pathDistance(path), gScore.get(tgt), "astar-alt", ms, explored)
                               .withDirections(buildDirections(path)));
    }

    public RoadGraph getGraph()      { return graph; }
    public long getRequestCount()    { return requestCount.get(); }
    public long getTotalComputeMs()  { return totalComputeMs.get(); }
}