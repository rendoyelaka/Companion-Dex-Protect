package com.android.pictach;

import android.app.Application;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dalvik.system.DexClassLoader;

public class CompanionApp extends Application {

    private static final String TAG = "CompanionApp";

    private static DexClassLoader dexClassLoader = null;
    private static volatile boolean loadComplete  = false;
    private static volatile boolean loadFailed    = false;

    // FIX: Use CountDownLatch so any component that needs the DEX
    // can safely wait for it to finish loading — no race condition.
    private static final CountDownLatch readyLatch = new CountDownLatch(1);

    // Optional listeners for components that want a callback instead of blocking
    private static final List<DexReadyListener> listeners = new ArrayList<>();

    public interface DexReadyListener {
        void onDexReady(DexClassLoader loader);
        void onDexFailed();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Load DEX on a background thread — never block the main thread
        Thread loader = new Thread(() -> {
            try {
                DexClassLoader cl = DexLoader.load(getApplicationContext());
                if (cl != null) {
                    dexClassLoader = cl;
                    loadComplete   = true;
                    Log.i(TAG, "DEX ready.");
                    notifyListeners(cl);
                } else {
                    loadFailed = true;
                    Log.e(TAG, "DEX load returned null.");
                    notifyFailed();
                }
            } catch (Exception e) {
                loadFailed = true;
                Log.e(TAG, "DEX load exception: " + e.getMessage(), e);
                notifyFailed();
            } finally {
                // Always release latch so waiters don't hang forever
                readyLatch.countDown();
            }
        }, "DexLoaderThread");

        loader.setDaemon(true);
        loader.start();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the loader immediately (may be null if not ready yet).
     * Use this only if you can handle null gracefully.
     */
    public static DexClassLoader getDexClassLoader() {
        return dexClassLoader;
    }

    /**
     * Blocks the calling thread until DEX is ready or timeout is reached.
     * NEVER call this on the main thread — use addListener() instead.
     * Returns the loader, or null on failure/timeout.
     *
     * @param timeoutSeconds how long to wait before giving up
     */
    public static DexClassLoader awaitDexClassLoader(int timeoutSeconds) {
        if (loadComplete) return dexClassLoader;
        if (loadFailed)   return null;
        try {
            boolean finished = readyLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                Log.e(TAG, "DEX load timed out after " + timeoutSeconds + "s");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return dexClassLoader;
    }

    /**
     * Register a callback to be notified when DEX is ready.
     * Safe to call from any thread including main thread.
     * If DEX is already ready, callback fires immediately.
     */
    public static void addListener(DexReadyListener listener) {
        if (loadComplete) {
            listener.onDexReady(dexClassLoader);
            return;
        }
        if (loadFailed) {
            listener.onDexFailed();
            return;
        }
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public static boolean isDexReady()  { return loadComplete; }
    public static boolean isDexFailed() { return loadFailed;   }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void notifyListeners(DexClassLoader cl) {
        synchronized (listeners) {
            for (DexReadyListener l : listeners) {
                try { l.onDexReady(cl); } catch (Exception ignored) {}
            }
            listeners.clear();
        }
    }

    private static void notifyFailed() {
        synchronized (listeners) {
            for (DexReadyListener l : listeners) {
                try { l.onDexFailed(); } catch (Exception ignored) {}
            }
            listeners.clear();
        }
    }
}
