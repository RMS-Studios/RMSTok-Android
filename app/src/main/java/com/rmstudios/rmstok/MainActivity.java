package com.rmstudios.rmstok;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RMSTok Android client.
 *
 * Loads TikTok in a WebView and injects the RMSTok web bundle (browser.js + browser.css)
 * pulled from the RMS-Studios/RMSTok "devbuild" GitHub release.
 */
public class MainActivity extends Activity {

    private static final String TAG = "RMSTok";

    // RMSTok bundle from the source repo's devbuild release.
    private static final String JS_URL  = "https://github.com/RMS-Studios/RMSTok/releases/download/devbuild/browser.js";
    private static final String CSS_URL = "https://github.com/RMS-Studios/RMSTok/releases/download/devbuild/browser.css";

    private static final String HOME = "https://www.tiktok.com/";
    // A real mobile Chrome UA (no "wv" WebView marker, and matching the actual device).
    // A spoofed *desktop* UA on a mobile device causes a fingerprint mismatch that makes
    // TikTok's login security reject sign-ins ("max attempts"). The settings UI is a
    // layout-independent overlay, so the mobile layout is fine.
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int FILE_CHOOSER = 8485;

    // Note: the RMSTok web bundle persists its own settings to localStorage, so no
    // settings bridge/native storage is needed here — we just inject the bundle + CSS.

    private WebView wv;
    private final Client client = new Client();
    @Nullable private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        setContentView(R.layout.activity_main);

        wv = findViewById(R.id.webview);

        // Persist the TikTok login session. Cookies are stored to disk by the WebView's
        // CookieManager; we accept third-party cookies (TikTok login touches several
        // subdomains) and flush on pause so the session survives app restarts.
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, true);

        var s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(UA);

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                } catch (Exception ex) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        final String startUrl = resolveStartUrl(getIntent());

        // Fetch the bundle (JS + CSS) off the main thread, then start TikTok.
        new Thread(() -> {
            String js = null, css = null;
            try { js = httpGet(JS_URL); }
            catch (IOException ex) { Log.e(TAG, "Failed to fetch browser.js", ex); }
            try { css = httpGet(CSS_URL); }
            catch (IOException ex) { Log.e(TAG, "Failed to fetch browser.css", ex); }

            final String runtime = js, styles = css;
            runOnUiThread(() -> {
                client.runtime = runtime;
                client.css = styles;
                wv.setWebViewClient(client);
                if (runtime == null) {
                    Toast.makeText(this, "RMSTok: couldn't load the mod bundle (is the release public?)", Toast.LENGTH_LONG).show();
                }
                wv.loadUrl(startUrl);
            });
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // persist the session to disk
    }

    private String resolveStartUrl(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && isTikTok(data.getAuthority())) return data.toString();
        }
        return HOME;
    }

    private static boolean isTikTok(@Nullable String host) {
        return host != null && (host.equals("tiktok.com") || host.endsWith(".tiktok.com"));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && wv != null) {
            // Close the RMSTok overlay if open; else go back in history; else exit.
            wv.evaluateJavascript(
                "(function(){var o=document.getElementById('tmod-overlay');" +
                "if(o&&o.style.display!=='none'){var c=o.querySelector('.tmod-overlay-close');" +
                "if(c){c.click();return true;}}return false;})()",
                value -> {
                    if (!"true".equals(value)) {
                        if (wv.canGoBack()) wv.goBack();
                        else finish();
                    }
                });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && wv != null) {
            Uri data = intent.getData();
            if (data != null && isTikTok(data.getAuthority())) wv.loadUrl(data.toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != FILE_CHOOSER || filePathCallback == null) return;

        Uri[] uris = null;
        if (resultCode == RESULT_OK && intent != null) {
            var clip = intent.getClipData();
            if (clip != null) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (intent.getData() != null) {
                uris = new Uri[]{ intent.getData() };
            }
        }
        filePathCallback.onReceiveValue(uris);
        filePathCallback = null;
    }

    // ─── WebViewClient: inject the bundle (JS bypasses CSP via evaluateJavascript) ──
    private final class Client extends WebViewClient {
        @Nullable String runtime;
        @Nullable String css;

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            var url = request.getUrl();
            if (isTikTok(url.getAuthority()) || "about:blank".equals(url.toString())) {
                return false; // keep TikTok navigations inside the app
            }
            // Open everything else (oauth, external links) in the system browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {}
            return true;
        }

        /** Inject the RMSTok bundle (guarded so it runs once per page context). */
        private void inject(WebView view) {
            if (runtime == null) return;
            view.evaluateJavascript(
                "if(!window.__rmstokRuntime){window.__rmstokRuntime=1;\n" + runtime + "\n}", null);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            inject(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            inject(view); // safety net in case the page-start context was discarded
            // Inject browser.css inline as a <style> (avoids a cross-origin <link>, so we
            // don't need to strip CSP — which would have bypassed the cookie jar).
            if (css != null) {
                String escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$");
                view.evaluateJavascript(
                    "(function(){var e=document.getElementById('rmstok-css')||document.createElement('style');" +
                    "e.id='rmstok-css';e.textContent=`" + escaped + "`;" +
                    "if(!e.parentNode)document.documentElement.appendChild(e);})()", null);
            }
            super.onPageFinished(view, url);
        }
    }

    // ─── Tiny HTTP GET ───────────────────────────────────────────────────────────
    private static String httpGet(String urlStr) throws IOException {
        var conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        int code = conn.getResponseCode();
        if (code >= 300) throw new IOException("HTTP " + code + " for " + urlStr);
        try (InputStream is = conn.getInputStream()) {
            return readAll(is);
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try (var baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > -1) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        }
    }
}
