package com.natsume.fitgenerator;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

/** Bounded, entity-safe GPX parser supporting separate track segments and routes. */
public final class GpxParser {
    public enum CoordinateSource { WGS84, GCJ02, BD09 }

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_POINTS = 200_000;
    private static final int MAX_SEGMENTS = 1_000;

    private GpxParser() {}

    public static List<Route> parse(InputStream input, CoordinateSource source) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Handler handler = new Handler(source);
        InputSource safeInput = new InputSource(new LimitedInputStream(input, MAX_BYTES));
        safeInput.setEncoding("UTF-8");
        factory.newSAXParser().parse(safeInput, handler);
        return handler.finish();
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) { /* Parser variants differ on Android. */ }
    }

    private static final class Handler extends DefaultHandler {
        private final CoordinateSource source;
        private final List<Route> routes = new ArrayList<>();
        private List<Route.Point> current;
        private String trackName = "GPX 轨迹";
        private StringBuilder text;
        private boolean readingTrackName;
        private boolean inTrack;
        private boolean inRoute;
        private boolean inPoint;
        private int pointCount;
        private int segmentNumber;

        Handler(CoordinateSource source) { this.source = source == null ? CoordinateSource.WGS84 : source; }

        @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException {
            String tag = local == null || local.isEmpty() ? qName : local;
            switch (tag) {
                case "trk" -> { inTrack = true; trackName = "GPX 轨迹"; }
                case "rte" -> { inRoute = true; trackName = "GPX 路线"; beginSegment(); }
                case "trkseg" -> beginSegment();
                case "name" -> { if ((inTrack || inRoute) && !inPoint) { readingTrackName = true; text = new StringBuilder(); } }
                case "trkpt", "rtept" -> {
                    inPoint = true;
                    if (current == null) beginSegment();
                    if (++pointCount > MAX_POINTS) throw new SAXException("GPX 点数超过 20 万上限");
                    try {
                        double latitude = Double.parseDouble(attributes.getValue("lat"));
                        double longitude = Double.parseDouble(attributes.getValue("lon"));
                        double[] converted = convert(latitude, longitude, source);
                        current.add(new Route.Point(converted[0], converted[1]));
                    } catch (RuntimeException error) {
                        throw new SAXException("GPX 包含无效经纬度", error);
                    }
                }
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (readingTrackName && text.length() < 300) text.append(chars, start, Math.min(length, 300 - text.length()));
        }

        @Override public void endElement(String uri, String local, String qName) throws SAXException {
            String tag = local == null || local.isEmpty() ? qName : local;
            switch (tag) {
                case "name" -> {
                    if (readingTrackName) {
                        String value = text.toString().strip();
                        if (!value.isEmpty()) trackName = value;
                        readingTrackName = false;
                    }
                }
                case "trkpt", "rtept" -> inPoint = false;
                case "trkseg" -> endSegment();
                case "rte" -> { endSegment(); inRoute = false; }
                case "trk" -> inTrack = false;
            }
        }

        private void beginSegment() throws SAXException {
            if (current != null) endSegment();
            if (++segmentNumber > MAX_SEGMENTS) throw new SAXException("GPX 分段超过 1000 个上限");
            current = new ArrayList<>();
        }

        private void endSegment() throws SAXException {
            if (current == null) return;
            if (current.size() >= 2) {
                try {
                    String name = trackName + (segmentNumber > 1 ? " · 第 " + segmentNumber + " 段" : "");
                    routes.add(new Route(name, current));
                } catch (IllegalArgumentException error) {
                    throw new SAXException(error.getMessage(), error);
                }
            }
            current = null;
        }

        List<Route> finish() throws SAXException {
            endSegment();
            if (routes.isEmpty()) throw new SAXException("没有找到包含至少两个有效点的 GPX 轨迹");
            return routes;
        }
    }

    static double[] convert(double latitude, double longitude, CoordinateSource source) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) throw new IllegalArgumentException("坐标无效");
        if (source == CoordinateSource.BD09) {
            double x = longitude - 0.0065, y = latitude - 0.006;
            double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI * 3000 / 180);
            double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI * 3000 / 180);
            longitude = z * Math.cos(theta);
            latitude = z * Math.sin(theta);
            source = CoordinateSource.GCJ02;
        }
        if (source == CoordinateSource.GCJ02 && inChina(latitude, longitude)) {
            double guessLat = latitude, guessLon = longitude;
            for (int i = 0; i < 12; i++) {
                double[] mapped = wgsToGcj(guessLat, guessLon);
                double dLat = mapped[0] - latitude, dLon = mapped[1] - longitude;
                guessLat -= dLat; guessLon -= dLon;
                if (Math.max(Math.abs(dLat), Math.abs(dLon)) < 1e-10) break;
            }
            latitude = guessLat; longitude = guessLon;
        }
        return new double[]{latitude, longitude};
    }

    private static boolean inChina(double lat, double lon) { return lon >= 72.004 && lon <= 137.8347 && lat >= 0.8293 && lat <= 55.8271; }
    private static double[] wgsToGcj(double lat, double lon) {
        if (!inChina(lat, lon)) return new double[]{lat, lon};
        double a = 6378245.0, ee = 0.00669342162296594323;
        double dLat = transformLat(lon - 105, lat - 35), dLon = transformLon(lon - 105, lat - 35);
        double radLat = Math.toRadians(lat), magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = dLat * 180 / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);
        dLon = dLon * 180 / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }
    private static double transformLat(double x, double y) {
        double r = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        return r + (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
    }
    private static double transformLon(double x, double y) {
        double r = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        return r + (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining-- <= 0) throw new IOException("GPX 文件超过 20 MB 上限");
            return super.read();
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new IOException("GPX 文件超过 20 MB 上限");
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
