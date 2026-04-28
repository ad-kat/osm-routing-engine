package com.osmrouter.parser;

import com.osmrouter.graph.RoadGraph;
import com.osmrouter.model.Node;



import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Streaming StAX parser for OpenStreetMap XML (.osm) files.
 *
 * Uses StAX (Streaming API for XML) rather than DOM so that large
 * OSM extracts (hundreds of MB) are parsed in O(1) memory per element
 * rather than loading the entire document tree.
 *
 * Only road-type ways (highway=*) are retained; pedestrian paths,
 * waterways, and buildings are filtered out to keep the graph lean.
 */
public class OsmParser {

    

    /** OSM highway values we route on — mirrors HERE's road classification */
    private static final Set<String> ROUTABLE_HIGHWAY_TYPES = Set.of(
            "motorway", "motorway_link",
            "trunk", "trunk_link",
            "primary", "primary_link",
            "secondary", "secondary_link",
            "tertiary", "tertiary_link",
            "residential", "living_street",
            "unclassified", "road", "service"
    );

    public RoadGraph parse(String osmFilePath) throws Exception {
        System.out.printf("Parsing OSM file: {}", osmFilePath);
        long start = System.currentTimeMillis();

        RoadGraph graph = new RoadGraph();

        // First pass: collect all nodes
        Map<Long, Node> allNodes = new HashMap<>();
        try (InputStream is = new FileInputStream(osmFilePath)) {
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(is);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "node".equals(reader.getLocalName())) {
                    long id  = Long.parseLong(reader.getAttributeValue(null, "id"));
                    String latStr = reader.getAttributeValue(null, "lat");
                    String lonStr = reader.getAttributeValue(null, "lon");
                    if (latStr != null && lonStr != null) {
                        allNodes.put(id, new Node(id, Double.parseDouble(latStr), Double.parseDouble(lonStr)));
                    }
                }
            }
            reader.close();
        }
        System.out.printf("First pass: {} raw OSM nodes loaded", allNodes.size());

        // Second pass: collect routable ways, add referenced nodes to graph
        Set<Long> usedNodeIds = new HashSet<>();
        List<WayData> ways = new ArrayList<>();

        try (InputStream is = new FileInputStream(osmFilePath)) {
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(is);
            WayData currentWay = null;

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "way" -> currentWay = new WayData();
                        case "nd" -> {
                            if (currentWay != null) {
                                long ref = Long.parseLong(reader.getAttributeValue(null, "ref"));
                                currentWay.nodeRefs.add(ref);
                            }
                        }
                        case "tag" -> {
                            if (currentWay != null) {
                                String k = reader.getAttributeValue(null, "k");
                                String v = reader.getAttributeValue(null, "v");
                                if ("highway".equals(k))  currentWay.highwayType = v;
                                if ("name".equals(k))     currentWay.name = v;
                                if ("oneway".equals(k) && "yes".equals(v)) currentWay.oneway = true;
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "way".equals(reader.getLocalName())) {
                    if (currentWay != null && ROUTABLE_HIGHWAY_TYPES.contains(currentWay.highwayType)) {
                        ways.add(currentWay);
                        usedNodeIds.addAll(currentWay.nodeRefs);
                    }
                    currentWay = null;
                }
            }
            reader.close();
        }

        // Add only nodes that are part of routable ways
        for (long nodeId : usedNodeIds) {
            Node n = allNodes.get(nodeId);
            if (n != null) graph.addNode(n);
        }

        // Build edges from ways
        for (WayData way : ways) {
            for (int i = 0; i < way.nodeRefs.size() - 1; i++) {
                long fromId = way.nodeRefs.get(i);
                long toId   = way.nodeRefs.get(i + 1);
                if (!graph.containsNode(fromId) || !graph.containsNode(toId)) continue;

                if (way.oneway) {
                    Node from = graph.getNode(fromId);
                    Node to   = graph.getNode(toId);
                    double dist = from.distanceTo(to);
                    graph.addEdge(new com.osmrouter.model.Edge(fromId, toId, dist, way.name, way.highwayType));
                } else {
                    graph.addBidirectionalEdge(fromId, toId, way.name, way.highwayType);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Graph built in {}ms: {}", elapsed, graph);
        return graph;
    }

    /** Mutable accumulator for a single OSM way during parsing */
    private static class WayData {
        List<Long> nodeRefs = new ArrayList<>();
        String highwayType = "";
        String name = "";
        boolean oneway = false;
    }
}
