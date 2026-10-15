package cn.boya.monitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "boya_mobile";
    private static final String SERVER_URL = "server_url";
    private SharedPreferences preferences;
    private WebView webView;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = preferences.getString(SERVER_URL, "");
        if (saved.isEmpty()) showServerForm("");
        else showWebView(saved);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void showServerForm(String error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(42), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = text("博雅课程监控", 28, Color.rgb(15, 23, 42));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("连接你的监控服务器", 16, Color.rgb(71, 85, 105));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        root.addView(subtitle, subtitleParams);

        TextView hint = text("先在电脑或服务器启动博雅监控，再填写它的地址。\n例如：http://192.168.1.20:8787", 14, Color.rgb(71, 85, 105));
        hint.setPadding(0, dp(36), 0, dp(10));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("监控服务器地址");
        input.setText(preferences.getString(SERVER_URL, ""));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));

        if (!error.isEmpty()) {
            TextView errorText = text(error, 14, Color.rgb(185, 28, 28));
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
            errorParams.topMargin = dp(8);
            root.addView(errorText, errorParams);
        }

        Button open = new Button(this);
        open.setText("打开监控");
        open.setTextColor(Color.WHITE);
        open.setAllCaps(false);
        open.setBackgroundResource(cn.boya.monitor.R.drawable.rounded_button);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.topMargin = dp(22);
        root.addView(open, buttonParams);
        open.setOnClickListener(v -> {
            String url = normalize(input.getText().toString());
            if (url.isEmpty()) {
                input.setError("请输入服务器地址");
                return;
            }
            preferences.edit().putString(SERVER_URL, url).apply();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
            showWebView(url);
        });
        setContentView(root);
    }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        try {
            Uri uri = Uri.parse(value);
            if (uri.getHost() == null) return "";
            return value;
        } catch (Exception ignored) {
            return "";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebView(String url) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.WHITE);
        TextView title = text("博雅课程监控", 18, Color.rgb(15, 23, 42));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button settings = new Button(this);
        settings.setText("服务器");
        settings.setTextSize(13);
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> showServerForm(""));
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(88), dp(48)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(52)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "无法连接服务器，请检查地址和网络", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
