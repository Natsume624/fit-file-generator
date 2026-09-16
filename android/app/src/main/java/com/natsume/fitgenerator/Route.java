package com.natsume.fitgenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable GPX route with cumulative distance and distance-based interpolation. */
public final class Route {
    public record Point(double latitude, double longitude) {}

    private final String name;
    private final List<Point> points;
    private final double[] cumulative;
    private final double length;

    public Route(String name, List<Point> source) {
        if (source == null || source.size() < 2) throw new IllegalArgumentException("GPX 路线至少需要两个有效点");
        List<Point> clean = new ArrayList<>(source.size());
        for (Point point : source) {
            if (point == null || !Double.isFinite(point.latitude) || !Double.isFinite(point.longitude)
                    || point.latitude < -85 || point.latitude > 85 || point.longitude < -180 || point.longitude > 180) {
                throw new IllegalArgumentException("GPX 包含无效坐标");
            }
            if (clean.isEmpty() || distance(clean.get(clean.size() - 1), point) > 0.01) clean.add(point);
        }
        if (clean.size() < 2) throw new IllegalArgumentException("GPX 路线没有足够的不同坐标点");
        this.name = name == null || name.isBlank() ? "GPX 路线" : name.strip();
        this.points = Collections.unmodifiableList(clean);
        this.cumulative = new double[clean.size()];
        for (int i = 1; i < clean.size(); i++) cumulative[i] = cumulative[i - 1] + distance(clean.get(i - 1), clean.get(i));
        this.length = cumulative[cumulative.length - 1];
        if (length < 1) throw new IllegalArgumentException("GPX 路线长度不足 1 米");
    }

    public String name() { return name; }
    public List<Point> points() { return points; }
    public double length() { return length; }

    public Point position(double distance) {
        if (distance <= 0) return points.get(0);
        if (distance >= length) return points.get(points.size() - 1);
        int low = 0, high = cumulative.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] <= distance) low = middle; else high = middle;
        }
        double segment = cumulative[high] - cumulative[low];
        double fraction = segment == 0 ? 0 : (distance - cumulative[low]) / segment;
        Point a = points.get(low), b = points.get(high);
        double deltaLongitude = (b.longitude - a.longitude + 540) % 360 - 180;
        return new Point(a.latitude + (b.latitude - a.latitude) * fraction,
                normalizeLongitude(a.longitude + deltaLongitude * fraction));
    }

    public List<Point> previewPoints(int limit) {
        int count = Math.max(2, Math.min(limit, points.size()));
        if (count == points.size()) return points;
        List<Point> preview = new ArrayList<>(count);
        for (int i = 0; i < count; i++) preview.add(position(length * i / (count - 1.0)));
        return preview;
    }

    private static double distance(Point a, Point b) {
        double lat1 = Math.toRadians(a.latitude), lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians((b.longitude - a.longitude + 540) % 360 - 180);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_008.8 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
    }

    private static double normalizeLongitude(double value) { return (value + 540) % 360 - 180; }
}
