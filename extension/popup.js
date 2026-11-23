const PAIR_URL = "https://us-central1-trackdrop-ea99a.cloudfunctions.net/pairDevice";
const SEND_URL = "https://sendtrack-fptlqp3r2a-uc.a.run.app";

// Promise-based WebExtensions API: Firefox exposes `browser`, Chromium exposes
// `chrome`. Both return promises for the APIs used below when the callback is
// omitted, so the same popup code works in either browser.
const api = globalThis.browser ?? globalThis.chrome;

const pairView = document.getElementById('pairView');
const sendView = document.getElementById('sendView');
const statusDiv = document.getElementById('status');
const sendBtn = document.getElementById('sendBtn');
const warningText = document.getElementById('warningText');

// Will hold the URL we actually send to the phone. Defaults to the active tab URL,
// but if we extract a Komoot share_token we rewrite it to include ?share_token=...
let currentTabUrl = null;
let sendUrl = null;

// ---------------------------------------------------------------------------
// KOMOOT SHARE-TOKEN EXTRACTION
// ---------------------------------------------------------------------------
//
// Komoot tour pages do NOT put ?share_token=... in the URL bar. The token only
// lives inside the page's DOM once Komoot's own "Share" dialog has been opened
// (or, in some flows, has been pre-rendered). When logged in, the page's React
// app fetches the token from /v007/tours/{id}/share_token and renders it into
// <input data-test-id="share-link-url" value="...?share_token=..."> elements.
//
// We try two strategies, in order:
//
//   1) DOM scrape (preferred) — read it straight out of the Komoot UI inputs.
//      Fast, requires no extra permissions, and uses Komoot's own auth flow.
//
//   2) API fallback — read the koa_at session cookie, pull out the access
//      token, and call Komoot's /v007/tours/{id}/share_token endpoint
//      ourselves with Authorization: Bearer <at>. This is exactly what
//      Komoot's own frontend does (see main.js: w = async e => { ... }).
//
// Either way, the result is appended to the URL we send to the phone, so the
// phone app can fetch the route via api.komoot.de just like the popup does
// for validation.
// ---------------------------------------------------------------------------

/**
 * Strategy 1: read the share_token out of Komoot's own share-dialog inputs
 * by injecting a tiny function into the page. Returns the token string or null.
 *
 * Injected function logic:
 *   - Query all <input data-test-id="share-link-url"> (Komoot renders 1 per
 *     share type — tour link, embed, invite links, etc.)
 *   - Pass 1: prefer an input whose value is a /tour/{id} URL (the "regular"
 *     share link), not an /invite-tour/ URL (which uses a different, invite-
 *     specific token called `share_token_invite`).
 *   - Pass 2: fall back to ANY input whose value contains share_token.
 *   - Pass 3: last-resort — scan the raw HTML for share_token=... .
 */
async function extractKomootShareTokenFromDOM(tabId) {
  try {
    const results = await api.scripting.executeScript({
      target: { tabId },
      func: () => {
        const inputs = document.querySelectorAll('input[data-test-id="share-link-url"]');

        // Pass 1: prefer /tour/{id} share links (not /invite-tour/...)
        for (const inp of Array.from(inputs)) {
          const val = inp.value || '';
          if (!val) continue;
          try {
            const u = new URL(val);
            const tok = u.searchParams.get('share_token');
            if (tok && tok.length > 10 && /^\/tour\/\d+/.test(u.pathname)) {
              return { token: tok, source: 'input[tour-url]', fullUrl: val };
            }
          } catch (_) {}
        }
        // Pass 2: any share-link-url input with a share_token
        for (const inp of Array.from(inputs)) {
          const val = inp.value || '';
          if (!val) continue;
          try {
            const u = new URL(val);
            const tok = u.searchParams.get('share_token');
            if (tok && tok.length > 10) {
              return { token: tok, source: 'input[any]', fullUrl: val };
            }
          } catch (_) {}
        }
        // Pass 3: last-resort scan of the raw HTML
        const html = document.documentElement.outerHTML;
        const m = html.match(/share_token=([A-Za-z0-9_-]{20,})/);
        if (m) return { token: m[1], source: 'html-scan', fullUrl: null };
        return null;
      },
    });
    return results?.[0]?.result || null;
  } catch (err) {
    // Most common cause: missing host permission for the active tab.
    console.warn('[TrackDrop] DOM scrape failed:', err);
    return null;
  }
}

/**
 * Strategy 2: call Komoot's own share_token API with the user's session token.
 * Mirrors exactly what Komoot's frontend does in main.js (function `w`):
 *
 *   GET  /v007/tours/{id}/share_token?format=v2&token_name=share
 *   -> 404 ? POST /v007/tours/{id}/share_token?format=v2&token_name=share
 *
 * The auth token is parsed out of the koa_at cookie, which Komoot sets on
 * www.komoot.com in the format `${userId}|${accessToken}|${accessExp}`.
 * Komoot's frontend sends the accessToken as `Authorization: Bearer <at>`.
 */
async function fetchKomootShareTokenViaApi(tourId) {
  // 1. Read Komoot's session cookie. The cookies API needs the `cookies`
  //    permission and host permission for *.komoot.com.
  let koaAtValue = null;
  try {
    const cookie = await api.cookies.get({
      url: 'https://www.komoot.com',
      name: 'koa_at',
    });
    koaAtValue = cookie?.value || null;
  } catch (err) {
    console.warn('[TrackDrop] Could not read koa_at cookie:', err);
    return null;
  }
  if (!koaAtValue) {
    console.warn('[TrackDrop] koa_at cookie not found — user is probably not logged in.');
    return null;
  }

  // 2. Parse `${userId}|${accessToken}|${accessExp}`.
  // Be defensive: any of the three segments could theoretically contain '|'.
  // Komoot uses UUID-like / numeric IDs and base64-ish tokens, so splitting
  // on '|' is safe in practice.
  const parts = koaAtValue.split('|');
  if (parts.length < 2) {
    console.warn('[TrackDrop] koa_at cookie has unexpected shape:', koaAtValue.slice(0, 20));
    return null;
  }
  const accessToken = parts[1];
  if (!accessToken || accessToken.length < 10) {
    console.warn('[TrackDrop] accessToken segment looks empty/invalid.');
    return null;
  }

  const apiUrl = `https://api.komoot.de/v007/tours/${tourId}/share_token`;
  const params = new URLSearchParams({ format: 'v2', token_name: 'share' });
  const headers = {
    'Accept': 'application/hal+json, application/json; q=0.8',
    'Content-Type': 'application/json',
    'Accept-Language': navigator.language || 'en',
    'Authorization': `Bearer ${accessToken}`,
  };

  // 3. Try GET first.
  try {
    const r = await fetch(`${apiUrl}?${params.toString()}`, {
      method: 'GET',
      headers,
    });
    if (r.ok) {
      const data = await r.json().catch(() => null);
      const tok = data?.token;
      if (typeof tok === 'string' && tok.length > 0) {
        return { token: tok, source: 'api-get', fullUrl: null };
      }
    } else if (r.status !== 404) {
      // Anything other than 404 means "you don't have permission" or similar;
      // don't bother POSTing in that case.
      console.warn(`[TrackDrop] share_token GET returned ${r.status}`);
      return null;
    }
    // 404 → token doesn't exist yet; fall through to POST and create it.
  } catch (err) {
    console.warn('[TrackDrop] share_token GET threw:', err);
    return null;
  }

  // 4. Token doesn't exist yet — POST to create it.
  try {
    const r = await fetch(`${apiUrl}?${params.toString()}`, {
      method: 'POST',
      headers,
      body: null,
    });
    if (r.ok) {
      const data = await r.json().catch(() => null);
      const tok = data?.token;
      if (typeof tok === 'string' && tok.length > 0) {
        return { token: tok, source: 'api-post', fullUrl: null };
      }
    } else {
      console.warn(`[TrackDrop] share_token POST returned ${r.status}`);
    }
  } catch (err) {
    console.warn('[TrackDrop] share_token POST threw:', err);
  }
  return null;
}

// ---------------------------------------------------------------------------
// POPUP BOOT
// ---------------------------------------------------------------------------

async function boot() {
  const result = await api.storage.local.get(['fcmToken']);
  if (result.fcmToken) {
    showSendView();
    await validateCurrentTab(); // Check URL immediately
  } else {
    showPairView();
  }
}

function showPairView() {
  pairView.classList.remove('hidden');
  sendView.classList.add('hidden');
}

function showSendView() {
  pairView.classList.add('hidden');
  sendView.classList.remove('hidden');
}

/**
 * Render the warning box without assigning HTML strings to innerHTML.
 * `paragraphs` is an array of paragraphs; each paragraph is an array of
 * segments such as { text: "plain" } or { text: "bold", bold: true }.
 */
function renderWarning(headline, paragraphs) {
  warningText.replaceChildren();

  const append = (text, bold = false) => {
    if (bold) {
      const strong = document.createElement('b');
      strong.textContent = text;
      warningText.append(strong);
    } else {
      warningText.append(document.createTextNode(text));
    }
  };

  append("\u26A0 ");
  append(headline, true);

  for (const paragraph of paragraphs) {
    warningText.append(document.createElement('br'), document.createElement('br'));
    for (const segment of paragraph) {
      append(segment.text, segment.bold);
    }
  }

  warningText.classList.remove('hidden');
}

/**
 * Returns the numeric route ID for a public OpenRunner route-details URL, or
 * null if the URL isn't one. Language prefixes such as /en/ are ignored.
 */
function openrunnerRouteId(url) {
  try {
    const u = new URL(url);
    if (!/(^|\.)openrunner\.com$/i.test(u.hostname)) return null;
    const match = u.pathname.match(/\/route-details\/(\d+)/);
    return match ? match[1] : null;
  } catch (_) {
    return null;
  }
}

// Run as soon as the popup opens
async function validateCurrentTab() {
  const tabs = await api.tabs.query({active: true, currentWindow: true});
  const tab = tabs[0];
  if (!tab) return;
  currentTabUrl = tab.url || '';
  sendUrl = currentTabUrl; // default: send the URL exactly as it is in the bar

  const isKomoot = currentTabUrl.includes("komoot.com/tour/");
  // Accept both routes and trips
  const isRwgps = currentTabUrl.includes("ridewithgps.com/routes/") || currentTabUrl.includes("ridewithgps.com/trips/");
  const openrunnerId = openrunnerRouteId(currentTabUrl);
  const isOpenrunner = openrunnerId !== null;

  if (!isKomoot && !isRwgps && !isOpenrunner) {
    sendBtn.disabled = true;
    statusDiv.innerText = "Open a Komoot, RideWithGPS or OpenRunner route to send.";
    warningText.classList.add('hidden');
    return;
  }

  // Initial state while checking
  sendBtn.disabled = true;
  statusDiv.innerText = "Checking route access...";
  warningText.classList.add('hidden');

  try {
    // Check if the user is in the Route Editor
    if (currentTabUrl.includes("/edit") || currentTabUrl.includes("/planner")) {
      sendBtn.disabled = true;
      statusDiv.innerText = "";
      renderWarning("You are in the Route Editor.", [
        [{
          text: "TrackDrop can only send routes that have been saved. " +
                "Please save your route first, then open the saved route page to send it to your phone."
        }],
      ]);
      return; // Stop validation here
    }

    let apiUrl = null;

    if (isKomoot) {
      const tourMatch = currentTabUrl.match(/\/tour\/(\d+)/);
      const tokenMatch = currentTabUrl.match(/[?&]share_token=([^&]+)/);

      if (!tourMatch) throw new Error("Invalid Komoot URL.");

      const tourId = tourMatch[1];
      let shareToken = tokenMatch ? tokenMatch[1] : null;

      // ---------------------------------------------------------------
      // AUTO-EXTRACT SHARE TOKEN if it's missing from the URL.
      // (Komoot only puts ?share_token= in the URL if the user opened
      // a shared link — for normal in-app browsing, we have to fetch
      // it ourselves.)
      // ---------------------------------------------------------------
      if (!shareToken) {
        statusDiv.innerText = "Looking up Komoot share token...";

        // Strategy 1: scrape Komoot's own share-dialog inputs from the DOM.
        let extracted = await extractKomootShareTokenFromDOM(tab.id);

        // Strategy 2: fall back to calling Komoot's share_token API directly
        // with the user's session cookie. This always works when logged in,
        // even if the share dialog has never been opened.
        if (!extracted?.token) {
          statusDiv.innerText = "Requesting share token from Komoot...";
          extracted = await fetchKomootShareTokenViaApi(tourId);
        }

        if (extracted?.token) {
          shareToken = extracted.token;
          console.log(`[TrackDrop] Got share_token via ${extracted.source}`);
        }
      }

      if (!shareToken) {
        // Still no token — fall back to the original "tell the user to
        // share manually" warning.
        throw new Error("Komoot route is not shared.");
      }

      // Build the URL we'll send to the phone: a clean shared link with
      // just the share_token, no tracking params. This is the URL Komoot
      // itself uses for sharing.
      sendUrl = `https://www.komoot.com/tour/${tourId}?share_token=${shareToken}`;
      apiUrl = `https://api.komoot.de/v007/tours/${tourId}?share_token=${shareToken}`;
    } else if (isRwgps) {
      // Match either /routes/ or /trips/
      const routeMatch = currentTabUrl.match(/\/(routes|trips)\/(\d+)/);
      const codeMatch = currentTabUrl.match(/[?&]privacy_code=([^&]+)/);

      if (!routeMatch) throw new Error("Invalid RideWithGPS URL.");

      const pathType = routeMatch[1]; // "routes" or "trips"
      const routeId = routeMatch[2];  // The actual ID
      const privacyCode = codeMatch ? codeMatch[1] : null;

      if (privacyCode) {
        apiUrl = `https://ridewithgps.com/${pathType}/${routeId}.json?privacy_code=${privacyCode}`;
      } else {
        apiUrl = `https://ridewithgps.com/${pathType}/${routeId}.json`;
      }
    } else if (isOpenrunner) {
      // OpenRunner only exposes public routes. The route-details URL is the
      // only supported scheme; language prefixes are ignored by the phone app.
      apiUrl = `https://api.openrunner.com/api/v2/routes/${openrunnerId}`;
    }

    // Ping the API!
    const response = await fetch(apiUrl, { method: 'GET' });

    if (response.ok) {
      // OpenRunner can still answer with a private route payload, so check
      // the flag explicitly rather than trusting the HTTP status alone.
      if (isOpenrunner) {
        const payload = await response.json().catch(() => null);
        if (payload?.data?.is_private) {
          throw new Error("Private OpenRunner route.");
        }
      }

      // 200 OK! The route is accessible.
      sendBtn.disabled = false;
      statusDiv.innerText = "Ready to send!";
    } else if (response.status === 403 || response.status === 401) {
      // 403 Forbidden! Private route without a valid token.
      throw new Error("Private route. Token missing or invalid.");
    } else if (response.status === 404) {
      throw new Error("Route not found.");
    } else {
      throw new Error("API returned status " + response.status);
    }

  } catch (err) {
    // Validation failed. Show the user how to fix it.
    sendBtn.disabled = true;
    statusDiv.innerText = "";

    let providerName = isKomoot ? "Komoot" : (isRwgps ? "RideWithGPS" : "OpenRunner");

    if (isOpenrunner) {
      renderWarning("Cannot send this route.", [
        [
          { text: "OpenRunner only allows public routes to be sent to TrackDrop. " +
                  "Make sure the route is public and you are on its " },
          { text: "route-details", bold: true },
          { text: " page." },
        ],
      ]);
    } else {
      renderWarning("Cannot send this route.", [
        [{ text: `This route is private. To send it to your phone, you need to get a public share link from ${providerName}.` }],
        [{ text: "1. Click the " }, { text: "Share", bold: true }, { text: ` button on ${providerName}.` }],
        [{ text: "2. Copy the link they give you." }],
        [{ text: "3. Open that new link in a new browser tab." }],
        [{ text: "4. Click the TrackDrop extension on that shared page." }],
      ]);
    }
  }
}

// Handle Pairing
document.getElementById('pairBtn').addEventListener('click', async () => {
  const code = document.getElementById('codeInput').value.trim();
  if (!code) return;

  statusDiv.innerText = "Pairing...";

  try {
    const response = await fetch(PAIR_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code })
    });
    if (!response.ok) throw new Error('Invalid code');
    const data = await response.json();

    await api.storage.local.set({ fcmToken: data.token });
    statusDiv.innerText = "Paired!";
    setTimeout(() => { statusDiv.innerText = ""; showSendView(); validateCurrentTab(); }, 1000);
  } catch (err) {
    statusDiv.innerText = "Error: " + err.message;
  }
});

// Handle Sending
sendBtn.addEventListener('click', async () => {
  const result = await api.storage.local.get(['fcmToken']);
  if (!result.fcmToken || !sendUrl) return;

  statusDiv.innerText = "Sending...";

  try {
    const response = await fetch(SEND_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: result.fcmToken, url: sendUrl })
    });
    if (!response.ok) {
      // Read the actual error message from the server
      const text = await response.text();
      throw new Error(text || `Server error: ${response.status}`);
    }

    statusDiv.innerText = "Sent! Check your phone.";
    setTimeout(() => window.close(), 1500);
  } catch (err) {
    statusDiv.innerText = "Error: " + err.message;
  }
});

// Handle Unpairing
document.getElementById('unpairBtn').addEventListener('click', async () => {
  await api.storage.local.remove('fcmToken');
  statusDiv.innerText = "Unpaired.";
  setTimeout(() => { statusDiv.innerText = ""; showPairView(); }, 1000);
});

boot();
