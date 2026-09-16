package com.natsume.fitgenerator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

public final class TrackPreviewView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private double straight = 84.39;
    private double radius = 36.8;
    private double bearing = 0;
    private Route route;

    public TrackPreviewView(Context context) { this(context, null); }

    public TrackPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        grid.setColor(0xffdbe3ef);
        grid.setStrokeWidth(dp(1));
        halo.setColor(0xffbfd1fa);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(dp(12));
        halo.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0xff2563eb);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(dp(2.5f));
        track.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xff2563eb);
    }

    public void setTrack(double straight, double radius, double bearing) {
        if (straight > 0 && radius > 0) {
            this.straight = straight;
            this.radius = radius;
            this.bearing = bearing;
            this.route = null;
            invalidate();
        }
    }

    public void setRoute(Route route) {
        this.route = route;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawLine(cx, dp(14), cx, getHeight() - dp(14), grid);
        canvas.drawLine(dp(14), cy, getWidth() - dp(14), cy, grid);
        if (route != null) { drawRoute(canvas, cx, cy); return; }
        double perimeter = 2 * straight + 2 * Math.PI * radius;
        double extent = straight / 2 + radius;
        float scale = (float) (Math.min(getWidth() - dp(48), getHeight() - dp(42)) / (2 * extent));
        path.reset();
        float firstX = 0, firstY = 0;
        for (int i = 0; i <= 240; i++) {
            double[] point = FitEncoder.trackXY(perimeter * i / 240.0, straight, radius, bearing);
            float x = cx + (float) point[0] * scale;
            float y = cy - (float) point[1] * scale;
            if (i == 0) { path.moveTo(x, y); firstX = x; firstY = y; }
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, halo);
        canvas.drawPath(path, track);
        canvas.drawCircle(firstX, firstY, dp(5), dot);
    }

    private void drawRoute(Canvas canvas, float cx, float cy) {
        List<Route.Point> points = route.previewPoints(500);
        double latitude = points.stream().mapToDouble(Route.Point::latitude).average().orElse(0);
        double longitude = points.get(0).longitude();
        double cosine = Math.cos(Math.toRadians(latitude));
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double[] xs = new double[points.size()], ys = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Route.Point point = points.get(i);
            xs[i] = ((point.longitude() - longitude + 540) % 360 - 180) * cosine;
            ys[i] = point.latitude() - latitude;
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        double width = Math.max(maxX - minX, 1e-9), height = Math.max(maxY - minY, 1e-9);
        double scale = Math.min((getWidth() - dp(36)) / width, (getHeight() - dp(36)) / height);
        path.reset();
        float firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            float x = cx + (float) ((xs[i] - (minX + maxX) / 2) * scale);
            float y = cy - (float) ((ys[i] - (minY + maxY) / 2) * scale);
            if (i == 0) { path.moveTo(x, y); firstX = x; firstY = y; } else path.lineTo(x, y);
            lastX = x; lastY = y;
        }
        canvas.drawPath(path, track);
        dot.setColor(0xff22a06b); canvas.drawCircle(firstX, firstY, dp(5), dot);
        dot.setColor(0xffe36b4e); canvas.drawCircle(lastX, lastY, dp(5), dot);
        dot.setColor(0xff2563eb);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
