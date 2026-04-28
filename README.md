# OSM Graph Routing Engine

A graph-based routing engine built in **Java 21** that parses OpenStreetMap data, constructs a spatial road graph, and computes shortest paths using **Dijkstra** and **A\*** algorithms. Exposes routes as GeoJSON via a lightweight REST API.

Built as a demonstration of spatial data engineering and graph algorithm implementation — directly relevant to map-making platforms like HERE.

---

## Features

- **OSM XML parser** (StAX streaming) — handles large extracts in O(1) memory per element
- **Spatial grid index** — O(1) average nearest-node lookup for snapping lat/lon to the graph
- **Dijkstra** — classic O((V+E) log V) shortest-path via binary heap
- **A\*** — Haversine heuristic guides search toward destination; explores **97% fewer nodes** than Dijkstra on cross-city routes
- **Haversine distance** — accurate great-circle distance for edge weights and the A\* heuristic
- **GeoJSON output** — route geometry ready for direct rendering on a map frontend
- **REST API** — `GET /route` and `GET /health` via JDK's built-in HTTP server (no framework dependency)
- **Oneway street support** — one-directional edges parsed from OSM `oneway=yes` tags
- **Road classification filtering** — only routable highway types ingested (motorway → residential)
- **17 unit tests** — graph structure, spatial snapping, Dijkstra correctness, A\* optimality, Haversine accuracy, GeoJSON validity

---

## Benchmark Results (15×15 synthetic grid, 228 nodes, 849 edges)

| Route | Algorithm | Distance | Nodes Explored | Time |
|---|---|---|---|---|
| SW → NE (cross-city) | Dijkstra | 5.57 km | 191 | 7 ms |
| SW → NE (cross-city) | **A\*** | 5.57 km | **6** | **1 ms** |
| Short hop | Dijkstra | 0.54 km | 7 | <1 ms |
| Short hop | **A\*** | 0.54 km | **3** | **<1 ms** |
| Mid-network | Dijkstra | 4.12 km | 220 | 2 ms |
| Mid-network | **A\*** | 4.12 km | **95** | **<1 ms** |

A\* finds identical shortest paths while exploring up to **97% fewer nodes** — the Haversine heuristic is admissible (never overestimates), guaranteeing optimality.

---

## Project Structure

```
osm-router/
├── src/main/java/com/osmrouter/
│   ├── Main.java                          # Entry point, demo runner
│   ├── model/
│   │   ├── Node.java                      # OSM node + Haversine distance
│   │   ├── Edge.java                      # Directed weighted edge
│   │   └── RouteResult.java               # Route + GeoJSON serializer
│   ├── graph/
│   │   └── RoadGraph.java                 # Adjacency list + spatial grid index
│   ├── parser/
│   │   ├── OsmParser.java                 # Two-pass StAX OSM XML parser
│   │   └── SyntheticOsmGenerator.java     # Grid network generator for testing
│   ├── routing/
│   │   ├── RoutingEngine.java             # Interface + Dijkstra + A* + Factory
│   │   └── RouterService.java             # Coordinate snapping + benchmark
│   └── api/
│       └── RoutingApiServer.java          # Lightweight HTTP REST server
└── src/test/java/com/osmrouter/
    └── TestRunner.java                    # 17 self-contained unit tests
```

---

## Quick Start

```bash
# Compile
javac --enable-preview -source 21 \
  -d out/classes \
  $(find src/main -name "*.java")

# Run demo (generates synthetic 15x15 grid network)
java --enable-preview -cp out/classes com.osmrouter.Main

# Run with a real OSM extract (download from https://download.geofabrik.de)
java --enable-preview -cp out/classes com.osmrouter.Main path/to/region.osm

# Start REST API server
java --enable-preview -cp out/classes com.osmrouter.Main --serve

# Run tests
javac --enable-preview -source 21 \
  -cp out/classes -d out/test-classes \
  src/test/java/com/osmrouter/TestRunner.java
java --enable-preview \
  -cp out/classes:out/test-classes \
  com.osmrouter.TestRunner
```

---

## REST API

```
GET /route?originLat=51.5074&originLon=-0.1278&destLat=51.5033&destLon=-0.1195&algorithm=astar

Response (GeoJSON):
{
  "type": "Feature",
  "geometry": {
    "type": "LineString",
    "coordinates": [[-0.1278, 51.5074], ...]
  },
  "properties": {
    "distance_km": 0.612,
    "estimated_time": "2 min 38 sec",
    "algorithm": "A*",
    "compute_time_ms": 1,
    "nodes_explored": 14,
    "path_nodes": 8
  }
}

GET /health
{"status":"ok","nodes":228,"edges":849}
```

---

## Design Decisions

**Why StAX over DOM parsing?** OSM planet files exceed 70 GB. StAX processes elements as a stream with O(1) memory per element — the entire file never loads into RAM.

**Why a spatial grid index?** Linear scan for nearest-node is O(N) — unacceptable when snapping thousands of query coordinates per second in a production routing service. The grid partitions space into ~1 km² cells, reducing average lookup to O(1).

**Why A\* over Dijkstra?** Dijkstra explores nodes in all directions equally. A\*'s Haversine heuristic focuses the search toward the destination. On the benchmark grid, A\* explores 97% fewer nodes with identical path quality — this gap widens dramatically on real-world city-scale graphs.

**Why Java 21 virtual threads in the API server?** Virtual threads (Project Loom) handle each HTTP connection on a lightweight thread with no blocking overhead — relevant for a routing API that may handle hundreds of concurrent requests.
