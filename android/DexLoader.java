package com.android.pictach;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dalvik.system.DexClassLoader;

public class DexLoader {

    private static final String TAG              = "DexLoader";
    private static final String KEYSTORE_ALIAS   = "DexPartA";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // ── UPDATE THESE 3 VALUES ────────────────────────────────────────────────
    private static final String RAILWAY_URL       = "https://companion-dex-protect-production.up.railway.app/get-key";
    private static final String ENCRYPTED_DEX_URL = "https://github.com/rendoyelaka/Companion-Dex-Protect/releases/latest/download/encrypted_classes.dex";
    private static final String APP_SECRET_TOKEN  = "Companiontoken$24563";
    // ────────────────────────────────────────────────────────────────────────

    private static final String PACKAGE_NAME = "com.android.pictach";
    private static final String DEX_FILENAME = "payload.dex";
    private static final String ENC_FILENAME = "payload.enc";
    private static final String PART_A_FILE  = "pref_a.dat";

    // FIX 1: Return loader AND keep dex file alive until caller is done.
    // Caller must call cleanup(context) after all classes are loaded.
    public static DexClassLoader load(Context context) {
        try {
            // 1. Ensure Part A exists in Keystore (generated once per device)
            ensurePartA(context);

            // 2. Fetch Part B from Railway
            String partB = fetchPartB();
            if (partB == null) {
                Log.e(TAG, "Failed to fetch Part B");
                return null;
            }

            // 3. Combine Part A + Part B → full AES key
            byte[] fullKey = combineKeys(context, partB);
            if (fullKey == null) {
                Log.e(TAG, "Failed to combine keys");
                return null;
            }

            // 4. Download encrypted DEX from GitHub Release
            File encFile = new File(context.getFilesDir(), ENC_FILENAME);
            downloadFile(ENCRYPTED_DEX_URL, encFile);

            // 5. Decrypt → write to private dir
            File dexFile = new File(context.getFilesDir(), DEX_FILENAME);
            decryptFile(encFile, dexFile, fullKey);
            encFile.delete();

            // Wipe key from memory immediately after decrypt
            Arrays.fill(fullKey, (byte) 0);

            // 6. Load via DexClassLoader
            File optDir = context.getDir("outdex", Context.MODE_PRIVATE);
            DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir.getAbsolutePath(),
                null,
                context.getClassLoader()
            );

            // FIX 2: Do NOT delete dex here. DexClassLoader uses lazy loading —
            // deleting now causes ClassNotFoundException when classes are first used.
            // Call cleanup(context) only AFTER you have instantiated all needed classes.
            Log.i(TAG, "DEX loaded successfully");
            return loader;

        } catch (Exception e) {
            Log.e(TAG, "DexLoader error: " + e.getMessage(), e);
            return null;
        }
    }

    // Call this after you have fully initialized all classes from the loader
    public static void cleanup(Context context) {
        try {
            new File(context.getFilesDir(), DEX_FILENAME).delete();
            new File(context.getFilesDir(), ENC_FILENAME).delete();
            Log.i(TAG, "DEX cleanup done");
        } catch (Exception e) {
            Log.w(TAG, "Cleanup error: " + e.getMessage());
        }
    }

    // ── Part A: Android Keystore ─────────────────────────────────────────────

    private static void ensurePartA(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) return;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        kg.generateKey();

        SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv  = cipher.getIV();
        byte[] enc = cipher.doFinal("PART_A_SEED".getBytes(StandardCharsets.UTF_8));

        File f = new File(context.getFilesDir(), PART_A_FILE);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(iv.length);
            fos.write(iv);
            fos.write(enc);
        }
    }

    private static byte[] getPartABytes(Context context) throws Exception {
        File f = new File(context.getFilesDir(), PART_A_FILE);

        // FIX 3: readAllBytes() requires API 33+. Use manual read for compatibility
        // down to API 21 (the app's min SDK).
        byte[] blob;
        try (FileInputStream fis = new FileInputStream(f)) {
            blob = readAllBytesCompat(fis);
        }

        int ivLen = blob[0] & 0xFF;
        byte[] iv  = Arrays.copyOfRange(blob, 1, 1 + ivLen);
        byte[] enc = Arrays.copyOfRange(blob, 1 + ivLen, blob.length);

        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(enc);
    }

    // ── Key Combine ──────────────────────────────────────────────────────────

    private static byte[] combineKeys(Context context, String partB) throws Exception {
        byte[] partABytes = getPartABytes(context);
        byte[] partBBytes = partB.getBytes(StandardCharsets.UTF_8);

        byte[] hashA = MessageDigest.getInstance("SHA-256").digest(partABytes);
        byte[] hashB = MessageDigest.getInstance("SHA-256").digest(partBBytes);
        byte[] combined = new byte[32];
        for (int i = 0; i < 32; i++) combined[i] = (byte) (hashA[i] ^ hashB[i]);
        return combined;
    }

    // ── Railway: Fetch Part B ────────────────────────────────────────────────

    private static String fetchPartB() {
        // FIX 4: Retry up to 3 times with backoff in case of transient network failure
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                URL url = new URL(RAILWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-app-token", APP_SECRET_TOKEN);
                conn.setRequestProperty("x-package", PACKAGE_NAME);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "fetchPartB HTTP " + code + " attempt " + attempt);
                    conn.disconnect();
                    Thread.sleep(attempt * 2000L);
                    continue;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                return new JSONObject(sb.toString()).getString("key");

            } catch (Exception e) {
                Log.w(TAG, "fetchPartB attempt " + attempt + " failed: " + e.getMessage());
                try { Thread.sleep(attempt * 2000L); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private static void downloadFile(String fileUrl, File dest) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    // ── Decrypt ──────────────────────────────────────────────────────────────

    private static void decryptFile(File encFile, File outFile, byte[] keyBytes) throws Exception {
        // FIX 5: Files.readAllBytes() also requires API 26+. Use compat read.
        byte[] encData;
        try (FileInputStream fis = new FileInputStream(encFile)) {
            encData = readAllBytesCompat(fis);
        }

        byte[] iv         = Arrays.copyOfRange(encData, 0, 16);
        byte[] ciphertext = Arrays.copyOfRange(encData, 16, encData.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(ciphertext);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(decrypted);
        }
    }

    // ── Compat Helpers ───────────────────────────────────────────────────────

    // Replacement for InputStream.readAllBytes() which requires API 33+
    // This works from API 21 (Android 5.0) onwards
    private static byte[] readAllBytesCompat(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }
}
