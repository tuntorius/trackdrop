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
function validateCurrentTab() {
  chrome.tabs.query({active: true, currentWindow: true}, (tabs) => {
    if (!tabs[0]) return;
    currentTabUrl = tabs[0].url;
    
    const isKomoot = currentTabUrl.includes("komoot.com/tour/");
    const isRwgps = currentTabUrl.includes("ridewithgps.com/routes/");
    
    if (isKomoot || isRwgps) {
      sendBtn.disabled = false;
      statusDiv.innerText = "Ready to send!";
      
      // Check for share tokens
      try {
        const urlObj = new URL(currentTabUrl);
        let missingToken = false;
        let providerName = "";

        if (isKomoot) {
          providerName = "Komoot";
          if (!urlObj.searchParams.get('share_token')) {
            missingToken = true;
          }
        } else if (isRwgps) {
          providerName = "RideWithGPS";
          if (!urlObj.searchParams.get('privacy_code')) {
            missingToken = true;
          }
        }

        if (missingToken) {
          warningText.innerHTML = "&#9888; <b>Warning:</b> Share token/privacy code is missing. This will only work if the route is public. If it fails, use the " + providerName + " Share button to get a valid link.";
          warningText.classList.remove('hidden');
        } else {
          warningText.classList.add('hidden');
        }
      } catch (e) {
        // Invalid URL format, ignore
      }
    } else {
      sendBtn.disabled = true;
      statusDiv.innerText = "Open a Komoot or RideWithGPS route to send.";
      warningText.classList.add('hidden');
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
      if (!response.ok) throw new Error('Server error: ' + response.status);
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