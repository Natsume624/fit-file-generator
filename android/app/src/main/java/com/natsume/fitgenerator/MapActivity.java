package com.natsume.fitgenerator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bundled Leaflet with switchable WGS84 basemaps and bounded place-search fallback. */
public final class MapActivity extends Activity {
    private static final int MAX_SEARCH_BYTES = 512 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView webView;
    private JSONObject initialState;
    private final LinkedHashMap<String, JSONArray> searchCache = new LinkedHashMap<>(64, .75f, true);
    private long lastSearch;

    @SuppressLint("SetJavaScriptEnabled") // Required by bundled Leaflet; navigation is locked to local assets.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 38, 61));
        initialState = readState(getIntent());
        webView = new WebView(this);
        webView.setBackgroundColor(0xffe7edf3);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("window.initFromAndroid(" + initialState + ")", null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) && uri.toString().startsWith("file:///android_asset/")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/android-map.html");
    }

    private JSONObject readState(Intent intent) {
        JSONObject state = new JSONObject();
        try {
            if (intent.hasExtra("route")) {
                state.put("route", new JSONObject(intent.getStringExtra("route")));
            } else {
                state.put("lat", intent.hasExtra("lat") ? intent.getDoubleExtra("lat", 35) : JSONObject.NULL);
                state.put("lon", intent.hasExtra("lon") ? intent.getDoubleExtra("lon", 105) : JSONObject.NULL);
                state.put("bearing", intent.getDoubleExtra("bearing", 0));
                state.put("straight", intent.getDoubleExtra("straight", 84.39));
                state.put("radius", intent.getDoubleExtra("radius", 36.8));
            }
        } catch (Exception error) {
            Toast.makeText(this, "地图参数无效", Toast.LENGTH_LONG).show();
        }
        return state;
    }

    private final class Bridge {
        @JavascriptInterface public void apply(String json) {
            runOnUiThread(() -> {
                try {
                    JSONObject values = new JSONObject(json);
                    double lat = finite(values, "lat", -85, 85);
                    double lon = finite(values, "lon", -180, 180);
                    double bearing = finite(values, "bearing", 0, 360);
                    double straight = finite(values, "straight", 1, 1000);
                    double radius = finite(values, "radius", 5, 300);
                    Intent result = new Intent()
                            .putExtra("lat", lat).putExtra("lon", lon)
                            .putExtra("bearing", bearing).putExtra("straight", straight).putExtra("radius", radius);
                    setResult(RESULT_OK, result);
                    finish();
                } catch (Exception error) {
                    Toast.makeText(MapActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void search(String query) {
            String clean = query == null ? "" : query.strip();
            if (clean.length() < 2 || clean.length() > 120) {
                deliverSearchError("请至少输入两个字，最多 120 字。");
                return;
            }
            worker.execute(() -> searchPlaces(clean));
        }
    }

    private void searchPlaces(String query) {
        if (searchCache.containsKey(query)) {
            deliverSearch(searchCache.get(query));
            return;
        }
        long now = System.nanoTime();
        if (now - lastSearch < 1_200_000_000L) {
            deliverSearchError("请稍后再搜索");
            return;
        }
        lastSearch = now;
        JSONArray results = new JSONArray();
        boolean receivedResponse = false;
        for (boolean photon : new boolean[]{true, false}) {
            try {
                results = requestSearch(query, photon);
                receivedResponse = true;
                if (results.length() > 0) break;
            } catch (Exception ignored) {
                // A failed or empty primary response may still have a usable fallback.
            }
        }
        lastSearch = System.nanoTime();
        if (!receivedResponse) {
            deliverSearchError("地点搜索暂不可用。可缩放地图选点，或返回输入坐标 / 导入 GPX。");
            return;
        }
        searchCache.put(query, results);
        if (searchCache.size() > 64) searchCache.remove(searchCache.keySet().iterator().next());
        deliverSearch(results);
    }

    private JSONArray requestSearch(String query, boolean photon) throws Exception {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            URL url = new URL((photon ? "https://photon.komoot.io/api/?limit=5&q="
                    : "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=") + encoded);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(6_000);
            connection.setRequestProperty("User-Agent", "fit-file-generator-android/2.4.2 (+https://github.com/Natsume624/fit-file-generator)");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
            if (connection.getResponseCode() != 200) throw new IllegalStateException("地点搜索暂不可用（" + connection.getResponseCode() + "）");
            byte[] body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_SEARCH_BYTES) throw new IllegalStateException("地点搜索响应过大");
                    output.write(buffer, 0, count);
                }
                body = output.toByteArray();
            }
            String json = new String(body, StandardCharsets.UTF_8);
            JSONArray features = photon ? new JSONObject(json).getJSONArray("features") : new JSONArray(json);
            JSONArray results = new JSONArray();
            for (int i = 0; i < Math.min(5, features.length()); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) continue;
                double lat, lon;
                String label;
                if (photon) {
                    JSONObject geometry = feature.optJSONObject("geometry");
                    JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
                    if (coordinates == null || coordinates.length() < 2) continue;
                    lat = coordinates.optDouble(1, Double.NaN);
                    lon = coordinates.optDouble(0, Double.NaN);
                    JSONObject properties = feature.optJSONObject("properties");
                    String name = properties == null ? "搜索结果" : properties.optString("name", properties.optString("city", "搜索结果"));
                    String city = properties == null ? "" : properties.optString("city", properties.optString("state", ""));
                    label = city.isEmpty() || city.equals(name) ? name : name + " · " + city;
                } else {
                    lat = feature.optDouble("lat", Double.NaN);
                    lon = feature.optDouble("lon", Double.NaN);
                    label = feature.optString("display_name", "搜索结果");
                }
                if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -85 || lat > 85 || lon < -180 || lon > 180) continue;
                label = label.strip();
                if (label.length() > 200) label = label.substring(0, 200);
                JSONObject item = new JSONObject();
                item.put("label", label.isEmpty() ? "搜索结果" : label);
                item.put("lat", lat);
                item.put("lon", lon);
                results.put(item);
            }
            return results;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deliverSearch(JSONArray results) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) webView.evaluateJavascript("window.receiveSearch(" + results + ")", null);
        });
    }

    private void deliverSearchError(String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && webView != null) webView.evaluateJavascript("window.searchFailed(" + JSONObject.quote(message) + ")", null);
        });
    }

    private static double finite(JSONObject values, String key, double minimum, double maximum) throws Exception {
        double value = values.getDouble(key);
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException("地图参数超出范围：" + key);
        return value;
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
