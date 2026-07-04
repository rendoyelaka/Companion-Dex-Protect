package com.android.pictach;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import dalvik.system.DexClassLoader;

public class DexLoader {

    private static final String TAG = "DexLoader";

    // ── UPDATE THESE 3 VALUES ────────────────────────────────────────────────
    private static final String RAILWAY_URL       = "https://companion-dex-protect-production.up.railway.app/get-key";
    private static final String ENCRYPTED_DEX_URL = "https://github.com/rendoyelaka/Companion-Dex-Protect/releases/latest/download/encrypted_classes.dex";
    private static final String APP_SECRET_TOKEN  = "Companiontoken$24563";
    // ────────────────────────────────────────────────────────────────────────

    private static final String PACKAGE_NAME = "com.android.pictach";
    private static final String DEX_FILENAME = "payload.dex";
    private static final String ENC_FILENAME = "payload.enc";

    public static DexClassLoader load(Context context) {
        try {
            String key = fetchKey();
            if (key == null) { Log.e(TAG, "Failed to fetch key"); return null; }

            File encFile = new File(context.getFilesDir(), ENC_FILENAME);
            downloadFile(ENCRYPTED_DEX_URL, encFile);

            File dexFile = new File(context.getFilesDir(), DEX_FILENAME);
            decryptFile(encFile, dexFile, key);
            encFile.delete();

            File optDir = context.getDir("outdex", Context.MODE_PRIVATE);
            DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir.getAbsolutePath(),
                null,
                context.getClassLoader()
            );

            dexFile.delete();
            Log.i(TAG, "DEX loaded successfully");
            return loader;

        } catch (Exception e) {
            Log.e(TAG, "DexLoader error: " + e.getMessage());
            return null;
        }
    }

    private static String fetchKey() throws Exception {
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

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        return new JSONObject(sb.toString()).getString("key");
    }

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

    private static void decryptFile(File encFile, File outFile, String rawKey) throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));

        byte[] encData = java.nio.file.Files.readAllBytes(encFile.toPath());
        byte[] decrypted = cipher.doFinal(encData);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(decrypted);
        }
    }
}
