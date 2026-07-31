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

import java.util.ArrayList;

public class MainActivity extends Activity {

    private WebView chatWebView = null;
    private ImageButton btnMenuToggle = null;
    private ImageButton btnReload = null;
    private ImageButton btnRestrict = null;
    private ImageButton btnClearData = null;
    private ImageButton btnAbout = null;
    private LinearLayout menuBar = null;
    private boolean menuVisible = false;
    private WebSettings chatWebSettings = null;
    private CookieManager chatCookieManager = null;
    private final Context context = this;
    private SwipeTouchListener swipeTouchListener;
    private static final String TAG = "tinfoilAssist";
    private static final String URL_TO_LOAD = "https://chat.tinfoil.sh/";
    private static boolean restricted = true;

    private static final ArrayList<String> allowedDomains = new ArrayList<>();

    private ValueCallback<Uri[]> mUploadMessage;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;

    // JavaScript code to disable battery, sensor, and hardware APIs inside the WebView
    private static final String HARDENING_JS =
            "javascript:(function() {" +
            "  try {" +
            "    if (navigator.getBattery) { navigator.getBattery = function() { return Promise.reject(new Error('Battery API disabled for privacy')); }; }" +
            "    Object.defineProperty(window, 'DeviceOrientationEvent', { value: undefined, configurable: false });" +
            "    Object.defineProperty(window, 'DeviceMotionEvent', { value: undefined, configurable: false });" +
            "    if (navigator.vibrate) { navigator.vibrate = function() { return false; }; }" +
            "    if (navigator.connection) { Object.defineProperty(navigator, 'connection', { value: undefined }); }" +
            "    if (navigator.geolocation) {" +
            "      navigator.geolocation.getCurrentPosition = function(s, e) { if (e) e({code: 1, message: 'Geolocation disabled'}); };" +
            "      navigator.geolocation.watchPosition = function() { return 0; };" +
            "    }" +
            "  } catch(e) { console.log('Hardening error:', e); }" +
            "})();";

    @Override
    protected void onPause() {
        if (chatCookieManager != null) chatCookieManager.flush();
        swipeTouchListener = null;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateRestrictIcon();

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

        // Toggle restricted mode
        btnRestrict.setOnClickListener(v -> {
            restricted = !restricted;
            updateRestrictIcon();
            if (restricted) {
                Toast.makeText(context, R.string.urls_restricted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, R.string.all_urls, Toast.LENGTH_SHORT).show();
            }
            chatWebSettings.setUserAgentString(modUserAgent());
            chatWebView.reload();
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

    private void updateRestrictIcon() {
        btnRestrict.setImageDrawable(getDrawable(restricted ? R.drawable.ic_lock : R.drawable.ic_lock_open));
    }

    private int getArrowWidth() {
        ImageButton arrow = menuBar.findViewById(R.id.btnMenuToggleInner);
        return arrow.getWidth();
    }

    private void showMenu() {
        menuVisible = true;
        // Show action buttons immediately (no fade)
        int[] btnIds = {R.id.btnReload, R.id.btnRestrict, R.id.btnClearData, R.id.btnAbout};
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
                int[] btnIds = {R.id.btnReload, R.id.btnRestrict, R.id.btnClearData, R.id.btnAbout};
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
        restricted = true;

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
        setContentView(R.layout.activity_main);

        chatWebView = findViewById(R.id.chatWebView);
        registerForContextMenu(chatWebView);
        btnMenuToggle = findViewById(R.id.btnMenuToggleInner);
        btnReload = findViewById(R.id.btnReload);
        btnRestrict = findViewById(R.id.btnRestrict);
        btnClearData = findViewById(R.id.btnClearData);
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
                view.loadUrl(HARDENING_JS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.loadUrl(HARDENING_JS);
                if (chatCookieManager != null) {
                    chatCookieManager.flush();
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
                    return new WebResourceResponse("text/javascript", "UTF-8", null);
                }

                boolean allowed = false;
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String domain : allowedDomains) {
                        if (host.endsWith(domain)) {
                            allowed = true;
                            break;
                        }
                    }
                }

                if (!allowed) {
                    Log.d(TAG, "[shouldInterceptRequest][BLOCKED] " + host);
                    return new WebResourceResponse("text/javascript", "UTF-8", null);
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

                boolean allowed = false;
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String domain : allowedDomains) {
                        if (host.endsWith(domain)) {
                            allowed = true;
                            break;
                        }
                    }
                }

                if (!allowed) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][BLOCKED] " + host);
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
        chatCookieManager.removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        chatWebView.loadUrl(URL_TO_LOAD);
    }

    private static void initURLs() {
        // Domains permitted for Tinfoil Chat, Clerk, and authentication providers
        allowedDomains.add("tinfoil.sh");
        allowedDomains.add("chat.tinfoil.sh");
        allowedDomains.add("clerk.tinfoil.sh");
        allowedDomains.add("verification-center.tinfoil.sh");
        allowedDomains.add("clerk.accounts.dev");
        allowedDomains.add("clerk.com"); // Clerk central domain
        allowedDomains.add("tinfoilsh.github.io");
        allowedDomains.add("cdn.jsdelivr.net");

        // Google resources (fonts, icons, JS libraries) — NOT auth
        allowedDomains.add("gstatic.com");
        allowedDomains.add("googleapis.com");

        // Apple resources — NOT auth
        allowedDomains.add("apple.com");
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
                        boolean allowed = false;
                        for (String domain : allowedDomains) {
                            if (host.endsWith(domain)) {
                                allowed = true;
                                break;
                            }
                        }
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
        String newPrefix = "Mozilla/5.0 (X11; Linux " + System.getProperty("os.arch") + ")";
        String newUserAgent = WebSettings.getDefaultUserAgent(context);
        try {
            String prefix = newUserAgent.substring(0, newUserAgent.indexOf(")") + 1);
            newUserAgent = newUserAgent.replace(prefix, newPrefix);
        } catch (Exception e) {
            Log.e(TAG, "Error modifying User-Agent", e);
        }
        return newUserAgent;
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
