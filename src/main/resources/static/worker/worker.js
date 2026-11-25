import { apiFetch, ApiError } from '/shared/api.js';

const views = {
  loading: document.getElementById('view-loading'),
  welcome: document.getElementById('view-welcome'),
  invite: document.getElementById('view-invite'),
  consent: document.getElementById('view-consent'),
  manual: document.getElementById('view-manual-fallback'),
  home: document.getElementById('view-home'),
  camera: document.getElementById('view-camera'),
  proxyConfirm: document.getElementById('view-proxy-confirm'),
  result: document.getElementById('view-result')
};

const state = {
  biometricsConfig: null,
  worker: null,
  today: null,
  cameraMode: 'register', // 'register' | 'self' | 'proxy'
  invitationCode: '',
  consentVersion: '',
  fullName: '',
  stream: null,
  busy: false,
  pendingIdempotencyKey: null,
  pendingProxyVerify: null
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

function setScanning(active) {
  document.getElementById('camera-frame-wrap').classList.toggle('scanning', active);
}

function stopCamera() {
  setScanning(false);
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
  document.getElementById('camera-video').srcObject = null;
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
    document.getElementById('camera-video').srcObject = stream;
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
  if (state.cameraMode === 'register') {
    return btoa(`MOCK_FACE_${state.invitationCode}`);
  }
  if (state.cameraMode === 'proxy') {
    const targetCode = document.getElementById('input-proxy-target-code').value.trim().toUpperCase() || 'W001';
    return btoa(`MOCK_FACE_${targetCode}`);
  }
  return btoa(`MOCK_FACE_${state.worker.workerCode}`);
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

function renderTodayStatus(today) {
  state.today = today;
  document.getElementById('home-workplace-date').textContent = `${today.attendanceDate} (${today.timezoneId})`;
  const badge = document.getElementById('home-today-badge');
  const detail = document.getElementById('home-status-detail');
  const markBtn = document.getElementById('btn-mark-attendance');

  if (today.alreadyMarked) {
    const isLate = today.attendanceStatus === 'LATE';
    badge.className = `status-badge ${isLate ? 'status-late' : 'status-ontime'}`;
    badge.textContent = isLate ? `Late · ${today.checkInLocalTime}` : `Marked · ${today.checkInLocalTime}`;
    detail.textContent = `You have already marked attendance today at ${today.checkInLocalTime}`;
    markBtn.disabled = true;
    markBtn.textContent = `Already marked at ${today.checkInLocalTime}`;
  } else if (today.dayType !== 'WORKING') {
    badge.className = 'status-badge status-holiday';
    badge.textContent = today.dayType === 'HOLIDAY' ? 'Holiday' : 'Weekly off';
    detail.textContent = 'No attendance check-in required on non-working days.';
    markBtn.disabled = true;
    markBtn.textContent = 'Non-working day';
  } else {
    badge.className = 'status-badge status-notdue';
    badge.textContent = 'Not marked';
    detail.textContent = `Shift starts at ${today.shiftStartLocal} (${today.gracePeriodMinutes} min grace period).`;
    markBtn.disabled = false;
    markBtn.textContent = 'Mark your attendance';
  }

  const proxyWrap = document.getElementById('proxy-action-wrap');
  const proxyBtn = document.getElementById('btn-mark-proxy');
  const proxyNote = document.getElementById('proxy-quota-note');
  if (today.proxyEnabled && today.dayType === 'WORKING') {
    proxyWrap.hidden = false;
    const remaining = Math.max(0, (today.maxProxyPerDay || 0) - (today.proxyUsedToday || 0));
    proxyBtn.disabled = remaining === 0;
    proxyNote.textContent = remaining > 0
      ? `The other worker must be present (${remaining} of ${today.maxProxyPerDay} remaining today).`
      : `Daily limit reached (${today.proxyUsedToday} of ${today.maxProxyPerDay} used today).`;
  } else {
    proxyWrap.hidden = true;
  }
}

async function refreshHome(justRegistered = false) {
  stopCamera();
  document.getElementById('reg-success-banner').hidden = !justRegistered;
  const firstName = (state.worker.fullName || 'Worker').split(' ')[0];
  document.getElementById('home-greeting').textContent = `Welcome, ${firstName}`;
  document.getElementById('home-worker-meta').textContent =
    `${state.worker.fullName} (${state.worker.workerCode})${state.worker.teamName ? ' · ' + state.worker.teamName : ''}`;
  document.getElementById('home-offline-banner').hidden = navigator.onLine;

  try {
    const today = await apiFetch('/api/v1/attendance/today');
    renderTodayStatus(today);
  } catch {
    // Keep home rendered if offline
  }
  showView('home');
}

function openCameraView(mode) {
  state.cameraMode = mode;
  state.pendingProxyVerify = null;
  state.pendingIdempotencyKey = (mode === 'self' || mode === 'proxy') ? crypto.randomUUID() : null;

  const heading = document.getElementById('camera-heading');
  const guidance = document.getElementById('camera-guidance');
  const actionBtn = document.getElementById('btn-capture-action');
  const proxyTargetWrap = document.getElementById('proxy-target-wrap');

  proxyTargetWrap.hidden = mode !== 'proxy';
  if (mode === 'register') {
    heading.textContent = 'Take your registration photo';
    guidance.textContent = 'Place your face inside the frame in good lighting. Keep only one face in frame.';
    actionBtn.textContent = 'Capture & Complete Registration';
  } else if (mode === 'proxy') {
    heading.textContent = 'Ask the worker to face the camera';
    guidance.textContent = 'The other worker must be physically present for face verification.';
    actionBtn.textContent = 'Verify Worker Face';
  } else {
    heading.textContent = 'Place your face inside the frame';
    guidance.textContent = 'Keep your face still for a moment in good lighting.';
    actionBtn.textContent = 'Verify & Mark Attendance';
  }

  showView('camera');
  startCamera();
}

function showAttendanceSuccess(data) {
  stopCamera();
  const icon = document.getElementById('result-icon-wrap');
  icon.className = 'result-icon result-icon-success';
  icon.innerHTML = '&#10003;';
  document.getElementById('result-title').textContent = 'Attendance marked successfully';
  if (data.attendanceMethod === 'PROXY') {
    document.getElementById('result-subtitle').textContent =
      `Attendance recorded for ${data.subjectWorkerName} by ${data.actorWorkerName}.`;
  } else {
    document.getElementById('result-subtitle').textContent = state.biometricsConfig?.mockMode
      ? 'Recorded in development mock-biometrics mode.'
      : 'Your daily attendance has been recorded.';
  }
  document.getElementById('result-details').hidden = false;
  document.getElementById('result-worker-name').textContent = `${data.subjectWorkerName} (${data.subjectWorkerCode})`;
  document.getElementById('result-date').textContent = data.attendanceDate;
  document.getElementById('result-time').textContent = `${data.checkInLocalTime} (${data.timezoneId})`;
  const badge = document.getElementById('result-status-badge');
  badge.className = `status-badge ${data.attendanceStatus === 'LATE' ? 'status-late' : 'status-ontime'}`;
  badge.textContent = data.attendanceStatus === 'LATE' ? 'Late' : 'On time';
  document.getElementById('result-method').textContent = data.attendanceMethod;
  document.getElementById('btn-result-retry').hidden = true;
  showView('result');
}

function showAttendanceFailure(message, allowRetry = true) {
  stopCamera();
  const icon = document.getElementById('result-icon-wrap');
  icon.className = 'result-icon result-icon-error';
  icon.textContent = '!';
  document.getElementById('result-title').textContent = 'Attendance not recorded';
  document.getElementById('result-subtitle').textContent = message;
  document.getElementById('result-details').hidden = true;
  document.getElementById('btn-result-retry').hidden = !allowRetry;
  showView('result');
}

async function handleCameraCapture(imageBase64) {
  if (state.busy) {
    return;
  }
  if (!navigator.onLine) {
    if (state.cameraMode !== 'register') {
      showAttendanceFailure("You're offline. Your attendance has not been recorded.", true);
    } else {
      setBanner('camera-error', "You're offline. Please connect to the internet and try again.");
    }
    return;
  }

  state.busy = true;
  const actionBtn = document.getElementById('btn-capture-action');
  const mockBtn = document.getElementById('btn-mock-capture');
  actionBtn.disabled = true;
  mockBtn.disabled = true;
  setBanner('camera-error', '');
  setScanning(true);

  try {
    if (state.cameraMode === 'register') {
      document.getElementById('camera-guidance').textContent = 'Creating encrypted template...';
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
      state.worker = result;
      await refreshHome(true);
    } else if (state.cameraMode === 'proxy') {
      document.getElementById('camera-guidance').textContent = 'Verifying your face...';
      const targetWorkerCode = document.getElementById('input-proxy-target-code').value.trim() || null;
      const verify = await apiFetch('/api/v1/biometrics/verify', {
        method: 'POST',
        body: {
          mode: 'PROXY',
          targetWorkerCode,
          imageBase64
        }
      });
      stopCamera();
      state.pendingProxyVerify = verify;
      setBanner('proxy-confirm-error', '');
      document.getElementById('proxy-confirm-question').textContent =
        `You are marking attendance for ${verify.matchedWorkerName} (${verify.matchedWorkerCode}). Continue?`;
      showView('proxyConfirm');
    } else {
      document.getElementById('camera-guidance').textContent = 'Verifying your face...';
      const verify = await apiFetch('/api/v1/biometrics/verify', {
        method: 'POST',
        body: { mode: 'SELF', imageBase64 }
      });
      const checkIn = await apiFetch('/api/v1/attendance/check-in', {
        method: 'POST',
        headers: { 'Idempotency-Key': state.pendingIdempotencyKey },
        body: {
          verificationToken: verify.verificationToken,
          idempotencyKey: state.pendingIdempotencyKey
        }
      });
      showAttendanceSuccess(checkIn);
    }
  } catch (err) {
    setScanning(false);
    if (state.cameraMode === 'register') {
      setBanner('camera-error', err.message || 'Registration failed. Please try again.');
    } else if (err instanceof ApiError && err.code === 'ALREADY_MARKED') {
      showAttendanceFailure(err.message, false);
    } else {
      setBanner('camera-error', err.message || "We couldn't verify your face. Please try again in better lighting.");
      document.getElementById('camera-guidance').textContent = 'Place face inside the frame and try again.';
    }
  } finally {
    state.busy = false;
    actionBtn.disabled = false;
    mockBtn.disabled = false;
  }
}

async function submitProxyConfirmation() {
  if (!state.pendingProxyVerify || state.busy) {
    return;
  }
  state.busy = true;
  const confirmBtn = document.getElementById('btn-proxy-confirm-submit');
  confirmBtn.disabled = true;
  setBanner('proxy-confirm-error', '');
  try {
    const checkIn = await apiFetch('/api/v1/attendance/check-in', {
      method: 'POST',
      headers: { 'Idempotency-Key': state.pendingIdempotencyKey },
      body: {
        verificationToken: state.pendingProxyVerify.verificationToken,
        idempotencyKey: state.pendingIdempotencyKey
      }
    });
    showAttendanceSuccess(checkIn);
  } catch (err) {
    if (err instanceof ApiError && (err.code === 'ALREADY_MARKED' || err.code === 'PROXY_LIMIT_REACHED')) {
      showAttendanceFailure(err.message, false);
    } else {
      setBanner('proxy-confirm-error', err.message || 'Could not record proxy attendance.');
    }
  } finally {
    state.busy = false;
    confirmBtn.disabled = false;
  }
}

function setupPwaInstallAndUpdates() {
  let deferredPrompt = null;
  const installBtn = document.getElementById('btn-install-pwa');

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    installBtn.hidden = false;
  });

  installBtn.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    installBtn.hidden = true;
  });

  const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);
  const isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  if (isIos && !isStandalone) {
    document.getElementById('ios-install-tip').hidden = false;
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/service-worker.js').then((reg) => {
      reg.addEventListener('updatefound', () => {
        const newWorker = reg.installing;
        if (!newWorker) return;
        newWorker.addEventListener('statechange', () => {
          if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
            const banner = document.getElementById('sw-update-banner');
            banner.hidden = false;
            document.getElementById('btn-sw-refresh').onclick = () => {
              newWorker.postMessage({ type: 'SKIP_WAITING' });
              window.location.reload();
            };
          }
        });
      });
    }).catch(() => {});
  }
}

async function init() {
  setupVoiceInput();
  setupPwaInstallAndUpdates();

  window.addEventListener('online', () => {
    document.getElementById('home-offline-banner').hidden = true;
  });
  window.addEventListener('offline', () => {
    document.getElementById('home-offline-banner').hidden = false;
  });

  try {
    state.biometricsConfig = await apiFetch('/api/v1/biometrics/config');
    if (state.biometricsConfig.mockMode) {
      document.getElementById('mock-mode-badge').hidden = false;
      document.getElementById('btn-mock-capture').hidden = false;
    }
  } catch {
    // Non-fatal
  }

  try {
    state.worker = await apiFetch('/api/v1/workers/me');
    await refreshHome(false);
  } catch {
    showView('welcome');
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

document.getElementById('btn-consent-continue').addEventListener('click', () => {
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
  openCameraView('register');
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

document.getElementById('btn-mark-attendance').addEventListener('click', () => {
  if (!navigator.onLine) {
    showAttendanceFailure("You're offline. Your attendance has not been recorded.", true);
    return;
  }
  openCameraView('self');
});

document.getElementById('btn-mark-proxy').addEventListener('click', () => {
  if (!navigator.onLine) {
    showAttendanceFailure("You're offline. Your attendance has not been recorded.", true);
    return;
  }
  openCameraView('proxy');
});

document.getElementById('btn-retry-camera').addEventListener('click', startCamera);
document.getElementById('btn-camera-cancel').addEventListener('click', () => {
  stopCamera();
  if (state.cameraMode === 'register') {
    showView('consent');
  } else {
    refreshHome(false);
  }
});

document.getElementById('btn-capture-action').addEventListener('click', async () => {
  const img = captureCanvasBase64();
  if (!img) {
    setBanner('camera-error', 'No live camera frame available. Allow camera access and tap Retry Camera.');
    return;
  }
  await handleCameraCapture(img);
});

document.getElementById('btn-mock-capture').addEventListener('click', async () => {
  await handleCameraCapture(buildSyntheticMockPayload());
});

document.getElementById('btn-proxy-confirm-submit').addEventListener('click', submitProxyConfirmation);
document.getElementById('btn-proxy-confirm-cancel').addEventListener('click', () => refreshHome(false));

document.getElementById('btn-result-retry').addEventListener('click', () => openCameraView(state.cameraMode));
document.getElementById('btn-result-done').addEventListener('click', () => refreshHome(false));

document.getElementById('btn-switch-worker').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/logout', { method: 'POST' }).catch(() => {});
  state.worker = null;
  showView('welcome');
});

document.getElementById('btn-revoke-device').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/device/revoke', { method: 'POST' }).catch(() => {});
  state.worker = null;
  showView('welcome');
});

init();
