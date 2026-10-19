package com.tvdigao.tvoffline;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        prefs = getSharedPreferences("tvo_prefs", Context.MODE_PRIVATE);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new TVBridge(), "TVNative");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("menu(menuOn?0:1)", null);
        }
    }

    private File getInternalDir() {
        File d = new File(getFilesDir(), "videos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File getSdCardDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null) {
                for (int i = 1; i < dirs.length; i++) {
                    if (dirs[i] != null) {
                        File v = new File(dirs[i], "videos");
                        if (!v.exists()) v.mkdirs();
                        if (v.exists() && v.canWrite()) return v;
                    }
                }
            }
        }
        return null;
    }

    private String readSysMac(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            br.close();
            if (line != null) {
                line = line.trim().toUpperCase();
                if (line.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") && !line.equals("02:00:00:00:00:00")) {
                    return line;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public class TVBridge {
        @JavascriptInterface
        public String getMacId() {
            String saved = prefs.getString("device_mac", null);
            if (saved != null && !saved.isEmpty()) return saved;

            String m = readSysMac("/sys/class/net/eth0/address");
            if (m == null) m = readSysMac("/sys/class/net/wlan0/address");
            if (m == null) {
                try {
                    List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
                    for (NetworkInterface nif : all) {
                        if (!nif.getName().equalsIgnoreCase("wlan0") && !nif.getName().equalsIgnoreCase("eth0")) continue;
                        byte[] macBytes = nif.getHardwareAddress();
                        if (macBytes != null && macBytes.length == 6) {
                            StringBuilder sb = new StringBuilder();
                            for (byte b : macBytes) sb.append(String.format("%02X:", b));
                            if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                            String cand = sb.toString();
                            if (!cand.equals("02:00:00:00:00:00")) { m = cand; break; }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (m == null) {
                try {
                    String aid = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (aid == null || aid.isEmpty()) aid = Build.SERIAL + Build.BOARD + Build.MODEL;
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    byte[] d = md.digest(aid.getBytes("UTF-8"));
                    m = String.format("%02X:%02X:%02X:%02X:%02X:%02X", d[0], d[1], d[2], d[3], d[4], d[5]);
                } catch (Exception e) {
                    m = "02:AA:BB:CC:DD:EE";
                }
            }
            prefs.edit().putString("device_mac", m).apply();
            return m;
        }

        @JavascriptInterface
        public String getStorageInfo() {
            try {
                JSONObject o = new JSONObject();
                File intDir = getInternalDir();
                StatFs sInt = new StatFs(intDir.getAbsolutePath());
                o.put("internalPath", intDir.getAbsolutePath());
                o.put("internalFree", sInt.getAvailableBytes());
                o.put("internalTotal", sInt.getTotalBytes());

                File sdDir = getSdCardDir();
                if (sdDir != null) {
                    StatFs sSd = new StatFs(sdDir.getAbsolutePath());
                    o.put("sdAvailable", true);
                    o.put("sdPath", sdDir.getAbsolutePath());
                    o.put("sdFree", sSd.getAvailableBytes());
                    o.put("sdTotal", sSd.getTotalBytes());
                } else {
                    o.put("sdAvailable", false);
                    o.put("sdFree", 0);
                    o.put("sdTotal", 0);
                }
                o.put("target", prefs.getString("storage_target", "interno"));
                return o.toString();
            } catch (Exception e) {
                return "{\"sdAvailable\":false,\"internalFree\":999999999}";
            }
        }

        @JavascriptInterface
        public void setStorageTarget(String target) {
            prefs.edit().putString("storage_target", "sd".equals(target) ? "sd" : "interno").apply();
        }

        @JavascriptInterface
        public String downloadVideo(String urlStr, String fileName, final String label) {
            File targetDir = getInternalDir();
            String chosen = prefs.getString("storage_target", "interno");
            if ("sd".equals(chosen)) {
                File sd = getSdCardDir();
                if (sd != null) targetDir = sd;
                else chosen = "interno";
            }
            File out = new File(targetDir, fileName);
            File tmp = new File(targetDir, fileName + ".tmp");
            HttpURLConnection conn = null;
            try {
                URL u = new URL(urlStr);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();
                if (conn.getResponseCode() >= 400) throw new Exception("HTTP " + conn.getResponseCode());
                long total = conn.getContentLength();
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                long count = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    count += n;
                    if (total > 0) {
                        final int pct = (int) ((count * 100) / total);
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct;
                            runOnUiThread(() -> webView.evaluateJavascript("window.__dlProg&&window.__dlProg(" + JSONObject.quote(label) + "," + pct + ")", null));
                        }
                    }
                }
                fos.flush();
                fos.close();
                in.close();
                if (total > 0 && count != total) {
                    tmp.delete();
                    throw new Exception("incompleto");
                }
                if (out.exists()) out.delete();
                tmp.renameTo(out);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("path", out.getAbsolutePath());
                res.put("size", count);
                res.put("target", chosen);
                return res.toString();
            } catch (Exception e) {
                if (tmp.exists()) tmp.delete();
                try {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("err", e.getMessage());
                    return err.toString();
                } catch (Exception ignored) {
                    return "{\"ok\":false}";
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        @JavascriptInterface
        public boolean fileExists(String path) {
            return path != null && new File(path).exists();
        }

        @JavascriptInterface
        public boolean deleteFile(String path) {
            return path != null && new File(path).delete();
        }

        @JavascriptInterface
        public void clearAllVideos() {
            File[] d1 = getInternalDir().listFiles();
            if (d1 != null) for (File f : d1) f.delete();
            File sd = getSdCardDir();
            if (sd != null) {
                File[] d2 = sd.listFiles();
                if (d2 != null) for (File f : d2) f.delete();
            }
        }
    }
}
