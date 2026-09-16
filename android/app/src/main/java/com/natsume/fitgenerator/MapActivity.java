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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local Leaflet map backed by OpenStreetMap tiles and a bounded native Photon search proxy. */
public final class MapActivity extends Activity {
    private static final int MAX_SEARCH_BYTES = 512 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView webView;
    private JSONObject initialState;

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
            worker.execute(() -> searchPhoton(clean));
        }
    }

    private void searchPhoton(String query) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            URL url = new URL("https://photon.komoot.io/api/?limit=6&q=" + encoded);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "fit-file-generator-android/2.2 (+https://github.com/Natsume624/fit-file-generator)");
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
            JSONArray features = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("features");
            JSONArray results = new JSONArray();
            if (features != null) for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) continue;
                JSONObject geometry = feature.optJSONObject("geometry");
                JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
                if (coordinates == null || coordinates.length() < 2) continue;
                JSONObject properties = feature.optJSONObject("properties");
                String name = properties == null ? "搜索结果" : properties.optString("name", properties.optString("city", "搜索结果"));
                String city = properties == null ? "" : properties.optString("city", properties.optString("state", ""));
                JSONObject item = new JSONObject();
                item.put("label", city.isEmpty() || city.equals(name) ? name : name + " · " + city);
                item.put("lat", coordinates.getDouble(1));
                item.put("lon", coordinates.getDouble(0));
                results.put(item);
            }
            String script = "window.receiveSearch(" + results + ")";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        } catch (Exception error) {
            deliverSearchError(error.getMessage() == null ? "地点搜索失败" : error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deliverSearchError(String message) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("window.searchFailed(" + JSONObject.quote(message) + ")", null);
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
