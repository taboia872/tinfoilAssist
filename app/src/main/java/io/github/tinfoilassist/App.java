package io.github.tinfoilassist;

import android.app.Application;

public class App extends Application {
    // setDataDirectorySuffix removed: it was preventing IndexedDB from persisting
    // across app restarts. The default data directory works correctly for single-process apps.
    @Override
    public void onCreate() {
        super.onCreate();
    }
}
