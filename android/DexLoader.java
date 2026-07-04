package com.android.pictach;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
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

    private static final String TAG             = "DexLoader";
    private static final String KEYSTORE_ALIAS  = "DexPartA";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // ── UPDATE THESE 3 VALUES ────────────────────────────────────────────────
    private static final String RAILWAY_URL       = "https://companion-dex-protect-production.up.railway.app/get-key";
    private static final String ENCRYPTED_DEX_URL = "https://github.com/rendoyelaka/Companion-Dex-Protect/releases/latest/download/encrypted_classes.dex";
    private static final String APP_SECRET_TOKEN  = "Companiontoken$24563";
    // ────────────────────────────────────────────────────────────────────────

    private static final String PACKAGE_NAME = "com.android.pictach";
    private static final String DEX_FILENAME = "payload.dex";
    private static final String ENC_FILENAME = "payload.enc";
    private static final String PART_A_FILE  = "pref_a.dat"; // Part A wrapped blob

    public static DexClassLoader load(Context context) {
        try {
            // 1. Ensure Part A exists in Keystore (generated once per device)
            ensurePartA(context);

            // 2. Fetch Part B from Railway
            String partB = fetchPartB();
            if (partB == null) { Log.e(TAG, "Failed to fetch Part B"); return null; }

            // 3. Combine Part A + Part B → full AES key
            byte[] fullKey = combineKeys(context, partB);
            if (fullKey == null) { Log.e(TAG, "Failed to combine keys"); return null; }

            // 4. Download encrypted DEX from GitHub Release
            File encFile = new File(context.getFilesDir(), ENC_FILENAME);
            downloadFile(ENCRYPTED_DEX_URL, encFile);

            // 5. Decrypt → write to private dir
            File dexFile = new File(context.getFilesDir(), DEX_FILENAME);
            decryptFile(encFile, dexFile, fullKey);
            encFile.delete();

            // 6. Load via DexClassLoader
            File optDir = context.getDir("outdex", Context.MODE_PRIVATE);
            DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir.getAbsolutePath(),
                null,
                context.getClassLoader()
            );

            // 7. Delete decrypted DEX immediately after loading
            dexFile.delete();
            Arrays.fill(fullKey, (byte) 0); // wipe key from memory

            Log.i(TAG, "DEX loaded successfully");
            return loader;

        } catch (Exception e) {
            Log.e(TAG, "DexLoader error: " + e.getMessage());
            return null;
        }
    }

    // ── Part A: Android Keystore ─────────────────────────────────────────────

    private static void ensurePartA(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) return;

        // Generate AES key inside Keystore (never leaves hardware)
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        kg.generateKey();

        // Encrypt a fixed seed with this Keystore key → store blob as Part A reference
        SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv  = cipher.getIV();
        byte[] enc = cipher.doFinal("PART_A_SEED".getBytes(StandardCharsets.UTF_8));

        // Store IV + encrypted blob to private file
        File f = new File(context.getFilesDir(), PART_A_FILE);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(iv.length);
            fos.write(iv);
            fos.write(enc);
        }
    }

    private static byte[] getPartABytes(Context context) throws Exception {
        File f = new File(context.getFilesDir(), PART_A_FILE);
        byte[] blob;
        try (FileInputStream fis = new FileInputStream(f)) {
            blob = fis.readAllBytes();
        }
        int ivLen = blob[0];
        byte[] iv  = Arrays.copyOfRange(blob, 1, 1 + ivLen);
        byte[] enc = Arrays.copyOfRange(blob, 1 + ivLen, blob.length);

        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(enc); // returns "PART_A_SEED" bytes
    }

    // ── Key Combine ──────────────────────────────────────────────────────────

    private static byte[] combineKeys(Context context, String partB) throws Exception {
        byte[] partABytes = getPartABytes(context);
        byte[] partBBytes = partB.getBytes(StandardCharsets.UTF_8);

        // XOR Part A seed hash with Part B hash → 32-byte AES key
        byte[] hashA = MessageDigest.getInstance("SHA-256").digest(partABytes);
        byte[] hashB = MessageDigest.getInstance("SHA-256").digest(partBBytes);
        byte[] combined = new byte[32];
        for (int i = 0; i < 32; i++) combined[i] = (byte) (hashA[i] ^ hashB[i]);
        return combined;
    }

    // ── Railway: Fetch Part B ────────────────────────────────────────────────

    private static String fetchPartB() throws Exception {
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

        if (conn.getResponseCode() != 200) return null;

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        return new JSONObject(sb.toString()).getString("key");
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
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ── Decrypt ──────────────────────────────────────────────────────────────

    private static void decryptFile(File encFile, File outFile, byte[] keyBytes) throws Exception {
        byte[] encData = java.nio.file.Files.readAllBytes(encFile.toPath());

        // First 16 bytes = IV (written by encrypt_dex.py)
        byte[] iv         = Arrays.copyOfRange(encData, 0, 16);
        byte[] ciphertext = Arrays.copyOfRange(encData, 16, encData.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(ciphertext);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(decrypted);
        }
    }
}
