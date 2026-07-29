package io.github.tinfoilassist;

import android.app.Application;
import android.os.Build;
import android.webkit.WebView;
import android.util.Log;

public class App extends Application {
    private static final String TAG = "tinfoilAssist-App";

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("tinfoil_chat");
                Log.d(TAG, "WebView data directory suffix set to 'tinfoil_chat'");
            } catch (Exception e) {
                Log.e(TAG, "Failed to set DataDirectorySuffix", e);
            }
        }
    }
}
