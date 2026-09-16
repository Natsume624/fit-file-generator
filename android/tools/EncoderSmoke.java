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
    }
}
