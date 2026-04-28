package com.osmrouter.model;

import java.util.List;

/**
 * Encapsulates a computed route: the ordered list of nodes,
 * total distance, estimated travel time, and the algorithm used.
 */
public record RouteResult(
        List<Node> path,
        double totalDistanceMetres,
        double estimatedTimeSeconds,
        String algorithm,
        long computeTimeMs,
        int nodesExplored
) {
    public double totalDistanceKm() {
        return totalDistanceMetres / 1000.0;
    }

    public String formattedTime() {
        int mins = (int) (estimatedTimeSeconds / 60);
        int secs = (int) (estimatedTimeSeconds % 60);
        return String.format("%d min %d sec", mins, secs);
    }

    /** GeoJSON LineString for map rendering */
    public String toGeoJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        for (int i = 0; i < path.size(); i++) {
            Node n = path.get(i);
            sb.append(String.format("[%.6f,%.6f]", n.lon(), n.lat()));
            if (i < path.size() - 1) sb.append(",");
        }
        sb.append("]},\"properties\":{");
        sb.append(String.format("\"distance_km\":%.3f,", totalDistanceKm()));
        sb.append(String.format("\"estimated_time\":\"%s\",", formattedTime()));
        sb.append(String.format("\"algorithm\":\"%s\",", algorithm));
        sb.append(String.format("\"compute_time_ms\":%d,", computeTimeMs));
        sb.append(String.format("\"nodes_explored\":%d,", nodesExplored));
        sb.append(String.format("\"path_nodes\":%d", path.size()));
        sb.append("}}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("Route[%s]: %.2f km, %s, %d nodes explored in %dms",
                algorithm, totalDistanceKm(), formattedTime(), nodesExplored, computeTimeMs);
    }
}
