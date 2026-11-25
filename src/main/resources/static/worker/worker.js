import { apiFetch, ApiError } from '/shared/api.js';

const views = {
  loading: document.getElementById('view-loading'),
  welcome: document.getElementById('view-welcome'),
  invite: document.getElementById('view-invite'),
  consent: document.getElementById('view-consent'),
  manual: document.getElementById('view-manual-fallback'),
  camera: document.getElementById('view-camera'),
  home: document.getElementById('view-home')
};

const state = {
  biometricsConfig: null,
  invitationCode: '',
  consentVersion: '',
  fullName: '',
  stream: null
};

function showView(name) {
  Object.entries(views).forEach(([key, el]) => {
    el.hidden = key !== name;
  });
}

function setBanner(id, message) {
  const el = document.getElementById(id);
  if (!message) {
    el.hidden = true;
    el.textContent = '';
  } else {
    el.textContent = message;
    el.hidden = false;
  }
}

function stopCamera() {
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
  const video = document.getElementById('camera-video');
  video.srcObject = null;
}

async function startCamera() {
  setBanner('camera-error', '');
  const placeholder = document.getElementById('camera-placeholder');
  placeholder.hidden = true;
  stopCamera();

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    placeholder.hidden = false;
    setBanner('camera-error', 'Camera is unavailable in this browser or requires HTTPS.');
    return;
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 800 } },
      audio: false
    });
    state.stream = stream;
    const video = document.getElementById('camera-video');
    video.srcObject = stream;
  } catch (err) {
    placeholder.hidden = false;
    if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
      setBanner('camera-error', 'Camera access is blocked. Allow camera access in your browser settings and tap Retry Camera.');
    } else {
      setBanner('camera-error', 'Could not open the front camera. Check that no other app is using it.');
    }
  }
}

function captureCanvasBase64() {
  const video = document.getElementById('camera-video');
  if (!state.stream || video.videoWidth === 0) {
    return null;
  }
  const canvas = document.getElementById('camera-canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const ctx = canvas.getContext('2d');
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.85);
}

function buildSyntheticMockPayload() {
  const marker = `MOCK_FACE_${state.invitationCode}_${Date.now()}`;
  return btoa(marker);
}

function setupVoiceInput() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const btn = document.getElementById('btn-voice-name');
  if (!SpeechRecognition) {
    btn.hidden = true;
    return;
  }
  btn.hidden = false;
  btn.addEventListener('click', () => {
    const rec = new SpeechRecognition();
    rec.lang = 'en-IN';
    rec.interimResults = false;
    rec.onresult = (event) => {
      const transcript = event.results?.[0]?.[0]?.transcript;
      if (transcript) {
        document.getElementById('input-full-name').value = transcript.trim();
      }
    };
    rec.start();
  });
}

function showHome(worker, justRegistered = false) {
  stopCamera();
  document.getElementById('reg-success-banner').hidden = !justRegistered;
  const firstName = (worker.fullName || 'Worker').split(' ')[0];
  document.getElementById('home-greeting').textContent = `Welcome, ${firstName}`;
  document.getElementById('home-worker-meta').textContent =
    `${worker.fullName} (${worker.workerCode})${worker.teamName ? ' · ' + worker.teamName : ''}`;
  showView('home');
}

async function submitRegistration(imageBase64) {
  const btn = document.getElementById('btn-capture-register');
  btn.disabled = true;
  setBanner('camera-error', '');
  try {
    const result = await apiFetch('/api/v1/workers/register', {
      method: 'POST',
      body: {
        invitationCode: state.invitationCode,
        fullName: state.fullName,
        consentGiven: true,
        consentVersion: state.consentVersion,
        imageBase64,
        deviceLabel: navigator.userAgent.slice(0, 80)
      }
    });
    showHome(result, true);
  } catch (err) {
    setBanner('camera-error', err.message || 'Registration failed. Please try again.');
  } finally {
    btn.disabled = false;
  }
}

async function init() {
  setupVoiceInput();

  try {
    state.biometricsConfig = await apiFetch('/api/v1/biometrics/config');
    if (state.biometricsConfig.mockMode) {
      document.getElementById('mock-mode-badge').hidden = false;
      document.getElementById('btn-mock-capture').hidden = false;
    }
  } catch {
    // Non-fatal on initial load
  }

  try {
    const me = await apiFetch('/api/v1/workers/me');
    showHome(me, false);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      showView('welcome');
    } else {
      showView('welcome');
    }
  }
}

document.getElementById('btn-start-register').addEventListener('click', () => {
  setBanner('invite-error', '');
  showView('invite');
});

document.getElementById('btn-invite-back').addEventListener('click', () => showView('welcome'));

document.getElementById('form-invite').addEventListener('submit', async (e) => {
  e.preventDefault();
  setBanner('invite-error', '');
  const code = document.getElementById('input-invite-code').value.trim();
  try {
    const info = await apiFetch('/api/v1/worker-invitations/validate', {
      method: 'POST',
      body: { invitationCode: code }
    });
    state.invitationCode = code;
    state.consentVersion = info.consentVersion;
    document.getElementById('invite-worker-meta').textContent =
      `Worker code: ${info.workerCode}${info.teamName ? ' · Team: ' + info.teamName : ''}`;
    document.getElementById('input-full-name').value = info.suggestedName || '';
    document.getElementById('consent-notice-text').textContent = info.consentNotice;
    document.getElementById('checkbox-consent').checked = false;
    setBanner('consent-error', '');
    showView('consent');
  } catch (err) {
    setBanner('invite-error', err.message || 'Invalid invitation code.');
  }
});

document.getElementById('btn-consent-continue').addEventListener('click', async () => {
  setBanner('consent-error', '');
  const name = document.getElementById('input-full-name').value.trim();
  const consented = document.getElementById('checkbox-consent').checked;
  if (!name) {
    setBanner('consent-error', 'Please enter your full name.');
    return;
  }
  if (!consented) {
    setBanner('consent-error', 'Please check the consent box to continue, or choose manual attendance below.');
    return;
  }
  state.fullName = name;
  showView('camera');
  await startCamera();
});

document.getElementById('btn-decline-consent').addEventListener('click', async () => {
  const name = document.getElementById('input-full-name').value.trim() || 'Worker';
  try {
    await apiFetch('/api/v1/workers/register', {
      method: 'POST',
      body: {
        invitationCode: state.invitationCode,
        fullName: name,
        consentGiven: false,
        consentVersion: state.consentVersion
      }
    });
  } catch (err) {
    if (err.message) {
      document.getElementById('manual-guidance-text').textContent = err.message;
    }
  }
  showView('manual');
});

document.getElementById('btn-manual-back').addEventListener('click', () => showView('welcome'));
document.getElementById('btn-retry-camera').addEventListener('click', startCamera);
document.getElementById('btn-camera-cancel').addEventListener('click', () => {
  stopCamera();
  showView('consent');
});

document.getElementById('btn-capture-register').addEventListener('click', async () => {
  const img = captureCanvasBase64();
  if (!img) {
    setBanner('camera-error', 'No live camera frame available. Allow camera access and tap Retry Camera.');
    return;
  }
  await submitRegistration(img);
});

document.getElementById('btn-mock-capture').addEventListener('click', async () => {
  await submitRegistration(buildSyntheticMockPayload());
});

document.getElementById('btn-switch-worker').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/logout', { method: 'POST' }).catch(() => {});
  showView('welcome');
});

document.getElementById('btn-revoke-device').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/device/revoke', { method: 'POST' }).catch(() => {});
  showView('welcome');
});

init();
