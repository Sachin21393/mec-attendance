# PWA Hardening, Accessibility & Device QA Guide

## 1. PWA Caching & Offline Behavior

- **Static Shell Only:** `service-worker.js` caches only static UI files (`/worker/*`, `/shared/*`, `/assets/*`, `/manifest.webmanifest`).
- **Network-Only Sensitive Routes:** Requests under `/api/`, `/admin`, and `/actuator` are explicitly excluded from Service Worker caching. Biometric payloads, embeddings, and authenticated data are never stored in Cache Storage or `localStorage`.
- **Offline Check-In:** Attendance requires server verification and UTC timestamping. When offline, the worker app displays `"You're offline. Your attendance has not been recorded."` and never simulates success.
- **App Updates:** When a new `service-worker.js` is installed, an `"A new version of the app is ready. Refresh"` banner prompts the user to activate the update.

---

## 2. Android Chrome & iOS Safari Camera / PWA Limitations

- **HTTPS Required:** `navigator.mediaDevices.getUserMedia()` works on `http://localhost` for local dev and requires `https://` on all network/mobile origins.
- **iOS Safari Isolated Storage:** On iOS, Safari tabs and Home-Screen installed PWAs maintain separate cookie jars. If a worker registers in Safari and then installs the PWA to the Home Screen, they must establish their session inside the installed app (or register directly inside the installed PWA).
- **iOS Camera Attributes:** `<video autoplay playsinline muted>` is required so front-camera preview renders inline rather than full-screen or frozen on iOS WebKit.
- **Denied Camera Permission:** Both Android Chrome and iOS Safari block re-prompting via `getUserMedia()` after permission is denied; the UI surfaces clear instructions and a **Retry Camera** button instead of a blank screen.

---

## 3. Pre-Release Device QA Matrix

| Check | Android Chrome | iOS Safari (Tab + Home Screen) | Desktop (Chrome/Edge) |
|---|---|---|---|
| Installable manifest & icons (192px, 512px) | Required | Add to Home Screen | Required |
| Front camera preview & oval guide | Required | Required (`playsinline`) | Required |
| Camera permission denied recovery message | Required | Required | Required |
| Offline check-in warning (no false success) | Required | Required | Required |
| 48px+ touch targets & WCAG AA contrast | Required | Required | Required |
| Keyboard navigation & Escape closes drawer | N/A | N/A | Required |
