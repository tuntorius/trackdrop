# TrackDrop browser extension

A single Manifest V3 extension that works in **Chrome / Chromium** and
**Firefox 140+**.

## One extension, two browsers

The code base is shared. The only Firefox-specific metadata lives in the
`browser_specific_settings` block of `manifest.json`:

- `gecko.id` — required for signing Manifest V3 add-ons for Firefox.
- `gecko.strict_min_version` — 140, the first Firefox version with built-in
  data-collection consent.
- `gecko.data_collection_permissions` — declares that the route URL you choose
  to send is transmitted to the TrackDrop backend (`browsingActivity`).

Chromium ignores `browser_specific_settings` (it may list it as an
"unrecognized manifest key" in `chrome://extensions`; that is expected and does
not affect the extension).

The popup uses `browser.*` when available and falls back to `chrome.*`, so the
same `popup.js` runs in both browsers.

## Install

### Chrome / Chromium

1. Open `chrome://extensions/`.
2. Enable **Developer mode** (top-right toggle).
3. Click **Load unpacked** and select this `extension/` folder.
4. Open the extension, enter the pairing code shown in the TrackDrop app, and
   you're paired.

### Firefox

Temporary install (disappears when Firefox restarts):

1. Open `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on…**.
3. Select `extension/manifest.json`.
4. Open the extension, enter the pairing code shown in the TrackDrop app, and
   you're paired.

Firefox grants the requested host permissions at install time. You can review
or revoke them under **about:addons → TrackDrop → Permissions**. If they are
revoked, the extension cannot read provider data until they are enabled again.

Permanent install: sign and package the add-on through
[addons.mozilla.org](https://addons.mozilla.org/developers/) or with
`web-ext`:

```bash
npx web-ext sign --source-dir extension --api-key <key> --api-secret <secret>
```

## Package

```bash
# Firefox / AMO
npx web-ext build --source-dir extension

# Chrome / Chrome Web Store (or zip the folder)
chrome --pack-extension=extension
```

## Permissions

| Permission | Why |
| --- | --- |
| `activeTab`, `scripting` | Scrape the Komoot share token from the active route page. |
| `cookies` + `www.komoot.com` | Read Komoot's `koa_at` session cookie to request a share token when the DOM has none. |
| `api.komoot.de`, `ridewithgps.com`, `api.openrunner.com` | Check that the route is accessible and read its metadata. |
| `cloudfunctions.net`, `run.app` | Pair the extension and send the route URL to the TrackDrop backend. |
| `storage` | Remember the paired device's push token. |

The extension sends only the route URL you explicitly choose to send. See the
repository's [`privacy-policy.md`](../privacy-policy.md) for details.

## Notes

- The Firefox add-on ID is `trackdrop@tuntori.com`. Change it in
  `manifest.json` if you plan to publish under a different AMO account.
- `popup.js`/`popup.html` are shared by both browsers; do not add
  browser-specific copies.
