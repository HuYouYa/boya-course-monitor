package cn.boya.monitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import org.json.JSONObject;

public class LoginActivity extends Activity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WebView page = new WebView(this);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        page.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                JSONObject c = ConfigStore.config(LoginActivity.this);
                String u = JSONObject.quote(c.optString("username")), p = JSONObject.quote(c.optString("password"));
                v.evaluateJavascript("(function(){var u=document.querySelector('input[name=username],input#username,input[type=text]'),p=document.querySelector('input[name=password],input#password,input[type=password]');if(u)u.value="+u+";if(p){p.value="+p+";var b=document.querySelector('button[type=submit],input[type=submit],.submit-btn');if(b)b.click();}})()", null);
            }
        });
        setContentView(page);
        page.loadUrl(MonitorService.SITE_URL);
    }

    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }
}
