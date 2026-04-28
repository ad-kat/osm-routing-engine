package com.osmrouter.model;

/**
 * Represents an OpenStreetMap node with a unique ID and geographic coordinates.
 * Nodes are the fundamental building blocks of the road graph.
 */
public record Node(long id, double lat, double lon) {

    /**
     * Compute Haversine great-circle distance in metres between two nodes.
     * Used as the admissible heuristic in A* search.
     */
    public double distanceTo(Node other) {
        final double R = 6_371_000.0; // Earth radius in metres
        double dLat = Math.toRadians(other.lat - this.lat);
        double dLon = Math.toRadians(other.lon - this.lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(this.lat))
                * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override
    public String toString() {
        return String.format("Node{id=%d, lat=%.6f, lon=%.6f}", id, lat, lon);
    }
}
