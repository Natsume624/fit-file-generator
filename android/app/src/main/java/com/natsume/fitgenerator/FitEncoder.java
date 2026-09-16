package com.natsume.fitgenerator;

import java.io.ByteArrayOutputStream;
import java.time.ZonedDateTime;

/** Dependency-free FIT activity encoder shared by the Android UI and desktop smoke tests. */
public final class FitEncoder {
    private static final long FIT_EPOCH_OFFSET = 631065600L;
    private static final int ENUM = 0x00;
    private static final int UINT8 = 0x02;
    private static final int UINT16 = 0x84;
    private static final int SINT32 = 0x85;
    private static final int UINT32 = 0x86;

    private FitEncoder() {}

    public record Run(
            double distanceMeters,
            int durationSeconds,
            int cadence,
            double latitude,
            double longitude,
            double bearing,
            double straight,
            double radius,
            ZonedDateTime start,
            Route route) {
        public Run(double distanceMeters, int durationSeconds, int cadence, double latitude, double longitude,
                   double bearing, double straight, double radius, ZonedDateTime start) {
            this(distanceMeters, durationSeconds, cadence, latitude, longitude, bearing, straight, radius, start, null);
        }

        public Run {
            finite(distanceMeters, "距离");
            finite(latitude, "纬度");
            finite(longitude, "经度");
            finite(bearing, "方向");
            finite(straight, "直道长度");
            finite(radius, "弯道半径");
            if (distanceMeters < 1 || distanceMeters > 1_000_000) throw new IllegalArgumentException("距离应为 0.001～1000 km");
            if (durationSeconds < 1 || durationSeconds > 86_400) throw new IllegalArgumentException("时长应为 1 秒～24 小时");
            if (cadence < 30 || cadence > 300) throw new IllegalArgumentException("平均步频应为 30～300 步/分钟");
            if (latitude < -85 || latitude > 85) throw new IllegalArgumentException("纬度应为 -85～85");
            if (longitude < -180 || longitude > 180) throw new IllegalArgumentException("经度应为 -180～180");
            if (bearing < 0 || bearing > 360) throw new IllegalArgumentException("方向应为 0～360°");
            if (straight < 1 || straight > 1000) throw new IllegalArgumentException("直道长度应为 1～1000 米");
            if (radius < 5 || radius > 300) throw new IllegalArgumentException("弯道半径应为 5～300 米");
            if (start == null) throw new IllegalArgumentException("开始时间不能为空");
            if (distanceMeters / durationSeconds >= 65.535) throw new IllegalArgumentException("速度超出 FIT 文件允许范围");
        }
    }

    private record Field(int number, int size, int type) {}

    public static byte[] encode(Run run) {
        ByteArrayOutputStream data = new ByteArrayOutputStream(Math.max(4096, run.durationSeconds * 28));
        long start = run.start.toEpochSecond() - FIT_EPOCH_OFFSET;
        long end = start + run.durationSeconds;
        double speed = run.distanceMeters / run.durationSeconds;
        int cadenceWhole = run.cadence / 2;
        int cadenceFraction = (run.cadence % 2) * 64;
        int heartRate = speed > 10.0 / 3 ? 165 : speed > 25.0 / 9 ? 150 : speed > 50.0 / 21 ? 135 : 125;
        int power = (int) Math.round(speed * 70 * 1.05);
        int stepLength = (int) Math.round(speed * 60.0 / run.cadence * 10_000);

        definition(data, 0,
                new Field(0, 1, ENUM), new Field(1, 2, UINT16),
                new Field(2, 2, UINT16), new Field(4, 4, UINT32));
        header(data);
        u8(data, 4);                 // activity
        u16(data, 255);              // development manufacturer
        u16(data, 1);
        u32(data, start);

        eventDefinition(data);
        event(data, start, 0);       // timer start

        definition(data, 20,
                new Field(253, 4, UINT32), new Field(0, 4, SINT32),
                new Field(1, 4, SINT32), new Field(2, 2, UINT16),
                new Field(3, 1, UINT8), new Field(4, 1, UINT8),
                new Field(5, 4, UINT32), new Field(6, 2, UINT16),
                new Field(7, 2, UINT16), new Field(53, 1, UINT8),
                new Field(85, 2, UINT16));
        for (int second = 0; second <= run.durationSeconds; second++) {
            double distance = run.distanceMeters * second / run.durationSeconds;
            double[] position = trackPosition(run, distance);
            header(data);
            u32(data, start + second);
            s32(data, semicircles(position[0]));
            s32(data, semicircles(position[1]));
            u16(data, 2600);         // 20 m, scale 5 and offset 500
            u8(data, heartRate);
            u8(data, cadenceWhole);
            u32(data, Math.round(distance * 100));
            u16(data, Math.round(speed * 1000));
            u16(data, power);
            u8(data, cadenceFraction);
            u16(data, stepLength);
        }

        eventDefinition(data);
        event(data, end, 4);         // stop_all

        double[] first = trackPosition(run, 0);
        double[] last = trackPosition(run, run.distanceMeters);
        int elapsed = run.durationSeconds * 1000;
        long totalDistance = Math.round(run.distanceMeters * 100);
        int encodedSpeed = (int) Math.round(speed * 1000);
        int strides = (int) Math.round((run.cadence / 2.0) * run.durationSeconds / 60.0);

        definition(data, 19,
                new Field(253, 4, UINT32), new Field(2, 4, UINT32),
                new Field(3, 4, SINT32), new Field(4, 4, SINT32),
                new Field(5, 4, SINT32), new Field(6, 4, SINT32),
                new Field(7, 4, UINT32), new Field(8, 4, UINT32),
                new Field(9, 4, UINT32), new Field(13, 2, UINT16),
                new Field(14, 2, UINT16), new Field(17, 1, UINT8),
                new Field(18, 1, UINT8), new Field(19, 2, UINT16),
                new Field(25, 1, ENUM), new Field(39, 1, ENUM),
                new Field(79, 2, UINT16), new Field(80, 1, UINT8),
                new Field(81, 1, UINT8));
        header(data);
        u32(data, end); u32(data, start);
        s32(data, semicircles(first[0])); s32(data, semicircles(first[1]));
        s32(data, semicircles(last[0])); s32(data, semicircles(last[1]));
        u32(data, elapsed); u32(data, elapsed); u32(data, totalDistance);
        u16(data, encodedSpeed); u16(data, encodedSpeed);
        u8(data, cadenceWhole); u8(data, cadenceWhole); u16(data, strides);
        u8(data, 1); u8(data, 0);    // running / generic
        u16(data, stepLength); u8(data, cadenceFraction); u8(data, cadenceFraction);

        definition(data, 18,
                new Field(253, 4, UINT32), new Field(2, 4, UINT32),
                new Field(3, 4, SINT32), new Field(4, 4, SINT32),
                new Field(5, 1, ENUM), new Field(6, 1, ENUM),
                new Field(7, 4, UINT32), new Field(8, 4, UINT32),
                new Field(9, 4, UINT32), new Field(14, 2, UINT16),
                new Field(15, 2, UINT16), new Field(18, 1, UINT8),
                new Field(19, 1, UINT8), new Field(20, 2, UINT16),
                new Field(26, 2, UINT16), new Field(91, 2, UINT16),
                new Field(92, 1, UINT8), new Field(93, 1, UINT8));
        header(data);
        u32(data, end); u32(data, start);
        s32(data, semicircles(first[0])); s32(data, semicircles(first[1]));
        u8(data, 1); u8(data, 0);
        u32(data, elapsed); u32(data, elapsed); u32(data, totalDistance);
        u16(data, encodedSpeed); u16(data, encodedSpeed);
        u8(data, cadenceWhole); u8(data, cadenceWhole); u16(data, strides);
        u16(data, 1); u16(data, stepLength); u8(data, cadenceFraction); u8(data, cadenceFraction);

        definition(data, 34,
                new Field(253, 4, UINT32), new Field(0, 4, UINT32), new Field(1, 2, UINT16));
        header(data);
        u32(data, end); u32(data, elapsed); u16(data, 1);

        byte[] payload = data.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream(payload.length + 14);
        u8(file, 12);
        u8(file, 0x20);
        u16(file, 2321);
        u32(file, payload.length);
        byte[] signature = new byte[]{'.', 'F', 'I', 'T'};
        file.write(signature, 0, signature.length);
        file.write(payload, 0, payload.length);
        byte[] withoutCrc = file.toByteArray();
        u16(file, crc(withoutCrc));
        return file.toByteArray();
    }

    private static void eventDefinition(ByteArrayOutputStream out) {
        definition(out, 21, new Field(253, 4, UINT32), new Field(0, 1, ENUM), new Field(1, 1, ENUM));
    }

    private static void event(ByteArrayOutputStream out, long timestamp, int type) {
        header(out); u32(out, timestamp); u8(out, 0); u8(out, type);
    }

    private static void definition(ByteArrayOutputStream out, int globalMessage, Field... fields) {
        u8(out, 0x40); u8(out, 0); u8(out, 0); u16(out, globalMessage); u8(out, fields.length);
        for (Field field : fields) { u8(out, field.number); u8(out, field.size); u8(out, field.type); }
    }

    private static void header(ByteArrayOutputStream out) { u8(out, 0); }

    public static double[] trackXY(double distance, double straight, double radius, double bearing) {
        double arc = Math.PI * radius;
        double s = distance % (2 * straight + 2 * arc);
        double x, y;
        if (s < straight) {
            x = -radius; y = -straight / 2 + s;
        } else if (s < straight + arc) {
            double a = Math.PI - (s - straight) / radius;
            x = radius * Math.cos(a); y = straight / 2 + radius * Math.sin(a);
        } else if (s < 2 * straight + arc) {
            x = radius; y = straight / 2 - (s - straight - arc);
        } else {
            double a = (s - 2 * straight - arc) / radius;
            x = radius * Math.cos(a); y = -straight / 2 - radius * Math.sin(a);
        }
        double angle = Math.toRadians(bearing);
        return new double[]{x * Math.cos(angle) + y * Math.sin(angle), -x * Math.sin(angle) + y * Math.cos(angle)};
    }

    static double[] trackPosition(Run run, double distance) {
        if (run.route != null) {
            Route.Point point = run.route.position(distance);
            return new double[]{point.latitude(), point.longitude()};
        }
        double[] xy = trackXY(distance, run.straight, run.radius, run.bearing);
        return vincentyDirect(run.latitude, run.longitude, Math.toDegrees(Math.atan2(xy[0], xy[1])), Math.hypot(xy[0], xy[1]));
    }

    /** WGS84 direct geodesic; converges quickly for the sub-kilometre offsets used by tracks. */
    private static double[] vincentyDirect(double lat1, double lon1, double bearing, double distance) {
        final double a = 6378137.0, f = 1 / 298.257223563, b = (1 - f) * a;
        double alpha1 = Math.toRadians(bearing);
        double sinAlpha1 = Math.sin(alpha1), cosAlpha1 = Math.cos(alpha1);
        double tanU1 = (1 - f) * Math.tan(Math.toRadians(lat1));
        double cosU1 = 1 / Math.sqrt(1 + tanU1 * tanU1), sinU1 = tanU1 * cosU1;
        double sigma1 = Math.atan2(tanU1, cosAlpha1);
        double sinAlpha = cosU1 * sinAlpha1;
        double cosSqAlpha = 1 - sinAlpha * sinAlpha;
        double uSq = cosSqAlpha * (a * a - b * b) / (b * b);
        double A = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double B = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double sigma = distance / (b * A), previous;
        double sinSigma, cosSigma, cos2SigmaM, deltaSigma;
        do {
            cos2SigmaM = Math.cos(2 * sigma1 + sigma);
            sinSigma = Math.sin(sigma); cosSigma = Math.cos(sigma);
            deltaSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                    - B / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
            previous = sigma;
            sigma = distance / (b * A) + deltaSigma;
        } while (Math.abs(sigma - previous) > 1e-12);
        sinSigma = Math.sin(sigma); cosSigma = Math.cos(sigma); cos2SigmaM = Math.cos(2 * sigma1 + sigma);
        double tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1;
        double lat2 = Math.atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                (1 - f) * Math.sqrt(sinAlpha * sinAlpha + tmp * tmp));
        double lambda = Math.atan2(sinSigma * sinAlpha1, cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1);
        double C = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
        double L = lambda - (1 - C) * f * sinAlpha * (sigma + C * sinSigma * (cos2SigmaM
                + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        return new double[]{Math.toDegrees(lat2), normalizeLongitude(lon1 + Math.toDegrees(L))};
    }

    private static double normalizeLongitude(double value) { return (value + 540) % 360 - 180; }
    private static int semicircles(double degrees) { return (int) Math.round(degrees * (2147483648.0 / 180.0)); }
    private static void finite(double value, String label) { if (!Double.isFinite(value)) throw new IllegalArgumentException(label + "不是有效数字"); }

    private static int crc(byte[] bytes) {
        int crc = 0;
        for (byte value : bytes) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xffff;
    }

    private static void u8(ByteArrayOutputStream out, long value) { out.write((int) value & 0xff); }
    private static void u16(ByteArrayOutputStream out, long value) { u8(out, value); u8(out, value >>> 8); }
    private static void u32(ByteArrayOutputStream out, long value) { u16(out, value); u16(out, value >>> 16); }
    private static void s32(ByteArrayOutputStream out, int value) { u32(out, value & 0xffffffffL); }
}
