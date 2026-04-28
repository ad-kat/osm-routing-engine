package com.osmrouter.graph;

import com.osmrouter.model.Edge;
import com.osmrouter.model.Node;

import java.util.*;

/**
 * Directed adjacency-list road graph built from OSM data.
 *
 * Spatial indexing: nodes are bucketed into a grid of cells
 * (each ~0.01 degrees ≈ 1 km) so nearest-node lookup is O(1)
 * average rather than O(N) linear scan — critical for snapping
 * arbitrary lat/lon query points to the graph.
 */
public class RoadGraph {

    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Edge>> adjacency = new HashMap<>();

    // Spatial grid index: cell key -> list of node IDs
    private static final double CELL_SIZE = 0.01; // degrees
    private final Map<Long, List<Long>> spatialIndex = new HashMap<>();

    // ---------------------------------------------------------------
    // Graph construction
    // ---------------------------------------------------------------

    public void addNode(Node node) {
        nodes.put(node.id(), node);
        long cellKey = cellKey(node.lat(), node.lon());
        spatialIndex.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(node.id());
    }

    public void addEdge(Edge edge) {
        adjacency.computeIfAbsent(edge.fromId(), k -> new ArrayList<>()).add(edge);
    }

    /** Add a bidirectional road segment between two nodes */
    public void addBidirectionalEdge(long fromId, long toId, String roadName, String highwayType) {
        Node from = nodes.get(fromId);
        Node to   = nodes.get(toId);
        if (from == null || to == null) return;
        double dist = from.distanceTo(to);
        addEdge(new Edge(fromId, toId, dist, roadName, highwayType));
        addEdge(new Edge(toId, fromId, dist, roadName, highwayType));
    }

    // ---------------------------------------------------------------
    // Spatial nearest-node lookup
    // ---------------------------------------------------------------

    /**
     * Find the graph node closest to the given geographic coordinate.
     * Searches the immediate cell and its 8 neighbours before expanding.
     */
    public Optional<Node> nearestNode(double lat, double lon) {
        Node best = null;
        double bestDist = Double.MAX_VALUE;

        // Search 3x3 neighbourhood of cells
        for (int dRow = -1; dRow <= 1; dRow++) {
            for (int dCol = -1; dCol <= 1; dCol++) {
                long key = cellKey(lat + dRow * CELL_SIZE, lon + dCol * CELL_SIZE);
                List<Long> bucket = spatialIndex.get(key);
                if (bucket == null) continue;
                for (long nodeId : bucket) {
                    Node n = nodes.get(nodeId);
                    double d = haversineApprox(lat, lon, n.lat(), n.lon());
                    if (d < bestDist) {
                        bestDist = d;
                        best = n;
                    }
                }
            }
        }

        // If nothing found in immediate neighbourhood, expand search
        if (best == null) {
            for (Node n : nodes.values()) {
                double d = haversineApprox(lat, lon, n.lat(), n.lon());
                if (d < bestDist) {
                    bestDist = d;
                    best = n;
                }
            }
        }

        return Optional.ofNullable(best);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public Node getNode(long id) { return nodes.get(id); }

    public List<Edge> getEdges(long nodeId) {
        return adjacency.getOrDefault(nodeId, Collections.emptyList());
    }

    public boolean containsNode(long id) { return nodes.containsKey(id); }

    public int nodeCount() { return nodes.size(); }

    public int edgeCount() {
        return adjacency.values().stream().mapToInt(List::size).sum();
    }

    public Collection<Node> allNodes() { return nodes.values(); }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private long cellKey(double lat, double lon) {
        long row = (long) Math.floor(lat / CELL_SIZE);
        long col = (long) Math.floor(lon / CELL_SIZE);
        // Cantor pairing to get a unique long key
        return row * 1_000_000L + col;
    }

    /** Fast approximate distance for spatial indexing (no trig) */
    private double haversineApprox(double lat1, double lon1, double lat2, double lon2) {
        double dLat = lat1 - lat2;
        double dLon = (lon1 - lon2) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.sqrt(dLat * dLat + dLon * dLon) * 111_320;
    }

    @Override
    public String toString() {
        return String.format("RoadGraph{nodes=%d, edges=%d}", nodeCount(), edgeCount());
    }
}
