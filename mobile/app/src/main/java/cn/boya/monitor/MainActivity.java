package cn.boya.monitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @android.webkit.JavascriptInterface public String getConfig() { return ConfigStore.publicConfig(MainActivity.this).toString(); }
        @android.webkit.JavascriptInterface public String getStatus() { return ConfigStore.status(MainActivity.this).toString(); }
        @android.webkit.JavascriptInterface public void saveConfig(String raw) {
            try { ConfigStore.save(MainActivity.this, new JSONObject(raw)); }
            catch (Exception e) { Toast.makeText(MainActivity.this, "配置保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
        @android.webkit.JavascriptInterface public void startMonitor() {
            ConfigStore.start(MainActivity.this);
            Toast.makeText(MainActivity.this, "手机监控已启动", Toast.LENGTH_SHORT).show();
        }
        @android.webkit.JavascriptInterface public void stopMonitor() { stopService(new Intent(MainActivity.this, MonitorService.class)); }
        @android.webkit.JavascriptInterface public void runNow() { ConfigStore.start(MainActivity.this); }
        @android.webkit.JavascriptInterface public void testMail() { Intent i = new Intent(MainActivity.this, MonitorService.class).setAction(MonitorService.ACTION_TEST); if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); }
        @android.webkit.JavascriptInterface public void openLogin() { startActivity(new Intent(MainActivity.this, LoginActivity.class)); }
    }
}
