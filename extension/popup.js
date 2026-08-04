const PAIR_URL = "https://us-central1-trackdrop-ea99a.cloudfunctions.net/pairDevice"
const SEND_URL = "https://sendtrack-fptlqp3r2a-uc.a.run.app";

const pairView = document.getElementById('pairView');
const sendView = document.getElementById('sendView');
const statusDiv = document.getElementById('status');
const sendBtn = document.getElementById('sendBtn');
const warningText = document.getElementById('warningText');

let currentTabUrl = null;

// Check if already paired on load
chrome.storage.local.get(['fcmToken'], (result) => {
  if (result.fcmToken) {
    showSendView();
    validateCurrentTab(); // Check URL immediately
  } else {
    showPairView();
  }
});

function showPairView() {
  pairView.classList.remove('hidden');
  sendView.classList.add('hidden');
}

function showSendView() {
  pairView.classList.add('hidden');
  sendView.classList.remove('hidden');
}

// Run as soon as the popup opens
async function validateCurrentTab() {
  chrome.tabs.query({active: true, currentWindow: true}, async (tabs) => {
    if (!tabs[0]) return;
    currentTabUrl = tabs[0].url;
    
    const isKomoot = currentTabUrl.includes("komoot.com/tour/");
    // Accept both routes and trips
    const isRwgps = currentTabUrl.includes("ridewithgps.com/routes/") || currentTabUrl.includes("ridewithgps.com/trips/");
    
    if (!isKomoot && !isRwgps) {
      sendBtn.disabled = true;
      statusDiv.innerText = "Open a Komoot or RideWithGPS route to send.";
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
        warningText.innerHTML = "&#9888; <b>You are in the Route Editor.</b><br><br>" +
                                "TrackDrop can only send routes that have been saved. " +
                                "Please save your route first, then open the saved route page to send it to your phone.";
        warningText.classList.remove('hidden');
        return; // Stop validation here
      }

      let apiUrl = null;
      let needsToken = false;

      if (isKomoot) {
        const tourMatch = currentTabUrl.match(/\/tour\/(\d+)/);
        const tokenMatch = currentTabUrl.match(/[?&]share_token=([^&]+)/);
        
        if (!tourMatch) throw new Error("Invalid Komoot URL.");
        
        const tourId = tourMatch[1];
        const shareToken = tokenMatch ? tokenMatch[1] : null;
        
        if (!shareToken) {
          needsToken = true;
        } else {
          apiUrl = `https://api.komoot.de/v007/tours/${tourId}?share_token=${shareToken}`;
        }
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
      }

      if (needsToken) {
        throw new Error("Komoot route is not shared.");
      }

      // Ping the API!
      const response = await fetch(apiUrl, { method: 'GET' });

      if (response.ok) {
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
      
      let providerName = isKomoot ? "Komoot" : "RideWithGPS";
      
      warningText.innerHTML = `&#9888; <b>Cannot send this route.</b><br><br>` +
                              `This route is private. To send it to your phone, you need to get a public share link from ${providerName}.<br><br>` +
                              `1. Click the <b>Share</b> button on ${providerName}.<br>` +
                              `2. Copy the link they give you.<br>` +
                              `3. Open that new link in a new browser tab.<br>` +
                              `4. Click the TrackDrop extension on that shared page.`;
      warningText.classList.remove('hidden');
    }
  });
}

// Handle Pairing
document.getElementById('pairBtn').addEventListener('click', () => {
  const code = document.getElementById('codeInput').value.trim();
  if (!code) return;
  
  statusDiv.innerText = "Pairing...";
  
  fetch(PAIR_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: code })
  })
  .then(response => {
    if (!response.ok) throw new Error('Invalid code');
    return response.json();
  })
  .then(data => {
    chrome.storage.local.set({ fcmToken: data.token }, () => {
      statusDiv.innerText = "Paired!";
      setTimeout(() => { statusDiv.innerText = ""; showSendView(); validateCurrentTab(); }, 1000);
    });
  })
  .catch(err => {
    statusDiv.innerText = "Error: " + err.message;
  });
});

// Handle Sending
sendBtn.addEventListener('click', () => {
  chrome.storage.local.get(['fcmToken'], (result) => {
    if (!result.fcmToken || !currentTabUrl) return;

    statusDiv.innerText = "Sending...";

    fetch(SEND_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: result.fcmToken, url: currentTabUrl })
    })
    .then(response => {
      if (!response.ok) {
        // Read the actual error message from the server
        return response.text().then(text => { 
          throw new Error(text || `Server error: ${response.status}`) 
        });
      }
      return response.text();
    })
    .then(text => {
      statusDiv.innerText = "Sent! Check your phone.";
      setTimeout(() => window.close(), 1500);
    })
    .catch(err => {
      statusDiv.innerText = "Error: " + err.message;
    });
  });
});

// Handle Unpairing
document.getElementById('unpairBtn').addEventListener('click', () => {
  chrome.storage.local.remove('fcmToken', () => {
    statusDiv.innerText = "Unpaired.";
    setTimeout(() => { statusDiv.innerText = ""; showPairView(); }, 1000);
  });
});