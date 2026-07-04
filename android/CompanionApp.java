package com.android.pictach;

import android.util.Log;
import dalvik.system.DexClassLoader;

// Extends the original App class to preserve all original functionality
public class CompanionApp extends App {

    private static final String TAG = "CompanionApp";
    private static DexClassLoader dexClassLoader = null;

    @Override
    public void onCreate() {
        super.onCreate(); // calls original App.onCreate()

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
