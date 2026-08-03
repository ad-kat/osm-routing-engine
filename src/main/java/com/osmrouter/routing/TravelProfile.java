package com.osmrouter.routing;

import java.util.Set;

/** Routing profile: controls allowed road types and per-road travel speeds. */
public enum TravelProfile {
    DRIVING, WALKING, CYCLING;

    private static final Set<String> MOTOR_ONLY =
        Set.of("motorway", "motorway_link", "trunk", "trunk_link");

    /** Travel speed in m/s for this profile on the given highway type. */
    public double speedMs(String highwayType) {
        return switch (this) {
            case WALKING -> 1.4;
            case CYCLING -> 4.2;
            case DRIVING -> switch (highwayType) {
                case "motorway", "motorway_link"   -> 33.3; // 120 km/h
                case "trunk", "trunk_link"         -> 27.8; // 100 km/h
                case "primary", "primary_link"     -> 16.7; //  60 km/h
                case "secondary", "secondary_link" -> 13.9; //  50 km/h
                case "tertiary", "tertiary_link"   -> 11.1; //  40 km/h
                case "living_street"               ->  2.8; //  10 km/h
                case "service"                     ->  5.6; //  20 km/h
                default                            ->  8.3; //  30 km/h
            };
        };
    }

    /** Maximum possible speed — used as admissible A* heuristic divisor. */
    public double maxSpeedMs() {
        return switch (this) { case WALKING -> 1.4; case CYCLING -> 4.2; case DRIVING -> 33.3; };
    }

    /** True if this road type is accessible by this profile. */
    public boolean allows(String highwayType) {
        if (this == WALKING) return !MOTOR_ONLY.contains(highwayType);
        if (this == CYCLING) return !Set.of("motorway", "motorway_link").contains(highwayType);
        return true;
    }

    public static TravelProfile from(String name) {
        return switch (name == null ? "" : name.toLowerCase()) {
            case "walking", "walk", "foot" -> WALKING;
            case "cycling", "cycle", "bike" -> CYCLING;
            default -> DRIVING;
        };
    }
}