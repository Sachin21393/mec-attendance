import { apiFetch, ApiError, ensureCsrfToken } from '/shared/api.js';

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
  kioskStatus: null,
  lastLocation: null,
  worker: null,
  today: null,
  cameraMode: 'kiosk', // 'kiosk' | 'register'
  attendanceAction: 'LOGIN', // 'LOGIN' | 'LOGOUT'
  invitationCode: '',
  consentVersion: '',
  fullName: '',
  stream: null,
  autoScanTimer: null,
  autoScanActive: false,
  autoScanAttempts: 0,
  autoRecognitionMisses: 0,
  faceDetector: null,
  busy: false,
  pendingIdempotencyKey: null,
  pendingProxyVerify: null
};

let kioskRegTimer = null;

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

function stopAutoScan() {
  state.autoScanActive = false;
  if (state.autoScanTimer) {
    clearTimeout(state.autoScanTimer);
    state.autoScanTimer = null;
  }
}

function stopCamera() {
  stopAutoScan();
  setScanning(false);
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
  document.getElementById('camera-video').srcObject = null;
}

function hasUsableFrameBrightness() {
  const video = document.getElementById('camera-video');
  if (!state.stream || video.videoWidth === 0 || video.videoHeight === 0) {
    return false;
  }
  const sampleCanvas = document.getElementById('camera-canvas');
  sampleCanvas.width = 64;
  sampleCanvas.height = 48;
  const ctx = sampleCanvas.getContext('2d');
  ctx.drawImage(video, 0, 0, 64, 48);
  const data = ctx.getImageData(0, 0, 64, 48).data;
  let sum = 0;
  let min = 255;
  let max = 0;
  for (let i = 0; i < data.length; i += 4) {
    const lum = (data[i] + data[i + 1] + data[i + 2]) / 3;
    sum += lum;
    if (lum < min) min = lum;
    if (lum > max) max = lum;
  }
  const avg = sum / (data.length / 4);
  return avg > 18 && (max - min) > 20;
}

async function runAutoScanTick() {
  if (!state.autoScanActive || !state.stream || state.cameraMode !== 'kiosk') {
    return;
  }
  const video = document.getElementById('camera-video');
  if (!state.busy && video.videoWidth > 0 && video.readyState >= 2) {
    if (state.autoScanAttempts >= 20) {
      stopAutoScan();
      setScanning(false);
      document.getElementById('camera-guidance').textContent =
        'No clear face detected automatically. Place your face in frame and tap Scan Now.';
      return;
    }

    let shouldCapture = false;
    let waitMs = 450;

    if ('FaceDetector' in window) {
      waitMs = 300;
      try {
        if (!state.faceDetector) {
          state.faceDetector = new window.FaceDetector({ fastMode: true, maxDetectedFaces: 2 });
        }
        const faces = await state.faceDetector.detect(video);
        if (faces.length === 1 && faces[0].boundingBox.width >= video.videoWidth * 0.14) {
          shouldCapture = true;
        } else if (faces.length > 1) {
          document.getElementById('camera-guidance').textContent = 'Multiple faces detected — keep only one person in frame.';
        } else {
          document.getElementById('camera-guidance').textContent = 'Looking for your face... Place face inside the oval.';
        }
      } catch {
        // Browser exposes FaceDetector constructor without working backend; disable and use frame polling
        state.faceDetector = null;
        if (hasUsableFrameBrightness()) {
          shouldCapture = true;
        }
        waitMs = 450;
      }
    } else if (hasUsableFrameBrightness()) {
      shouldCapture = true;
    }

    if (shouldCapture) {
      const img = captureCanvasBase64();
      if (img) {
        state.autoScanAttempts += 1;
        await handleCameraCapture(img, { auto: true });
      }
    }

    if (state.autoScanActive) {
      state.autoScanTimer = setTimeout(runAutoScanTick, waitMs);
    }
    return;
  }

  if (state.autoScanActive) {
    state.autoScanTimer = setTimeout(runAutoScanTick, 200);
  }
}

function startAutoScan() {
  stopAutoScan();
  if (state.cameraMode !== 'kiosk') {
    return;
  }
  state.autoScanActive = true;
  state.autoScanAttempts = 0;
  state.autoRecognitionMisses = 0;
  setScanning(true);
  // Start fast after brief exposure settle so scanning triggers immediately
  state.autoScanTimer = setTimeout(runAutoScanTick, 250);
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
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 800 } },
        audio: false
      });
    } catch {
      stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
    }
    state.stream = stream;
    const video = document.getElementById('camera-video');
    video.srcObject = stream;
    await video.play().catch(() => {});
    if (state.cameraMode === 'kiosk' || state.cameraMode === 'self') {
      document.getElementById('camera-guidance').textContent =
        'Look straight into the oval — scanning your face automatically...';
      startAutoScan();
    }
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
  const maxSide = 480;
  const scale = Math.min(1, maxSide / Math.max(video.videoWidth, video.videoHeight));
  const targetW = Math.max(160, Math.round(video.videoWidth * scale));
  const targetH = Math.max(160, Math.round(video.videoHeight * scale));
  const canvas = document.getElementById('camera-canvas');
  canvas.width = targetW;
  canvas.height = targetH;
  const ctx = canvas.getContext('2d');
  ctx.drawImage(video, 0, 0, targetW, targetH);
  return canvas.toDataURL('image/jpeg', 0.78);
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
      ? `The other employee must be present (${remaining} of ${today.maxProxyPerDay} remaining today).`
      : `Daily limit reached (${today.proxyUsedToday} of ${today.maxProxyPerDay} used today).`;
  } else {
    proxyWrap.hidden = true;
  }
}

async function refreshHome(justRegistered = false) {
  stopCamera();
  document.getElementById('reg-success-banner').hidden = !justRegistered;
  const firstName = (state.worker.fullName || 'Employee').split(' ')[0];
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

function formatTime12h(hhMm) {
  const parts = String(hhMm || '').slice(0, 5).split(':');
  if (parts.length !== 2) return String(hhMm || '-');
  const h24 = Number(parts[0]);
  const m = Number(parts[1]);
  if (Number.isNaN(h24) || Number.isNaN(m)) return String(hhMm || '-');
  const period = h24 >= 12 ? 'PM' : 'AM';
  const h12 = h24 % 12 === 0 ? 12 : h24 % 12;
  return `${String(h12).padStart(2, '0')}:${String(m).padStart(2, '0')} ${period}`;
}

function formatShiftAndGrace(shiftStartLocal, graceMinutes) {
  const startLabel = formatTime12h(shiftStartLocal);
  const parts = String(shiftStartLocal || '').slice(0, 5).split(':');
  if (parts.length !== 2) return `${startLabel} (${graceMinutes}m grace)`;
  const totalMins = (Number(parts[0]) * 60 + Number(parts[1]) + Number(graceMinutes || 0)) % (24 * 60);
  const endHhMm = `${String(Math.floor(totalMins / 60)).padStart(2, '0')}:${String(totalMins % 60).padStart(2, '0')}`;
  return Number(graceMinutes || 0) > 0
    ? `${startLabel} (Grace until ${formatTime12h(endHhMm)})`
    : `${startLabel} (No grace)`;
}

function prewarmGeolocationIfGranted() {
  if (!state.kioskStatus?.geofenceEnabled || !navigator.geolocation || !navigator.permissions?.query) {
    return;
  }
  navigator.permissions.query({ name: 'geolocation' }).then((perm) => {
    if (perm.state === 'granted') {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          state.lastLocation = {
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
            capturedAtMs: Date.now()
          };
        },
        () => {},
        { enableHighAccuracy: true, timeout: 6000, maximumAge: 10000 }
      );
    }
  }).catch(() => {});
}

let locationPermWatcherBound = false;

function renderLocationBannerState(permState) {
  const banner = document.getElementById('kiosk-location-banner');
  const textEl = document.getElementById('kiosk-location-prompt-text');
  const btn = document.getElementById('btn-enable-location');
  const geoInfo = document.getElementById('kiosk-geofence-info');
  const radius = state.kioskStatus?.geofenceRadiusMeters || 50;

  if (!banner || !state.kioskStatus?.geofenceEnabled) {
    if (banner) banner.hidden = true;
    return;
  }

  if (permState === 'granted') {
    banner.hidden = true;
    if (geoInfo) geoInfo.textContent = `${radius}m radius · Location allowed`;
    prewarmGeolocationIfGranted();
    return;
  }

  if (permState === 'denied') {
    banner.className = 'banner banner-warn';
    banner.hidden = false;
    if (textEl) {
      textEl.textContent = 'Location is currently blocked for this site. Tap Enable / Retry Location (or allow Location in your browser/app Site Settings -> Permissions -> Location -> Allow).';
    }
    if (btn) btn.textContent = 'Enable / Retry Location';
    if (geoInfo) geoInfo.textContent = `${radius}m radius · Location blocked`;
    return;
  }

  // 'prompt' or unknown (iOS Safari without Permissions API)
  banner.className = 'banner banner-info';
  banner.hidden = false;
  if (textEl) {
    textEl.textContent = `Office geofencing is active (${radius}m zone). Allow location access when prompted to mark attendance.`;
  }
  if (btn) btn.textContent = 'Allow Location Access';
  if (geoInfo) geoInfo.textContent = `${radius}m radius · Location needed`;
}

function syncLocationPermissionUi() {
  if (!state.kioskStatus?.geofenceEnabled) {
    const banner = document.getElementById('kiosk-location-banner');
    if (banner) banner.hidden = true;
    return;
  }
  if (state.lastLocation && (Date.now() - state.lastLocation.capturedAtMs) < 60000) {
    renderLocationBannerState('granted');
    return;
  }
  if (!navigator.geolocation) {
    renderLocationBannerState('denied');
    return;
  }
  if (!navigator.permissions?.query) {
    renderLocationBannerState('prompt');
    return;
  }
  navigator.permissions.query({ name: 'geolocation' }).then((perm) => {
    renderLocationBannerState(perm.state);
    if (!locationPermWatcherBound) {
      locationPermWatcherBound = true;
      perm.onchange = () => renderLocationBannerState(perm.state);
    }
  }).catch(() => {
    renderLocationBannerState('prompt');
  });
}

function getCurrentGpsPosition(maxAgeMs = 8000) {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('Location/GPS is unavailable in this browser.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const loc = {
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          capturedAtMs: Date.now()
        };
        state.lastLocation = loc;
        renderLocationBannerState('granted');
        resolve(loc);
      },
      (err) => {
        if (err.code === 1) {
          renderLocationBannerState('denied');
          const e = new Error('Location permission was denied. Please tap "Allow" when prompted, or allow Location in your browser/app Site Settings and tap Enable / Retry Location.');
          e.permissionDenied = true;
          reject(e);
        } else {
          reject(new Error('Could not read GPS location. Please turn on device Location/GPS and try again.'));
        }
      },
      { enableHighAccuracy: true, timeout: 12000, maximumAge: maxAgeMs }
    );
  });
}

async function requestLocationFromUserButton() {
  const btn = document.getElementById('btn-enable-location');
  setBanner('kiosk-geofence-error', '');
  setBanner('kiosk-location-success', '');
  if (!navigator.geolocation) {
    setBanner('kiosk-geofence-error', 'Location/GPS is unavailable in this browser.');
    return;
  }
  const origLabel = btn ? btn.textContent : 'Allow Location Access';
  if (btn) {
    btn.disabled = true;
    btn.textContent = 'Requesting location...';
  }
  try {
    const loc = await getCurrentGpsPosition(0);
    const check = await apiFetch('/api/v1/attendance/kiosk/location-check', {
      method: 'POST',
      body: { latitude: loc.latitude, longitude: loc.longitude }
    });
    setBanner('kiosk-location-success', check.message || 'Location enabled — you are inside the office zone.');
  } catch (err) {
    setBanner('kiosk-geofence-error', err?.message || 'Could not enable location access.');
  } finally {
    if (btn) {
      btn.disabled = false;
      if (btn.textContent === 'Requesting location...') {
        btn.textContent = origLabel;
      }
    }
  }
}

async function verifyGeofenceAndStartScan(action) {
  if (!navigator.onLine) {
    showAttendanceFailure("You're offline. Your attendance has not been recorded.", true);
    return;
  }
  setBanner('kiosk-geofence-error', '');
  setBanner('kiosk-location-success', '');
  if (!state.kioskStatus) {
    state.kioskStatus = await apiFetch('/api/v1/attendance/kiosk/status').catch(() => null);
  }
  if (!state.kioskStatus?.geofenceEnabled) {
    openCameraView('kiosk', action);
    return;
  }

  const loginBtn = document.getElementById('btn-kiosk-login');
  const logoutBtn = document.getElementById('btn-kiosk-logout');
  const activeBtn = action === 'LOGOUT' ? logoutBtn : loginBtn;
  const origLabel = activeBtn.textContent;
  loginBtn.disabled = true;
  logoutBtn.disabled = true;
  activeBtn.textContent = 'Checking office location...';

  try {
    const loc = await getCurrentGpsPosition(8000);
    await apiFetch('/api/v1/attendance/kiosk/location-check', {
      method: 'POST',
      body: { latitude: loc.latitude, longitude: loc.longitude }
    });
    openCameraView('kiosk', action);
  } catch (err) {
    const msg = err?.message || 'You are out of the office zone.';
    setBanner('kiosk-geofence-error', msg);
    if (!views.welcome.hidden) {
      document.getElementById('kiosk-geofence-error')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } else {
      showAttendanceFailure(msg, true, false);
    }
  } finally {
    loginBtn.disabled = false;
    logoutBtn.disabled = false;
    activeBtn.textContent = origLabel;
  }
}

async function showKioskHome(registeredMsg = '') {
  stopCamera();
  setBanner('kiosk-geofence-error', '');
  setBanner('kiosk-location-success', '');
  const regBanner = document.getElementById('kiosk-reg-banner');
  if (kioskRegTimer) {
    clearTimeout(kioskRegTimer);
    kioskRegTimer = null;
  }
  if (regBanner) {
    if (registeredMsg) {
      regBanner.textContent = registeredMsg;
      regBanner.hidden = false;
      kioskRegTimer = setTimeout(() => {
        regBanner.hidden = true;
        regBanner.textContent = '';
      }, 4500);
    } else {
      regBanner.hidden = true;
      regBanner.textContent = '';
    }
  }
  try {
    const k = await apiFetch('/api/v1/attendance/kiosk/status');
    state.kioskStatus = k;
    document.getElementById('kiosk-workplace-meta').textContent =
      `${k.workplaceName} (${k.timezoneId}) — Choose Login or Logout Attendance below.`;
    document.getElementById('kiosk-date').textContent = k.attendanceDate || '-';
    const geoRow = document.getElementById('kiosk-geofence-row');
    const geoInfo = document.getElementById('kiosk-geofence-info');
    if (geoRow && geoInfo) {
      if (k.geofenceEnabled) {
        geoRow.hidden = false;
        geoInfo.textContent = `${k.geofenceRadiusMeters || 50}m radius enforced`;
      } else {
        geoRow.hidden = true;
      }
    }
    const loginBtn = document.getElementById('btn-kiosk-login');
    const logoutBtn = document.getElementById('btn-kiosk-logout');
    if (k.dayType && k.dayType !== 'WORKING') {
      const label = k.dayType === 'HOLIDAY' ? 'Holiday' : 'Weekly off';
      document.getElementById('kiosk-shift-info').textContent = label;
      document.getElementById('kiosk-logout-info').textContent = label;
      loginBtn.disabled = true;
      loginBtn.textContent = 'Non-working day';
      logoutBtn.disabled = true;
    } else {
      document.getElementById('kiosk-shift-info').textContent =
        formatShiftAndGrace(k.shiftStartLocal, k.gracePeriodMinutes);
      document.getElementById('kiosk-logout-info').textContent =
        formatTime12h(k.shiftEndLocal || '18:00');
      loginBtn.disabled = false;
      loginBtn.textContent = 'Login Attendance';
      logoutBtn.disabled = false;
      logoutBtn.textContent = 'Logout Attendance';
    }
    syncLocationPermissionUi();
  } catch {
    // Non-fatal fallback
  }
  showView('welcome');
}

function openCameraView(mode, action = state.attendanceAction || 'LOGIN') {
  state.cameraMode = mode;
  state.attendanceAction = action;
  state.pendingProxyVerify = null;
  state.pendingIdempotencyKey = (mode === 'kiosk') ? crypto.randomUUID() : null;

  const heading = document.getElementById('camera-heading');
  const guidance = document.getElementById('camera-guidance');
  const actionBtn = document.getElementById('btn-capture-action');
  const proxyTargetWrap = document.getElementById('proxy-target-wrap');

  proxyTargetWrap.hidden = mode !== 'proxy';
  if (mode === 'register') {
    heading.textContent = 'Take your registration photo';
    guidance.textContent = 'Place your face inside the frame in good lighting. Keep only one face in frame.';
    actionBtn.textContent = 'Capture & Complete Registration';
    actionBtn.className = 'btn btn-primary btn-block';
  } else {
    heading.textContent = action === 'LOGOUT' ? 'Logout Attendance — Scan Face' : 'Login Attendance — Scan Face';
    guidance.textContent = 'Look straight into the oval — scanning automatically...';
    actionBtn.textContent = 'Scan Now';
    actionBtn.className = 'btn btn-secondary btn-block';
  }

  showView('camera');
  startCamera();
}

function showAttendanceSuccess(data) {
  stopCamera();
  const isLogout = data.actionType === 'LOGOUT' || state.attendanceAction === 'LOGOUT';
  const icon = document.getElementById('result-icon-wrap');
  icon.className = 'result-icon result-icon-success';
  icon.innerHTML = '&#10003;';
  document.getElementById('result-title').textContent = isLogout
    ? 'Logout attendance marked'
    : 'Login attendance marked';

  const loginRow = document.getElementById('result-login-row');
  const loginTimeEl = document.getElementById('result-login-time');
  const timeLabel = document.getElementById('result-time-label');
  const badge = document.getElementById('result-status-badge');

  document.getElementById('result-details').hidden = false;
  document.getElementById('result-worker-name').textContent = `${data.subjectWorkerName} (${data.subjectWorkerCode})`;
  document.getElementById('result-date').textContent = data.attendanceDate;

  if (isLogout) {
    const logoutLabel = data.logoutStatusLabel || (data.logoutStatus === 'EARLY_LOGOUT' ? 'Early Logout' : 'Extended Work Hours');
    const isEarly = data.logoutStatus === 'EARLY_LOGOUT';
    document.getElementById('result-subtitle').textContent =
      `${data.subjectWorkerName} (${data.subjectWorkerCode}) — ${logoutLabel}.`;
    loginRow.hidden = false;
    loginTimeEl.textContent = `${data.checkInLocalTime} (${data.timezoneId})`;
    timeLabel.textContent = 'Logout time';
    document.getElementById('result-time').textContent = `${data.checkOutLocalTime} (${data.timezoneId})`;
    badge.className = `status-badge ${isEarly ? 'status-late' : 'status-ontime'}`;
    badge.textContent = logoutLabel;
    document.getElementById('result-method').textContent = 'Logout Attendance';
  } else {
    document.getElementById('result-subtitle').textContent = state.biometricsConfig?.mockMode
      ? `Recorded in mock-biometrics mode for ${data.subjectWorkerName}.`
      : `Recorded for ${data.subjectWorkerName} (${data.subjectWorkerCode}).`;
    loginRow.hidden = true;
    timeLabel.textContent = 'Login time';
    document.getElementById('result-time').textContent = `${data.checkInLocalTime} (${data.timezoneId})`;
    badge.className = `status-badge ${data.attendanceStatus === 'LATE' ? 'status-late' : 'status-ontime'}`;
    badge.textContent = data.attendanceStatus === 'LATE' ? 'Late' : 'On time';
    document.getElementById('result-method').textContent = 'Login Attendance';
  }

  document.getElementById('btn-result-retry').hidden = true;
  const nextBtn = document.getElementById('btn-result-next-worker');
  if (nextBtn) {
    nextBtn.hidden = false;
    nextBtn.textContent = isLogout ? 'Scan Next Employee (Logout)' : 'Scan Next Employee (Login)';
  }
  showView('result');
}

function showAttendanceFailure(message, allowRetry = true, allowNextWorker = false) {
  stopCamera();
  const icon = document.getElementById('result-icon-wrap');
  icon.className = 'result-icon result-icon-error';
  icon.textContent = '!';
  document.getElementById('result-title').textContent = 'Attendance not recorded';
  document.getElementById('result-subtitle').textContent = message;
  document.getElementById('result-details').hidden = true;
  document.getElementById('btn-result-retry').hidden = !allowRetry;
  const nextBtn = document.getElementById('btn-result-next-worker');
  if (nextBtn) nextBtn.hidden = !allowNextWorker;
  showView('result');
}

async function handleCameraCapture(imageBase64, opts = {}) {
  const fromAuto = !!opts.auto;
  if (state.busy) {
    return;
  }
  if (!navigator.onLine) {
    stopAutoScan();
    if (state.cameraMode !== 'register') {
      showAttendanceFailure("You're offline. Your attendance has not been recorded.", true, true);
    } else {
      setBanner('camera-error', "You're offline. Please connect to the internet and try again.");
    }
    return;
  }

  state.busy = true;
  const actionBtn = document.getElementById('btn-capture-action');
  actionBtn.disabled = true;
  if (!fromAuto) {
    setBanner('camera-error', '');
  }
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
      // Keep the phone sessionless so any worker can use or register on it next
      apiFetch('/api/v1/workers/logout', { method: 'POST' }).catch(() => {});
      state.worker = null;
      document.getElementById('input-invite-code').value = '';
      await showKioskHome(
        `Registered ${result.fullName || state.fullName} (${result.workerCode})! You can now mark attendance below.`
      );
    } else {
      document.getElementById('camera-guidance').textContent = state.attendanceAction === 'LOGOUT'
        ? 'Recognizing face & recording Logout...'
        : 'Recognizing face & recording Login...';
      const checkIn = await apiFetch('/api/v1/attendance/kiosk/mark', {
        method: 'POST',
        headers: { 'Idempotency-Key': state.pendingIdempotencyKey },
        body: {
          imageBase64,
          idempotencyKey: state.pendingIdempotencyKey,
          actionType: state.attendanceAction,
          latitude: state.lastLocation?.latitude ?? null,
          longitude: state.lastLocation?.longitude ?? null
        }
      });
      stopAutoScan();
      showAttendanceSuccess(checkIn);
    }
  } catch (err) {
    if (state.cameraMode === 'register') {
      setScanning(false);
      setBanner('camera-error', err.message || 'Registration failed. Please try again.');
    } else if (err instanceof ApiError && (err.code === 'ALREADY_MARKED' || err.code === 'ALREADY_LOGGED_OUT' || err.code === 'NOT_LOGGED_IN')) {
      stopAutoScan();
      setScanning(false);
      showAttendanceFailure(err.message, false, true);
    } else if (err instanceof ApiError && (err.code === 'OUT_OF_OFFICE_ZONE' || err.code === 'LOCATION_REQUIRED' || err.code === 'GEOFENCE_NOT_CONFIGURED')) {
      stopAutoScan();
      setScanning(false);
      state.lastLocation = null;
      showAttendanceFailure(err.message, true, false);
    } else if (fromAuto && err instanceof ApiError
      && (err.code === 'NO_FACE_DETECTED' || err.code === 'MULTIPLE_FACES_DETECTED'
        || err.code === 'FACE_TOO_BLURRY' || err.code === 'FACE_TOO_SMALL'
        || err.code === 'POSE_NOT_FRONTAL' || err.code === 'POOR_LIGHTING')) {
      document.getElementById('camera-guidance').textContent =
        err.message || 'Hold still with a single face inside the oval...';
    } else if (err instanceof ApiError && err.code === 'FACE_NOT_RECOGNIZED') {
      if (fromAuto && state.autoRecognitionMisses < 2) {
        state.autoRecognitionMisses += 1;
        document.getElementById('camera-guidance').textContent =
          'Hold steady in good lighting — verifying your face...';
      } else {
        stopAutoScan();
        setScanning(false);
        showAttendanceFailure(err.message || 'Face did not match any registered employee. Please try again in better lighting.', true, true);
      }
    } else {
      stopAutoScan();
      setScanning(false);
      setBanner('camera-error', err.message || "We couldn't verify your face. Please try again in better lighting.");
      document.getElementById('camera-guidance').textContent = 'Place face inside the frame and try again.';
    }
  } finally {
    state.busy = false;
    actionBtn.disabled = false;
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

  // Always run sessionless multi-worker kiosk mode: clear any stale legacy worker session cookie
  apiFetch('/api/v1/workers/logout', { method: 'POST' }).catch(() => {});

  const [bioCfg] = await Promise.all([
    apiFetch('/api/v1/biometrics/config').catch(() => null),
    ensureCsrfToken().catch(() => null),
    showKioskHome()
  ]);
  if (bioCfg) {
    state.biometricsConfig = bioCfg;
    if (bioCfg.mockMode) {
      document.getElementById('mock-mode-badge').hidden = false;
    }
  }
}

document.getElementById('btn-enable-location')?.addEventListener('click', () => {
  requestLocationFromUserButton();
});

document.getElementById('btn-kiosk-login')?.addEventListener('click', () => {
  verifyGeofenceAndStartScan('LOGIN');
});

document.getElementById('btn-kiosk-logout')?.addEventListener('click', () => {
  verifyGeofenceAndStartScan('LOGOUT');
});

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
      `Employee code: ${info.workerCode}${info.teamName ? ' · Team: ' + info.teamName : ''}`;
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
  const name = document.getElementById('input-full-name').value.trim() || 'Employee';
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
    showKioskHome();
  }
});

document.getElementById('btn-capture-action').addEventListener('click', async () => {
  stopAutoScan();
  const img = captureCanvasBase64();
  if (!img) {
    setBanner('camera-error', 'No live camera frame available. Allow camera access and tap Retry Camera.');
    return;
  }
  await handleCameraCapture(img, { auto: false });
});

document.getElementById('btn-proxy-confirm-submit').addEventListener('click', submitProxyConfirmation);
document.getElementById('btn-proxy-confirm-cancel').addEventListener('click', () => showKioskHome());

document.getElementById('btn-result-next-worker')?.addEventListener('click', () => verifyGeofenceAndStartScan(state.attendanceAction));
document.getElementById('btn-result-retry').addEventListener('click', () => {
  if (state.cameraMode === 'kiosk') {
    verifyGeofenceAndStartScan(state.attendanceAction);
  } else {
    openCameraView(state.cameraMode, state.attendanceAction);
  }
});
document.getElementById('btn-result-done').addEventListener('click', () => showKioskHome());

document.getElementById('btn-switch-worker').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/logout', { method: 'POST' }).catch(() => {});
  state.worker = null;
  await showKioskHome();
});

document.getElementById('btn-revoke-device').addEventListener('click', async () => {
  await apiFetch('/api/v1/workers/device/revoke', { method: 'POST' }).catch(() => {});
  state.worker = null;
  await showKioskHome();
});

init();
