package com.rmstudios.rmstok;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

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
    // Desktop UA so TikTok serves the desktop layout the mod targets.
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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

        var s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(DESKTOP_UA);

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

        // Fetch the bundle off the main thread, then start TikTok.
        new Thread(() -> {
            String js = null;
            try {
                js = httpGet(JS_URL);
            } catch (IOException ex) {
                Log.e(TAG, "Failed to fetch browser.js", ex);
            }
            final String runtime = js;
            runOnUiThread(() -> {
                client.runtime = runtime;
                wv.setWebViewClient(client);
                if (runtime == null) {
                    Toast.makeText(this, "RMSTok: couldn't load the mod bundle (is the release public?)", Toast.LENGTH_LONG).show();
                }
                wv.loadUrl(startUrl);
            });
        }).start();
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
            // Close the RMSTok panel if open; else go back in history; else exit.
            wv.evaluateJavascript(
                "(function(){var m=document.getElementById('tmod-mount');" +
                "if(m&&m.style.display!=='none'){var o=document.querySelector('[class*=\"DivNavItem\"]:not(#tmod-nav-item)');" +
                "if(o){o.click();return true;}}return false;})()",
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

    // ─── WebViewClient: inject the bundle + strip CSP ────────────────────────────
    private final class Client extends WebViewClient {
        @Nullable String runtime;

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
            // Inject browser.css as a stylesheet link (CSP is stripped below so it loads).
            view.evaluateJavascript(
                "(function(){if(!document.getElementById('rmstok-css')){" +
                "var l=document.createElement('link');l.id='rmstok-css';l.rel='stylesheet';l.type='text/css';" +
                "l.href='" + CSS_URL + "';document.documentElement.appendChild(l);}})()", null);
            super.onPageFinished(view, url);
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            var path = req.getUrl().getPath();
            boolean isCss = path != null && path.endsWith(".css");
            if (req.isForMainFrame() || isCss) {
                try {
                    return fetchStripped(req);
                } catch (IOException ex) {
                    Log.e(TAG, "intercept failed", ex);
                }
            }
            return null;
        }

        /** Re-fetch a request, dropping the Content-Security-Policy header so injection works. */
        private WebResourceResponse fetchStripped(WebResourceRequest req) throws IOException {
            var conn = (HttpURLConnection) new URL(req.getUrl().toString()).openConnection();
            conn.setRequestMethod(req.getMethod());
            conn.setInstanceFollowRedirects(true);
            for (var h : req.getRequestHeaders().entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            int code = conn.getResponseCode();
            String msg = conn.getResponseMessage();
            if (msg == null || msg.isEmpty()) msg = "OK";

            Map<String, String> headers = new HashMap<>();
            for (var e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                if ("content-security-policy".equalsIgnoreCase(e.getKey())) continue;
                if ("content-security-policy-report-only".equalsIgnoreCase(e.getKey())) continue;
                if (e.getValue() != null && !e.getValue().isEmpty()) headers.put(e.getKey(), e.getValue().get(0));
            }
            String contentType = conn.getContentType();
            if (req.getUrl().toString().endsWith(".css")) contentType = "text/css";
            if (contentType == null) contentType = "application/octet-stream";
            // Strip any charset for the WebResourceResponse mime arg.
            String mime = contentType.split(";")[0].trim();

            return new WebResourceResponse(mime, "utf-8", code, msg, headers, conn.getInputStream());
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
