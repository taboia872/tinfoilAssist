package io.github.tinfoilassist;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.webkit.URLUtilCompat;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.ScriptHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;
import java.util.Set;

public class MainActivity extends Activity {

    private WebView chatWebView = null;
    private ImageButton btnMenuToggle = null;
    private ImageButton btnReload = null;
    private ImageButton btnFullscreen = null;
    private ImageButton btnClearData = null;
    private ImageButton btnAbout = null;
    private ImageButton btnSettings = null;
    private LinearLayout menuBar = null;
    private boolean menuVisible = false;
    private WebSettings chatWebSettings = null;
    private CookieManager chatCookieManager = null;
    private SharedPreferences prefs = null;
    private final Context context = this;
    private SwipeTouchListener swipeTouchListener;
    private static final String TAG = "tinfoilAssist";
    private static final String URL_TO_LOAD = "https://chat.tinfoil.sh/";
    private static boolean restricted = true;
    private static boolean webrtcBlocked = true;
    private static boolean sensorsBlocked = true;
    private static boolean dntEnabled = true;
    private static boolean timezoneSpoofed = true;
    private static boolean desktopModeEnabled = false;
    private static boolean fullscreenEnabled = false;
    private static String spoofedTimezone = "UTC";

    private static final ArrayList<String> allowedDomains = new ArrayList<>();

    private ValueCallback<Uri[]> mUploadMessage;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;

    // Script handles injected via addDocumentStartJavaScript (API-neutral via
    // androidx.webkit). Each is registered once after WebView configuration so
    // they fire before any page script on every navigation (document_start).
    private ScriptHandler tzScriptHandler = null;
    private ScriptHandler hardeningScriptHandler = null;
    private static final Set<String> ALLOW_ALL_ORIGINS = Collections.singleton("*");

    // Pick a random timezone once per session if timezone spoofing is on.
    private static String pickRandomTimezone() {
        String[] timezones = {
            "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
            "America/Sao_Paulo", "America/Toronto", "America/Vancouver",
            "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Rome",
            "Europe/Amsterdam", "Europe/Stockholm", "Europe/Warsaw", "Europe/Istanbul",
            "Asia/Tokyo", "Asia/Singapore", "Asia/Seoul", "Asia/Bangkok",
            "Asia/Dubai", "Asia/Kolkata", "Asia/Hong_Kong",
            "Australia/Sydney", "Australia/Melbourne"
        };
        return timezones[(int) (Math.random() * timezones.length)];
    }

    private String readAsset(String filename) {
        try (InputStream is = getAssets().open(filename)) {
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        } catch (IOException e) {
            Log.e(TAG, "readAsset(" + filename + "): " + e.getMessage());
            return "";
        }
    }

    private String buildTzSpoofScript() {
        if (timezoneSpoofed && "UTC".equals(spoofedTimezone)) {
            spoofedTimezone = pickRandomTimezone();
        }
        String tz = timezoneSpoofed ? spoofedTimezone : "";
        String json = "{\"timezone\":\"" + tz + "\",\"tzEnabled\":" + timezoneSpoofed + "}";
        String js = readAsset("tzspoof.js");
        return "window.__TA_SETTINGS__ = " + json + ";\n" + js;
    }

    private String buildHardeningScript() {
        int cores = desktopModeEnabled ? 8 : 4;
        int memory = desktopModeEnabled ? 8 : 4;
        String gpuVendor = desktopModeEnabled
            ? "Google Inc. (Intel)"
            : "Qualcomm";
        String gpuRenderer = desktopModeEnabled
            ? "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.1)"
            : "Adreno (TM) 650";
        String json = "{\"sensorsBlocked\":" + sensorsBlocked
            + ",\"dntEnabled\":" + dntEnabled
            + ",\"webrtcBlocked\":" + webrtcBlocked
            + ",\"spoofCores\":" + cores
            + ",\"spoofMemory\":" + memory
            + ",\"spoofGpuVendor\":\"" + gpuVendor + "\""
            + ",\"spoofGpuRenderer\":\"" + gpuRenderer + "\"}";
        String js = readAsset("hardening.js");
        return "window.__TA_SETTINGS__ = " + json + ";\n" + js;
    }

    private void installDocumentStartScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (tzScriptHandler != null) { tzScriptHandler.remove(); tzScriptHandler = null; }
        if (hardeningScriptHandler != null) { hardeningScriptHandler.remove(); hardeningScriptHandler = null; }
        try {
            hardeningScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                chatWebView, buildHardeningScript(), ALLOW_ALL_ORIGINS);
        } catch (Exception e) { Log.w(TAG, "hardening script registration: " + e.getMessage()); }
        try {
            tzScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                chatWebView, buildTzSpoofScript(), ALLOW_ALL_ORIGINS);
        } catch (Exception e) { Log.w(TAG, "tz spoof script registration: " + e.getMessage()); }
    }

    private boolean documentStartSupported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    @Override
    protected void onPause() {
        if (chatCookieManager != null) chatCookieManager.flush();
        swipeTouchListener = null;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Arrow tab click — toggle menu open/closed
        btnMenuToggle.setOnClickListener(v -> {
            if (menuVisible) {
                hideMenu();
            } else {
                showMenu();
            }
        });

        // Reload page
        btnReload.setOnClickListener(v -> {
            chatWebView.reload();
            hideMenu();
        });

        // Fullscreen toggle — smooth content expand/collapse
        btnFullscreen.setOnClickListener(v -> {
            fullscreenEnabled = !fullscreenEnabled;
            applyFullscreen();
            Toast.makeText(context,
                fullscreenEnabled ? "Fullscreen on" : "Fullscreen off",
                Toast.LENGTH_SHORT).show();
            hideMenu();
        });

        // Clear all data with confirmation dialog
        btnClearData.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_clear_title)
                .setMessage(R.string.confirm_clear_data)
                .setPositiveButton(R.string.confirm_yes, (dialog, which) -> {
                    resetChat();
                    Toast.makeText(context, R.string.data_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.confirm_no, null)
                .show();
            hideMenu();
        });

        // About dialog
        btnAbout.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.dialog_OK_button, null)
                .show();
            hideMenu();
        });

        // Settings dialog — toggle privacy/security options
        btnSettings.setOnClickListener(v -> {
            String[] options = {
                getString(R.string.setting_restrict_domains),
                getString(R.string.setting_block_webrtc),
                getString(R.string.setting_block_sensors),
                getString(R.string.setting_dnt),
                getString(R.string.setting_spoof_tz),
                getString(R.string.setting_desktop_mode)
            };
            boolean[] checked = {restricted, webrtcBlocked, sensorsBlocked, dntEnabled, timezoneSpoofed, desktopModeEnabled};
            new AlertDialog.Builder(context)
                .setTitle(getString(R.string.settings_title))
                .setMultiChoiceItems(options, checked, (dialog, which, isChecked) -> {
                    if (which == 0) restricted = isChecked;
                    else if (which == 1) webrtcBlocked = isChecked;
                    else if (which == 2) sensorsBlocked = isChecked;
                    else if (which == 3) dntEnabled = isChecked;
                    else if (which == 4) {
                        timezoneSpoofed = isChecked;
                        if (!isChecked) spoofedTimezone = "UTC";
                    }
                    else if (which == 5) desktopModeEnabled = isChecked;
                })
                .setPositiveButton(getString(R.string.setting_apply), (dialog, which) -> {
                    saveSettings();
                    chatWebSettings.setUserAgentString(modUserAgent());
                    applyUserAgentMetadata();
                    installDocumentStartScripts();
                    chatWebView.reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            hideMenu();
        });

        swipeTouchListener = new SwipeTouchListener(context) {
            @Override
            public void onSwipeBottom() {
                if (!chatWebView.canScrollVertically(0)) {
                    menuBar.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onSwipeTop() {
                hideMenu();
                menuBar.setVisibility(View.GONE);
            }
        };

        chatWebView.setOnTouchListener(swipeTouchListener);
    }

    private int getArrowWidth() {
        ImageButton arrow = menuBar.findViewById(R.id.btnMenuToggleInner);
        return arrow.getWidth();
    }

    private void showMenu() {
        menuVisible = true;
        // Show action buttons immediately (no fade)
        int[] btnIds = {R.id.btnReload, R.id.btnFullscreen, R.id.btnClearData, R.id.btnSettings, R.id.btnAbout};
        for (int btnId : btnIds) {
            ImageButton btn = menuBar.findViewById(btnId);
            btn.setAlpha(1f);
            btn.setVisibility(View.VISIBLE);
        }
        // Measure full width now that all buttons are visible
        menuBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int fullWidth = menuBar.getMeasuredWidth();
        int arrowWidth = getArrowWidth();
        int slideDistance = fullWidth - arrowWidth;
        // Slide container left by exactly the distance that reveals all buttons
        menuBar.setTranslationX(slideDistance);
        menuBar.animate()
            .translationX(0f)
            .setDuration(500)
            .start();
    }

    private void hideMenu() {
        if (!menuVisible) return;
        menuVisible = false;
        // Measure current full width (all buttons visible)
        menuBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int fullWidth = menuBar.getMeasuredWidth();
        int arrowWidth = getArrowWidth();
        int slideDistance = fullWidth - arrowWidth;
        // Slide right by the distance that hides all buttons except the arrow
        menuBar.animate()
            .translationX(slideDistance)
            .setDuration(500)
            .withEndAction(() -> {
                int[] btnIds = {R.id.btnReload, R.id.btnFullscreen, R.id.btnClearData, R.id.btnSettings, R.id.btnAbout};
                for (int btnId : btnIds) {
                    ImageButton btn = menuBar.findViewById(btnId);
                    btn.setVisibility(View.GONE);
                }
                menuBar.setTranslationX(0f);
            })
            .start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("tinfoil_prefs", Context.MODE_PRIVATE);
        loadSettings();

        // Separate WebView data directory for isolation (sandboxing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("tinfoil_chat");
            } catch (Exception e) {
                Log.w(TAG, "setDataDirectorySuffix failed: " + e.getMessage());
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        // Edge-to-edge window: app draws behind system bars. Insets are applied
        // as padding on the root so content expands/contracts with bar visibility.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        setContentView(R.layout.activity_main);

        final android.view.View rootView = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView,
            (v, windowInsets) -> {
                androidx.core.graphics.Insets bars = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
                animatePadding(v, bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });

        chatWebView = findViewById(R.id.chatWebView);
        registerForContextMenu(chatWebView);
        btnMenuToggle = findViewById(R.id.btnMenuToggleInner);
        btnReload = findViewById(R.id.btnReload);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnClearData = findViewById(R.id.btnClearData);
        btnSettings = findViewById(R.id.btnSettings);
        btnAbout = findViewById(R.id.btnAbout);
        menuBar = findViewById(R.id.menuBar);

        // Cookie security settings - Allow cookies for domain storage and authentication persistence
        chatCookieManager = CookieManager.getInstance();
        chatCookieManager.setAcceptCookie(true);
        chatCookieManager.setAcceptThirdPartyCookies(chatWebView, true);

        initURLs();

        chatWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.")) {
                    Toast.makeText(context, R.string.error_copy, Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
                    }
                }
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }

                mUploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (request.getResources().length > 0 && request.getResources()[0].equals(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123);
                        }
                    } else {
                        request.deny();
                    }
                } else {
                    request.grant(request.getResources());
                }
            }
        });

        chatWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Bridge (full navigation): Clerk may land on /signin via document
                // load. SPAs use pushState and skip this — see doUpdateVisitedHistory.
                if (interceptBrokenSignin(view, url)) return;
                if (!documentStartSupported()) {
                    view.evaluateJavascript(buildHardeningScript(), null);
                    view.evaluateJavascript(buildTzSpoofScript(), null);
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // Bridge (SPA pushState): chat.tinfoil.sh is a Next.js SPA, so in-page
                // navigation to /signin goes through history.pushState and never
                // fires onPageStarted. doUpdateVisitedHistory catches it.
                interceptBrokenSignin(view, url);
            }

            // Returns true if the broken /signin URL was intercepted and the
            // bridge dialog was shown; false otherwise.
            private boolean interceptBrokenSignin(WebView view, String url) {
                if (url != null && url.startsWith("https://chat.tinfoil.sh/signin")) {
                    Log.d(TAG, "[bridge] intercepted navigation to broken /signin: " + url);
                    view.stopLoading();
                    showLoginBridgeDialog();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (chatCookieManager != null) {
                    chatCookieManager.flush();
                }
                // Bridge: login on tinfoil.sh redirects to dash.tinfoil.sh on success.
                // At this point the Clerk session cookie is set, so chat.tinfoil.sh
                // will be authenticated. Send the user back to the chat.
                if (url != null && url.startsWith("https://dash.tinfoil.sh")) {
                    Log.d(TAG, "[bridge] login completed on tinfoil.sh, returning to chat");
                    view.loadUrl(URL_TO_LOAD);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (!restricted) return null;

                String urlStr = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();

                // Essential: Allow blob:, data:, and about: schemes used by WebWorkers, IndexedDB, and Local Storage
                if (scheme != null && ("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme))) {
                    return null;
                }

                if (urlStr.equals("about:blank")) {
                    return null;
                }

                if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked: " + urlStr);
                    return blockedResponse();
                }

                String host = request.getUrl().getHost();
                if (!isAllowedHost(host)) {
                    Log.d(TAG, "[shouldInterceptRequest][BLOCKED] " + host);
                    return blockedResponse();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!restricted) return false;

                String urlStr = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();

                if (scheme != null && ("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme))) {
                    return false;
                }

                if (urlStr.equals("about:blank")) {
                    return false;
                }

                if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked: " + urlStr);
                    return true;
                }

                boolean allowed = isAllowedHost(request.getUrl().getHost());

                if (!allowed) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][BLOCKED] " + request.getUrl().getHost());
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "[onReceivedError] " + error.getErrorCode() + ": " + error.getDescription() + " @ " + request.getUrl());
                }
            }
        });

        chatWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Uri source = Uri.parse(url);
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
            request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
            request.addRequestHeader("Referer", url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
            if (filename == null) filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(request);
        });

        // Configure WebSettings for full local storage (LocalStorage, IndexedDB, Database)
        chatWebSettings = chatWebView.getSettings();
        chatWebSettings.setJavaScriptEnabled(true);
        chatWebSettings.setDomStorageEnabled(true);
        chatWebSettings.setDatabaseEnabled(true);
        chatWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Security / Hardening overrides
        chatWebSettings.setAllowContentAccess(false);
        chatWebSettings.setAllowFileAccess(false);
        chatWebSettings.setBuiltInZoomControls(false);
        chatWebSettings.setDisplayZoomControls(false);
        chatWebSettings.setSaveFormData(false);
        chatWebSettings.setGeolocationEnabled(false);
        chatWebSettings.setUserAgentString(modUserAgent());
        applyUserAgentMetadata();

        // Install document_start scripts — fires before page scripts on every
        // navigation. This is the primary injection path; onPageStarted fallback
        // handles older WebViews that lack DOCUMENT_START_SCRIPT support.
        installDocumentStartScripts();

        chatWebView.loadUrl(URL_TO_LOAD);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (chatWebView.canGoBack() && !chatWebView.getUrl().equals("about:blank")) {
                    chatWebView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void resetChat() {
        chatWebView.clearCache(true);
        chatWebView.clearFormData();
        chatWebView.clearHistory();
        chatWebView.clearMatches();
        chatWebView.clearSslPreferences();
        chatCookieManager.removeSessionCookie();
        // removeAllCookies is async — only reload after cookies are actually gone,
        // otherwise the fresh page load can still send stale session cookies.
        chatCookieManager.removeAllCookies(value -> {
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            chatWebView.loadUrl(URL_TO_LOAD);
        });
    }

    private void loadSettings() {
        restricted = prefs.getBoolean("restricted", true);
        webrtcBlocked = prefs.getBoolean("webrtcBlocked", true);
        sensorsBlocked = prefs.getBoolean("sensorsBlocked", true);
        dntEnabled = prefs.getBoolean("dntEnabled", true);
        timezoneSpoofed = prefs.getBoolean("timezoneSpoofed", true);
        desktopModeEnabled = prefs.getBoolean("desktopModeEnabled", false);
    }

    // chat.tinfoil.sh/signin is broken at the Clerk level (valid codes end in
    // "no matching user"). The modal flow on tinfoil.sh works. This dialog
    // offers the bridge; onPageFinished catches the dash.tinfoil.sh redirect
    // and brings the user back to the chat as authenticated.
    private void showLoginBridgeDialog() {
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.bridge_title))
                .setMessage(getString(R.string.bridge_message))
                .setPositiveButton(getString(R.string.bridge_go), (dialog, which) -> {
                    chatWebView.loadUrl("https://tinfoil.sh");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean("restricted", restricted)
                .putBoolean("webrtcBlocked", webrtcBlocked)
                .putBoolean("sensorsBlocked", sensorsBlocked)
                .putBoolean("dntEnabled", dntEnabled)
                .putBoolean("timezoneSpoofed", timezoneSpoofed)
                .putBoolean("desktopModeEnabled", desktopModeEnabled)
                .apply();
    }

    // Exact-host or proper subdomain match: "chat.tinfoil.sh" matches "tinfoil.sh",
    // but "eviltinfoil.sh" does NOT. Fixes whitelist bypass via lookalike domains.
    private boolean isAllowedHost(String host) {
        if (host == null) return false;
        for (String domain : allowedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    // Blocked requests get an explicit 403 with a JSON body instead of 200 OK with
    // text/javascript and an empty body — the old response broke auth-provider JS
    // (e.g. Clerk) that called response.json() and got a parse error / null.
    private WebResourceResponse blockedResponse() {
        return new WebResourceResponse(
                "application/json", "UTF-8", 403, "Forbidden",
                Collections.singletonMap("Content-Type", "application/json"),
                new java.io.ByteArrayInputStream("{}".getBytes()));
    }

    private static void initURLs() {
        // Domains permitted for Tinfoil Chat, Clerk, and authentication providers
        allowedDomains.add("tinfoil.sh");
        allowedDomains.add("chat.tinfoil.sh");
        allowedDomains.add("api.tinfoil.sh");
        allowedDomains.add("atc.tinfoil.sh");
        allowedDomains.add("clerk.tinfoil.sh");
        allowedDomains.add("verification-center.tinfoil.sh");
        allowedDomains.add("clerk.accounts.dev");
        allowedDomains.add("clerk.dev");
        allowedDomains.add("clerk.com");
        allowedDomains.add("tinfoilsh.github.io");
        allowedDomains.add("cdn.jsdelivr.net");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    result = new Uri[]{Uri.parse(dataString)};
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        WebView.HitTestResult result = chatWebView.getHitTestResult();
        String url = "";
        if (result.getExtra() != null) {
            if (result.getType() == IMAGE_TYPE) {
                url = result.getExtra();
                Uri source = Uri.parse(url);
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
                request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
                request.addRequestHeader("Referer", url);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String filename = URLUtil.guessFileName(url, null, "image/jpeg");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(request);
            } else if (result.getType() == SRC_IMAGE_ANCHOR_TYPE || result.getType() == SRC_ANCHOR_TYPE) {
                if (result.getType() == SRC_IMAGE_ANCHOR_TYPE) {
                    HandlerThread handlerThread = new HandlerThread("HandlerThread");
                    handlerThread.start();
                    Handler backgroundHandler = new Handler(handlerThread.getLooper());
                    Message msg = backgroundHandler.obtainMessage();
                    chatWebView.requestFocusNodeHref(msg);
                    url = (String) msg.getData().get("url");
                } else {
                    url = result.getExtra();
                }
                if (url != null) {
                    String host = Uri.parse(url).getHost();
                    if (host != null) {
                        boolean allowed = isAllowedHost(host);
                        if (!allowed) {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        }
    }

    public String modUserAgent() {
        if (desktopModeEnabled) {
            // Brave/Linux desktop UA — uncommon fingerprint, avoids the crowded
            // Windows-UA crowd and keeps us off "stock Chrome on Windows" lists.
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";
        }
        // Default: generic mobile Chrome on Android (matches the WebView context)
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
    }

    /**
     * Build UserAgentMetadata (Client Hints / navigator.userAgentData) aligned with
     * the current User-Agent string. Without this, WebView ships its factory UA-CH
     * which contradicts our spoofed UA, letting anti-bot (Alibaba AWSC, Cloudflare,
     * etc.) detect the inconsistency and reject sessions.
     */
    private void applyUserAgentMetadata() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
        try {
            boolean mobile = !desktopModeEnabled;
            UserAgentMetadata.BrandVersion chromium = new UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium").setMajorVersion("152").setFullVersion("152.0.0.0").build();
            UserAgentMetadata.BrandVersion chromeBrand = new UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome").setMajorVersion("152").setFullVersion("152.0.0.0").build();
            UserAgentMetadata.BrandVersion notABrand = new UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not(A:Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build();
            UserAgentMetadata meta = new UserAgentMetadata.Builder()
                    .setBrandVersionList(java.util.Arrays.asList(chromium, chromeBrand, notABrand))
                    .setFullVersion("152.0.0.0")
                    .setPlatform(mobile ? "Android" : "Linux")
                    .setPlatformVersion(mobile ? "10.0.0" : "6.8.0")
                    .setArchitecture(mobile ? "" : "x86")
                    .setModel("")
                    .setMobile(mobile)
                    .setBitness(mobile ? 0 : 64)
                    .setWow64(false)
                    .build();
            WebSettingsCompat.setUserAgentMetadata(chatWebSettings, meta);
        } catch (Exception e) {
            android.util.Log.w("tinfoilAssist", "setUserAgentMetadata failed: " + e.getMessage());
        }
    }

    private android.animation.ValueAnimator paddingAnimator;

    private void animatePadding(final android.view.View v,
                                final int targetLeft, final int targetTop,
                                final int targetRight, final int targetBottom) {
        if (paddingAnimator != null && paddingAnimator.isRunning()) {
            paddingAnimator.cancel();
        }
        final int startLeft = v.getPaddingLeft();
        final int startTop = v.getPaddingTop();
        final int startRight = v.getPaddingRight();
        final int startBottom = v.getPaddingBottom();
        if (startLeft == targetLeft && startTop == targetTop
                && startRight == targetRight && startBottom == targetBottom) {
            return;
        }
        paddingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        paddingAnimator.setDuration(300);
        paddingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        paddingAnimator.addUpdateListener(anim -> {
            float t = (Float) anim.getAnimatedValue();
            v.setPadding(
                (int)(startLeft   + (targetLeft   - startLeft)   * t),
                (int)(startTop    + (targetTop    - startTop)    * t),
                (int)(startRight  + (targetRight  - startRight)  * t),
                (int)(startBottom + (targetBottom - startBottom) * t));
        });
        paddingAnimator.start();
    }

    private void applyFullscreen() {
        androidx.core.view.WindowInsetsControllerCompat controller =
            androidx.core.view.WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
            androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (fullscreenEnabled) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Microphone permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Microphone permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Storage permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Storage permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
