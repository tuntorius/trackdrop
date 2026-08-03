const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

exports.sendTrack = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST");
  res.set("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  const {token, url} = req.body;

  if (!token || !url) {
    res.status(400).send("Missing token or url");
    return;
  }

  const isKomoot = url.includes("komoot.com/tour/");
  const isRwgps = url.includes("ridewithgps.com/routes/");
  if (!isKomoot && !isRwgps) {
    res.status(400).send("Unsupported URL format");
    return;
  }

  const message = {
    data: {url: url},
    token: token,
  };

  try {
    await admin.messaging().send(message);
    res.status(200).send("Success");
  } catch (error) {
    console.error("Error sending message:", error);
    res.status(500).send("Error sending push");
  }
});

exports.pairDevice = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST");
  res.set("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  const {code} = req.body;
  if (!code) {
    res.status(400).send("Missing code");
    return;
  }

  // Remove hyphen if user typed it (e.g. 123-456 -> 123456)
  const cleanCode = code.replace("-", "");

  try {
    // Look up the code in the Realtime Database
    const ref = admin.database().ref("pairing_codes/" + cleanCode);
    const snapshot = await ref.once("value");

    if (!snapshot.exists()) {
      res.status(404).send("Invalid or expired code");
      return;
    }

    const fcmToken = snapshot.val();
    
    // Delete the code so it can only be used once!
    await ref.remove();

    // Send the token back to the browser extension
    res.status(200).json({token: fcmToken});
  } catch (error) {
    console.error("Pairing error:", error);
    res.status(500).send("Error pairing device");
  }
});
// New Function: App calls this to generate a pairing code
exports.registerPairingCode = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST");
  res.set("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  const {code, token} = req.body;
  if (!code || !token) {
    res.status(400).send("Missing code or token");
    return;
  }

  try {
    // Save to DB using Admin SDK (bypasses security rules)
    await admin.database().ref("pairing_codes/" + code).set(token);
    res.status(200).send("Registered");
  } catch (error) {
    console.error("Error registering code:", error);
    res.status(500).send("Error");
  }
});