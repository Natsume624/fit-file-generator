package com.natsume.fitgenerator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_FIT = 1001;
    private static final int LOCATION_PERMISSION = 1002;
    private static final int IMPORT_GPX = 1003;
    private static final int MAP_PICKER = 1004;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TrackPreviewView preview;
    private TextView metrics;
    private TextView status;
    private Button generate;
    private Button locateButton;
    private Button clearGpxButton;
    private Spinner segmentSpinner;
    private TextView routeNote;
    private final List<Route> routes = new ArrayList<>();
    private Route currentRoute;
    private FitEncoder.Run pendingRun;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 38, 61));
        setContentView(buildContent());
        updatePreview();
    }

    private View buildContent() {
        int navy = Color.rgb(20, 40, 61);
        int blue = Color.rgb(37, 99, 235);
        int muted = Color.rgb(93, 113, 138);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(244, 247, 251));
        page.setFitsSystemWindows(true);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(21));
        hero.setBackgroundColor(navy);
        TextView eyebrow = text("NATSUME / ANDROID", 11, 0xffaac2ff, Typeface.BOLD);
        eyebrow.setLetterSpacing(.16f);
        hero.addView(eyebrow);
        TextView title = text("把每一次奔跑，\n装进标准 FIT 文件。", 25, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(6));
        hero.addView(title);
        hero.addView(text("本机生成 · 无需账号 · 直接保存到手机", 13, 0xffc6d2df, Typeface.NORMAL));
        page.addView(hero);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(112));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout overview = card();
        TextView previewTitle = text("跑道预览", 17, navy, Typeface.BOLD);
        overview.addView(previewTitle);
        overview.addView(text("北向朝上 · 蓝点为起点", 12, muted, Typeface.NORMAL));
        preview = new TrackPreviewView(this);
        preview.setBackgroundColor(0xfff4f7fc);
        overview.addView(preview, new LinearLayout.LayoutParams(-1, dp(210)));
        metrics = text("", 14, navy, Typeface.NORMAL);
        metrics.setLineSpacing(dp(3), 1f);
        metrics.setPadding(0, dp(12), 0, 0);
        overview.addView(metrics);
        content.addView(overview);

        LinearLayout movement = card();
        sectionTitle(movement, "01", "运动数据");
        addField(movement, "距离", "distance", "km", "5.00", true);
        addField(movement, "时长", "duration", "分钟", "30", true);
        addField(movement, "平均步频", "cadence", "步/分钟", "170", true);
        content.addView(movement);

        LinearLayout track = card();
        sectionTitle(track, "02", "跑道与坐标");
        addField(track, "中心纬度（WGS84）", "latitude", "°", "", true);
        addField(track, "中心经度（WGS84）", "longitude", "°", "", true);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        locateButton = secondaryButton("使用当前位置");
        locateButton.setOnClickListener(v -> requestCurrentLocation());
        actions.addView(locateButton, weighted());
        Button map = secondaryButton("地图校准 / 查看");
        map.setOnClickListener(v -> openMap());
        LinearLayout.LayoutParams mapParams = weighted();
        mapParams.setMarginStart(dp(8));
        actions.addView(map, mapParams);
        track.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout gpxActions = new LinearLayout(this);
        gpxActions.setOrientation(LinearLayout.HORIZONTAL);
        Button importGpx = secondaryButton("导入 GPX");
        importGpx.setOnClickListener(v -> chooseGpx());
        gpxActions.addView(importGpx, weighted());
        clearGpxButton = secondaryButton("切回标准跑道");
        clearGpxButton.setEnabled(false);
        clearGpxButton.setOnClickListener(v -> clearGpx());
        LinearLayout.LayoutParams clearParams = weighted();
        clearParams.setMarginStart(dp(8));
        gpxActions.addView(clearGpxButton, clearParams);
        LinearLayout.LayoutParams gpxParams = new LinearLayout.LayoutParams(-1, -2);
        gpxParams.setMargins(0, dp(8), 0, 0);
        track.addView(gpxActions, gpxParams);
        segmentSpinner = new Spinner(this);
        segmentSpinner.setVisibility(View.GONE);
        segmentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < routes.size()) selectRoute(routes.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        track.addView(segmentSpinner, new LinearLayout.LayoutParams(-1, dp(52)));
        routeNote = text("", 12, 0xff526578, Typeface.NORMAL);
        routeNote.setVisibility(View.GONE);
        routeNote.setPadding(0, dp(8), 0, 0);
        track.addView(routeNote);
        addField(track, "跑道方向", "bearing", "°", "0", true);
        addField(track, "单段直道长度", "straight", "米", "84.39", true);
        addField(track, "弯道半径", "radius", "米", "36.8", true);
        TextView note = text("请填写跑道中心而非入口。中国大陆地图应用显示的坐标可能经过偏移，请使用 WGS84 坐标。", 12, muted, Typeface.NORMAL);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, dp(9), 0, 0);
        track.addView(note);
        content.addView(track);

        LinearLayout timing = card();
        sectionTitle(timing, "03", "开始时间");
        LocalDateTime now = LocalDateTime.now();
        addPicker(timing, "日期", "date", now.toLocalDate().format(DATE), true);
        addPicker(timing, "时间", "time", now.toLocalTime().format(TIME), false);
        content.addView(timing);

        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(16));
        bottom.setBackgroundColor(Color.WHITE);
        status = text("填写坐标后即可生成", 12, muted, Typeface.NORMAL);
        bottom.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        generate = new Button(this);
        generate.setText(R.string.save_fit);
        generate.setTextColor(Color.WHITE);
        generate.setTextSize(15);
        generate.setAllCaps(false);
        generate.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        generate.setBackground(roundRect(blue, 12));
        generate.setPadding(dp(18), dp(12), dp(18), dp(12));
        generate.setOnClickListener(v -> chooseDestination());
        bottom.addView(generate);

        FrameLayout root = new FrameLayout(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bottomParams);
        return root;
    }

    private void sectionTitle(LinearLayout parent, String number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(number, 11, Color.WHITE, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundRect(0xff2563eb, 20));
        row.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(26)));
        TextView title = text(label, 17, 0xff14283d, Typeface.BOLD);
        title.setPadding(dp(10), 0, 0, 0);
        row.addView(title);
        parent.addView(row);
    }

    private void addField(LinearLayout parent, String label, String key, String suffix, String value, boolean decimal) {
        TextView caption = text(label + "  /  " + suffix, 12, 0xff526578, Typeface.NORMAL);
        caption.setPadding(0, dp(14), 0, dp(5));
        parent.addView(caption);
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setBackground(roundStroke(0xfffbfcfe, 0xffcdd8e5, 9));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setSelectAllOnFocus(true);
        input.setOnFocusChangeListener((v, focused) -> { if (!focused) updatePreview(); });
        fields.put(key, input);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private void addPicker(LinearLayout parent, String label, String key, String value, boolean date) {
        TextView caption = text(label, 12, 0xff526578, Typeface.NORMAL);
        caption.setPadding(0, dp(14), 0, dp(5));
        parent.addView(caption);
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(16);
        input.setFocusable(false);
        input.setClickable(true);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setBackground(roundStroke(0xfffbfcfe, 0xffcdd8e5, 9));
        input.setOnClickListener(v -> { if (date) showDate(input); else showTime(input); });
        fields.put(key, input);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private void showDate(EditText field) {
        LocalDate current = LocalDate.parse(field.getText(), DATE);
        new DatePickerDialog(this, (view, year, month, day) -> field.setText(LocalDate.of(year, month + 1, day).format(DATE)),
                current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()).show();
    }

    private void showTime(EditText field) {
        LocalTime current = LocalTime.parse(field.getText(), TIME);
        new TimePickerDialog(this, (view, hour, minute) -> field.setText(LocalTime.of(hour, minute).format(TIME)),
                current.getHour(), current.getMinute(), true).show();
    }

    private void updatePreview() {
        try {
            if (currentRoute != null) {
                preview.setRoute(currentRoute);
                double duration = number("duration");
                double cadence = number("cadence");
                long pace = Math.round(duration * 60 / (currentRoute.length() / 1000));
                metrics.setText(getString(R.string.route_metrics_summary,
                        currentRoute.length() / 1000, pace / 60, pace % 60, Math.round(cadence)));
                return;
            }
            double distance = number("distance");
            double duration = number("duration");
            double cadence = number("cadence");
            double straight = number("straight");
            double radius = number("radius");
            double bearing = number("bearing");
            preview.setTrack(straight, radius, bearing);
            double perimeter = 2 * straight + 2 * Math.PI * radius;
            long pace = Math.round(duration * 60 / distance);
            metrics.setText(getString(R.string.metrics_summary,
                    pace / 60, pace % 60, Math.round(cadence), perimeter, distance * 1000 / perimeter));
        } catch (RuntimeException ignored) {
            metrics.setText("请检查输入数据");
        }
    }

    private FitEncoder.Run readRun() {
        double distance = currentRoute == null ? number("distance") * 1000 : currentRoute.length();
        double durationMinutes = number("duration");
        int seconds = (int) Math.round(durationMinutes * 60);
        if (Math.abs(seconds - durationMinutes * 60) > 1e-6) throw new IllegalArgumentException("时长必须精确到整秒");
        double cadence = number("cadence");
        if (cadence != Math.rint(cadence)) throw new IllegalArgumentException("平均步频请输入整数");
        LocalDateTime start = LocalDateTime.of(LocalDate.parse(value("date"), DATE), LocalTime.parse(value("time"), TIME));
        double latitude = currentRoute == null ? number("latitude") : currentRoute.points().get(0).latitude();
        double longitude = currentRoute == null ? number("longitude") : currentRoute.points().get(0).longitude();
        return new FitEncoder.Run(distance, seconds, (int) cadence, latitude, longitude,
                currentRoute == null ? number("bearing") : 0,
                currentRoute == null ? number("straight") : 84.39,
                currentRoute == null ? number("radius") : 36.8,
                start.atZone(ZoneId.systemDefault()), currentRoute);
    }

    private void chooseDestination() {
        try {
            updatePreview();
            pendingRun = readRun();
            String filename = "run_" + pendingRun.start().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".fit";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, filename);
            startActivityForResult(intent, CREATE_FIT);
        } catch (RuntimeException error) {
            showError(error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_GPX && resultCode == RESULT_OK && data != null && data.getData() != null) {
            askGpxSource(data.getData());
            return;
        }
        if (requestCode == MAP_PICKER && resultCode == RESULT_OK && data != null) {
            fields.get("latitude").setText(format(data.getDoubleExtra("lat", 0), 7));
            fields.get("longitude").setText(format(data.getDoubleExtra("lon", 0), 7));
            fields.get("bearing").setText(format(data.getDoubleExtra("bearing", 0), 1));
            fields.get("straight").setText(format(data.getDoubleExtra("straight", 84.39), 2));
            fields.get("radius").setText(format(data.getDoubleExtra("radius", 36.8), 2));
            status.setText("地图校准已应用");
            updatePreview();
            return;
        }
        if (requestCode != CREATE_FIT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        FitEncoder.Run run = pendingRun;
        generate.setEnabled(false);
        status.setText("正在生成并写入…");
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("无法打开所选文件");
                output.write(FitEncoder.encode(run));
                output.flush();
                runOnUiThread(() -> {
                    generate.setEnabled(true);
                    status.setText("已保存到手机");
                    Toast.makeText(this, "FIT 文件已保存", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> { generate.setEnabled(true); status.setText("生成失败"); showError(error.getMessage()); });
            }
        });
    }

    private void requestCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        fillLastLocation();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) fillLastLocation();
        else if (requestCode == LOCATION_PERMISSION) showError("未获得定位权限，可手动填写 WGS84 坐标");
    }

    private void fillLastLocation() {
        try {
            LocationManager manager = getSystemService(LocationManager.class);
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
            if (best == null) {
                Intent settings = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(settings);
                showError("暂时没有可用位置，请开启定位后再试");
                return;
            }
            fields.get("latitude").setText(String.format(Locale.ROOT, "%.7f", best.getLatitude()));
            fields.get("longitude").setText(String.format(Locale.ROOT, "%.7f", best.getLongitude()));
            status.setText("已填入设备最近位置，请按跑道中心微调");
        } catch (SecurityException error) {
            showError("无法读取设备位置");
        }
    }

    private void openMap() {
        try {
            Intent intent = new Intent(this, MapActivity.class);
            if (currentRoute != null) {
                JSONObject route = new JSONObject();
                route.put("name", currentRoute.name());
                route.put("length", currentRoute.length());
                JSONArray points = new JSONArray();
                for (Route.Point point : currentRoute.previewPoints(600)) {
                    JSONArray pair = new JSONArray();
                    pair.put(point.latitude()); pair.put(point.longitude()); points.put(pair);
                }
                route.put("points", points);
                intent.putExtra("route", route.toString());
            } else {
                String latitude = value("latitude"), longitude = value("longitude");
                if (!latitude.isEmpty() && !longitude.isEmpty()) {
                    intent.putExtra("lat", number("latitude")); intent.putExtra("lon", number("longitude"));
                }
                intent.putExtra("bearing", number("bearing"));
                intent.putExtra("straight", number("straight"));
                intent.putExtra("radius", number("radius"));
            }
            startActivityForResult(intent, MAP_PICKER);
        } catch (RuntimeException error) {
            showError(error.getMessage());
        } catch (Exception error) {
            showError("无法打开地图");
        }
    }

    private void chooseGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"});
        startActivityForResult(intent, IMPORT_GPX);
    }

    private void askGpxSource(Uri uri) {
        String[] labels = {"标准 GPX / GPS（WGS84）", "高德 / 腾讯（GCJ-02）", "百度（BD-09）"};
        new AlertDialog.Builder(this)
                .setTitle("GPX 坐标来源")
                .setSingleChoiceItems(labels, 0, null)
                .setPositiveButton("导入", (dialog, which) -> {
                    int selected = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    GpxParser.CoordinateSource source = GpxParser.CoordinateSource.values()[Math.max(0, selected)];
                    importGpx(uri, source);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importGpx(Uri uri, GpxParser.CoordinateSource source) {
        generate.setEnabled(false);
        status.setText(R.string.gpx_reading);
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("无法读取所选 GPX 文件");
                List<Route> parsed = GpxParser.parse(input, source);
                runOnUiThread(() -> applyRoutes(parsed));
            } catch (Exception error) {
                runOnUiThread(() -> { generate.setEnabled(true); status.setText(R.string.gpx_failed); showError(error.getMessage()); });
            }
        });
    }

    private void applyRoutes(List<Route> parsed) {
        routes.clear(); routes.addAll(parsed);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            labels.add((i + 1) + ". " + route.name() + "（" + format(route.length() / 1000, 3) + " km）");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        segmentSpinner.setAdapter(adapter);
        segmentSpinner.setVisibility(View.VISIBLE);
        segmentSpinner.setSelection(0);
        selectRoute(routes.get(0));
        clearGpxButton.setEnabled(true);
        generate.setEnabled(true);
        if (routes.size() > 1) Toast.makeText(this, "发现 " + routes.size() + " 段轨迹，可从下拉框切换", Toast.LENGTH_LONG).show();
    }

    private void selectRoute(Route route) {
        currentRoute = route;
        fields.get("distance").setText(format(route.length() / 1000, 6));
        for (String key : new String[]{"distance", "latitude", "longitude", "bearing", "straight", "radius"}) fields.get(key).setEnabled(false);
        locateButton.setEnabled(false);
        routeNote.setVisibility(View.VISIBLE);
        routeNote.setText(getString(R.string.route_note, route.name(), route.points().size()));
        status.setText(R.string.gpx_loaded);
        updatePreview();
    }

    private void clearGpx() {
        currentRoute = null; routes.clear();
        segmentSpinner.setAdapter(null); segmentSpinner.setVisibility(View.GONE);
        routeNote.setVisibility(View.GONE); clearGpxButton.setEnabled(false);
        for (String key : new String[]{"distance", "latitude", "longitude", "bearing", "straight", "radius"}) fields.get(key).setEnabled(true);
        locateButton.setEnabled(true);
        fields.get("distance").setText(R.string.default_distance);
        status.setText("已切回标准跑道");
        updatePreview();
    }

    private static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private String value(String key) { return fields.get(key).getText().toString().trim(); }
    private double number(String key) {
        String value = value(key);
        if (value.isEmpty()) throw new IllegalArgumentException("请完整填写所有数据");
        try { return Double.parseDouble(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("请输入有效数字"); }
    }

    private void showError(String message) { Toast.makeText(this, message == null ? "操作失败" : message, Toast.LENGTH_LONG).show(); }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(Color.WHITE, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);
        card.setElevation(dp(1));
        return card;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(0xff194ea8);
        button.setAllCaps(false);
        button.setBackground(roundRect(0xffedf2f8, 9));
        return button;
    }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(48), 1); }
    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable roundStroke(int color, int stroke, float radius) {
        GradientDrawable drawable = roundRect(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
