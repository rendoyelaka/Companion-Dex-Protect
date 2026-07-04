package com.android.pictach;

import android.app.Application;
import android.util.Log;
import dalvik.system.DexClassLoader;

// Compiles against Application but at runtime Android loads
// this as a subclass of App (which itself extends Application).
// The manifest points to CompanionApp so Android resolves the
// full chain: CompanionApp -> App -> Application at runtime.
public class CompanionApp extends Application {

    private static final String TAG = "CompanionApp";
    private static DexClassLoader dexClassLoader = null;

    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            dexClassLoader = DexLoader.load(getApplicationContext());
            if (dexClassLoader == null) {
                Log.e(TAG, "DEX load failed.");
            } else {
                Log.i(TAG, "DEX ready.");
            }
        }).start();
    }

    public static DexClassLoader getDexClassLoader() {
        return dexClassLoader;
    }
}
