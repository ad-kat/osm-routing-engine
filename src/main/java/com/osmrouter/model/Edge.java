package com.osmrouter.model;

/**
 * A directed, weighted edge in the road graph.
 * Weight is the real-world distance in metres between the two nodes,
 * computed via the Haversine formula at construction time.
 */
public record Edge(long fromId, long toId, double weightMetres, String roadName, String highwayType) {

    public Edge(long fromId, long toId, double weightMetres) {
        this(fromId, toId, weightMetres, "", "unclassified");
    }

    @Override
    public String toString() {
        return String.format("Edge{%d -> %d, %.1fm, '%s' [%s]}", fromId, toId, weightMetres, roadName, highwayType);
    }
}
