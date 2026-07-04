# Companion DEX Protect

## Setup Steps

### 1. GitHub repo
- Create repo, add companion.apk to root, push to main
- Go to Settings → Secrets → Actions → New secret
  - Name: DEX_ENCRYPTION_KEY
  - Value: any strong random string e.g. MyStr0ngKey2025!

### 2. Railway server
- Go to railway.app → New Project → Deploy from GitHub
- Set Root Directory to: railway-server
- Add these environment variables in Railway dashboard:
  - DEX_ENCRYPTION_KEY = same value as GitHub secret
  - APP_SECRET_TOKEN = any strong string e.g. AppToken!XYZ789
  - PORT = 3000
- Copy your Railway URL after deploy

### 3. Update DexLoader.java
Edit android/DexLoader.java and fill in:
  RAILWAY_URL       = your Railway URL + /get-key
  ENCRYPTED_DEX_URL = your GitHub repo releases URL
  APP_SECRET_TOKEN  = same value as Railway env var

### 4. Add to your App.java
  public class App extends Application {
      public void onCreate() {
          super.onCreate();
          new Thread(() -> DexLoader.load(this)).start();
      }
  }

### 5. Push APK to trigger automation
  git add companion.apk
  git commit -m "update apk"
  git push origin main

GitHub Actions will auto-encrypt and upload to Releases.
