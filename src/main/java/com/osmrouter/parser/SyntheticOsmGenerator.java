package com.osmrouter.parser;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Random;

/**
 * Generates a synthetic OSM XML file representing a realistic grid-based
 * road network (modelled loosely on Manhattan-style blocks) for testing
 * and demonstration without requiring a real OSM data download.
 *
 * Also embeds a few "diagonal" streets to make routing non-trivial.
 */
public class SyntheticOsmGenerator {

    public static void generate(String outputPath, int gridRows, int gridCols,
                                double baseLat, double baseLon, double cellDegrees) throws Exception {

        System.out.printf("Generating synthetic OSM: %dx%d grid around (%.4f, %.4f)%n",
                gridRows, gridCols, baseLat, baseLon);

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<osm version=\"0.6\">");

            // --- Nodes ---
            long nodeId = 1;
            long[][] nodeIds = new long[gridRows][gridCols];
            for (int r = 0; r < gridRows; r++) {
                for (int c = 0; c < gridCols; c++) {
                    nodeIds[r][c] = nodeId;
                    double lat = baseLat + r * cellDegrees;
                    double lon = baseLon + c * cellDegrees;
                    pw.printf("  <node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", nodeId++, lat, lon);
                }
            }

            // A few off-grid nodes for diagonal streets
            long diag1 = nodeId++;
            long diag2 = nodeId++;
            long diag3 = nodeId++;
            pw.printf("  <node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", diag1,
                    baseLat + gridRows * cellDegrees * 0.33, baseLon + gridCols * cellDegrees * 0.25);
            pw.printf("  <node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", diag2,
                    baseLat + gridRows * cellDegrees * 0.5,  baseLon + gridCols * cellDegrees * 0.5);
            pw.printf("  <node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", diag3,
                    baseLat + gridRows * cellDegrees * 0.67, baseLon + gridCols * cellDegrees * 0.75);

            // --- Ways: horizontal streets ---
            long wayId = 1;
            String[] streetNames = {"Main St", "Oak Ave", "Elm St", "Maple Rd", "Cedar Blvd",
                    "Park Ave", "Lake Rd", "River St", "Hill Rd", "Valley Ave"};

            for (int r = 0; r < gridRows; r++) {
                pw.printf("  <way id=\"%d\">%n", wayId++);
                for (int c = 0; c < gridCols; c++) {
                    pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[r][c]);
                }
                String name = streetNames[r % streetNames.length];
                String type = (r == 0 || r == gridRows - 1) ? "primary" : "residential";
                pw.printf("    <tag k=\"highway\" v=\"%s\"/>%n", type);
                pw.printf("    <tag k=\"name\" v=\"%s\"/>%n", name);
                pw.println("  </way>");
            }

            // --- Ways: vertical avenues ---
            String[] aveNames = {"1st Ave", "2nd Ave", "3rd Ave", "4th Ave", "5th Ave",
                    "Broadway", "Central Ave", "West End", "East Blvd", "North Rd"};
            for (int c = 0; c < gridCols; c++) {
                pw.printf("  <way id=\"%d\">%n", wayId++);
                for (int r = 0; r < gridRows; r++) {
                    pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[r][c]);
                }
                String name = aveNames[c % aveNames.length];
                String type = (c == 0 || c == gridCols - 1) ? "secondary" : "residential";
                pw.printf("    <tag k=\"highway\" v=\"%s\"/>%n", type);
                pw.printf("    <tag k=\"name\" v=\"%s\"/>%n", name);
                pw.println("  </way>");
            }

            // --- Diagonal shortcut ways ---
            pw.printf("  <way id=\"%d\">%n", wayId++);
            pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[0][0]);
            pw.printf("    <nd ref=\"%d\"/>%n", diag1);
            pw.printf("    <nd ref=\"%d\"/>%n", diag2);
            pw.printf("    <tag k=\"highway\" v=\"tertiary\"/>%n");
            pw.printf("    <tag k=\"name\" v=\"Diagonal Rd\"/>%n");
            pw.println("  </way>");

            pw.printf("  <way id=\"%d\">%n", wayId++);
            pw.printf("    <nd ref=\"%d\"/>%n", diag2);
            pw.printf("    <nd ref=\"%d\"/>%n", diag3);
            pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[gridRows-1][gridCols-1]);
            pw.printf("    <tag k=\"highway\" v=\"tertiary\"/>%n");
            pw.printf("    <tag k=\"name\" v=\"Diagonal Rd\"/>%n");
            pw.println("  </way>");

            // One oneway street
            pw.printf("  <way id=\"%d\">%n", wayId++);
            pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[2][0]);
            pw.printf("    <nd ref=\"%d\"/>%n", nodeIds[2][gridCols/2]);
            pw.printf("    <tag k=\"highway\" v=\"primary\"/>%n");
            pw.printf("    <tag k=\"name\" v=\"One Way Blvd\"/>%n");
            pw.printf("    <tag k=\"oneway\" v=\"yes\"/>%n");
            pw.println("  </way>");

            pw.println("</osm>");
        }

        System.out.println("Synthetic OSM written to: " + outputPath);
    }
}
