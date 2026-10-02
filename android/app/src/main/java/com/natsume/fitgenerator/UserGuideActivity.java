package com.natsume.fitgenerator;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Read the bundled offline manual without enabling scripts or network requests. */
public final class UserGuideActivity extends Activity {
    private static final String GUIDE_URL = "file:///android_asset/user-guide.html";
    private WebView webView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 38, 61));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setBackgroundColor(Color.rgb(20, 38, 61));
        Button back = new Button(this);
        back.setText(R.string.guide_back);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back);
        TextView title = new TextView(this);
        title.setText(R.string.user_guide);
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setBlockNetworkLoads(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                if (GUIDE_URL.equals(url) || url.startsWith(GUIDE_URL + "#")) return false;
                if ("https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                        && uri.getPath() != null && uri.getPath().startsWith("/Natsume624/fit-file-generator/")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException error) {
                        Toast.makeText(UserGuideActivity.this, R.string.guide_browser_unavailable, Toast.LENGTH_LONG).show();
                    }
                }
                return true;
            }
        });
        page.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        webView.loadUrl(GUIDE_URL);
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
