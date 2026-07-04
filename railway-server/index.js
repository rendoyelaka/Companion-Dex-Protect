const express = require("express");
const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const DEX_ENCRYPTION_KEY = process.env.DEX_ENCRYPTION_KEY;
const APP_SECRET_TOKEN   = process.env.APP_SECRET_TOKEN;
const PACKAGE_NAME       = "com.android.pictach";

if (!DEX_ENCRYPTION_KEY || !APP_SECRET_TOKEN) {
  console.error("ERROR: DEX_ENCRYPTION_KEY and APP_SECRET_TOKEN must be set.");
  process.exit(1);
}

app.get("/", (req, res) => {
  res.json({ status: "ok" });
});

app.post("/get-key", (req, res) => {
  const token = req.headers["x-app-token"];
  const pkg   = req.headers["x-package"];

  if (!token || token !== APP_SECRET_TOKEN) {
    console.warn(`[REJECTED] Bad token from ${req.ip}`);
    return res.status(403).json({ error: "Forbidden" });
  }

  if (!pkg || pkg !== PACKAGE_NAME) {
    console.warn(`[REJECTED] Wrong package: ${pkg}`);
    return res.status(403).json({ error: "Forbidden" });
  }

  console.log(`[GRANTED] Key delivered to ${req.ip}`);
  return res.json({ key: DEX_ENCRYPTION_KEY });
});

app.listen(PORT, () => {
  console.log(`Key server running on port ${PORT}`);
});
