package com.natsume.fitgenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Small JVM-only harness used by CI to verify the Android encoder with Python fit-tool. */
public final class EncoderSmoke {
    public static void main(String[] args) throws Exception {
        FitEncoder.Run run = new FitEncoder.Run(
                5000, 1800, 171, 30.58, 114.33,
                62.5, 84.39, 36.8,
                LocalDateTime.of(2026, 9, 14, 8, 0).atZone(ZoneId.of("Asia/Shanghai")));
        byte[] payload = FitEncoder.encode(run);
        if (payload.length < 10_000) throw new AssertionError("Unexpectedly small FIT output");
        Files.write(Path.of(args[0]), payload);
        FitEncoder.Run constant = new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, null, 0, 0);
        for (double[] sample : FitEncoder.motionSamples(constant)) {
            if (sample[1] != 5000.0 / 1800) throw new AssertionError("Constant speed mode changed");
        }
        for (int seconds : new int[]{1, 2, 30, 1800, 86400}) {
            FitEncoder.Run boundary = new FitEncoder.Run(seconds * 3.0, seconds, run.cadence(), run.latitude(), run.longitude(),
                    run.bearing(), run.straight(), run.radius(), run.start(), null, null, 0, 30);
            double[][] samples = FitEncoder.motionSamples(boundary);
            double average = 3.0;
            if (samples[0][0] != 0 || samples[seconds][0] != seconds * 3.0) throw new AssertionError("Speed profile changed total distance");
            for (int second = 1; second <= seconds; second++) {
                if (samples[second][1] < average * .7 - 1e-8 || samples[second][1] > average * 1.3 + 1e-8)
                    throw new AssertionError("Speed profile outside requested bounds");
                if (Math.abs(samples[second][0] - samples[second - 1][0] - samples[second][1]) > 1e-8)
                    throw new AssertionError("Speed and distance disagree");
            }
        }
        for (double variation : new double[]{-1, 30.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(), run.latitude(), run.longitude(),
                        run.bearing(), run.straight(), run.radius(), run.start(), null, null, 0, variation);
                throw new AssertionError("Invalid speed variation accepted");
            } catch (IllegalArgumentException expected) { }
        }

        String gpx = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Morning Run</name>"
                + "<trkseg><trkpt lat=\"30.5800\" lon=\"114.3300\"/><trkpt lat=\"30.5810\" lon=\"114.3300\"/>"
                + "<trkpt lat=\"30.5810\" lon=\"114.3310\"/></trkseg>"
                + "<trkseg><trkpt lat=\"30.5900\" lon=\"114.3400\"/><trkpt lat=\"30.5910\" lon=\"114.3410\"/></trkseg>"
                + "</trk></gpx>";
        List<Route> routes = GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)), GpxParser.CoordinateSource.WGS84);
        if (routes.size() != 2 || !routes.get(0).name().equals("Morning Run") || !routes.get(1).name().contains("第 2 段")) {
            throw new AssertionError("GPX segment parse failed");
        }
        Route route = routes.get(0);
        FitEncoder.Run routeRun = new FitEncoder.Run(route.length(), 120, 170,
                route.points().get(0).latitude(), route.points().get(0).longitude(), 0, 84.39, 36.8,
                LocalDateTime.of(2026, 9, 14, 9, 0).atZone(ZoneId.of("Asia/Shanghai")), route);
        Files.write(Path.of(args[1]), FitEncoder.encode(routeRun));
        FitEncoder.Run natural = new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, 0.8, 3);
        if (args.length >= 5) Files.write(Path.of(args[4]), FitEncoder.encode(natural));
        double perimeter = 2 * run.straight() + 2 * Math.PI * run.radius();
        for (double s : new double[]{0, run.straight(), run.straight() + Math.PI * run.radius(),
                2 * run.straight() + Math.PI * run.radius(), perimeter, 2 * perimeter}) {
            double[] a = FitEncoder.variedTrackXY(s - .001, run.straight(), run.radius(), run.bearing(), 3, run.start().toEpochSecond());
            double[] b = FitEncoder.variedTrackXY(s + .001, run.straight(), run.radius(), run.bearing(), 3, run.start().toEpochSecond());
            if (Math.hypot(a[0] - b[0], a[1] - b[1]) > .0025) throw new AssertionError("Position jump at track seam");
        }
        FitEncoder.Run variedRoute = new FitEncoder.Run(routeRun.distanceMeters(), routeRun.durationSeconds(), routeRun.cadence(),
                routeRun.latitude(), routeRun.longitude(), 0, 84.39, 36.8, routeRun.start(), route, null, 8);
        for (double s : new double[]{0, route.length() / 2, route.length()}) {
            double[] a = FitEncoder.trackPosition(routeRun, s), b = FitEncoder.trackPosition(variedRoute, s);
            if (a[0] != b[0] || a[1] != b[1]) throw new AssertionError("Variation modified GPX geometry");
        }
        for (double variation : new double[]{-1, 8.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                        run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, null, variation);
                throw new AssertionError("Invalid variation accepted");
            } catch (IllegalArgumentException expected) { }
        }
        if (args.length >= 4) {
            FitEncoder.Run custom = new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                    run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, 0.8);
            Files.write(Path.of(args[2]), FitEncoder.encode(custom));
            FitEncoder.Run customRoute = new FitEncoder.Run(routeRun.distanceMeters(), routeRun.durationSeconds(), routeRun.cadence(),
                    routeRun.latitude(), routeRun.longitude(), routeRun.bearing(), routeRun.straight(), routeRun.radius(), routeRun.start(), route, 0.8);
            Files.write(Path.of(args[3]), FitEncoder.encode(customRoute));
        }
        for (double length : new double[]{0.01, 6.5}) {
            FitEncoder.Run boundary = new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                    run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, length);
            FitEncoder.encode(boundary);
        }
        for (double length : new double[]{0, -0.8, 0.009, 6.501, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new FitEncoder.Run(run.distanceMeters(), run.durationSeconds(), run.cadence(),
                        run.latitude(), run.longitude(), run.bearing(), run.straight(), run.radius(), run.start(), null, length);
                throw new AssertionError("Invalid step length accepted: " + length);
            } catch (IllegalArgumentException expected) {
                // Invalid overrides must be rejected before encoding.
            }
        }
    }
}
