import { apiFetch } from '/shared/api.js';

const state = {
  admin: null,
  month: new Date().toISOString().slice(0, 7),
  query: '',
  team: '',
  page: 0,
  size: 25,
  activeTab: 'dashboard',
  shiftStartLocal: '09:00'
};

const bannerTimers = new Map();

function showTimedBanner(elementOrId, message, durationMs = 3000) {
  const el = typeof elementOrId === 'string' ? document.getElementById(elementOrId) : elementOrId;
  if (!el) return;
  const existing = bannerTimers.get(el);
  if (existing) {
    clearTimeout(existing);
    bannerTimers.delete(el);
  }
  if (!message) {
    el.hidden = true;
    el.textContent = '';
    return;
  }
  el.textContent = message;
  el.hidden = false;
  if (durationMs > 0) {
    const t = setTimeout(() => {
      el.hidden = true;
      el.textContent = '';
      bannerTimers.delete(el);
    }, durationMs);
    bannerTimers.set(el, t);
  }
}

const loginView = document.getElementById('admin-login-view');
const appShell = document.getElementById('admin-app');
const sidebar = document.getElementById('admin-sidebar');

function setGlobalError(msg) {
  const el = document.getElementById('admin-global-error');
  if (!msg) {
    el.hidden = true;
    el.textContent = '';
  } else {
    el.textContent = msg;
    el.hidden = false;
  }
}

function showTab(tab) {
  state.activeTab = tab;
  if (tab !== 'workers') {
    stopEnrollCamera();
  }
  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.tab === tab);
  });
  const effectivePanel = tab === 'attendance' ? 'dashboard' : tab;
  document.querySelectorAll('.tab-panel').forEach((panel) => {
    panel.hidden = panel.id !== `tab-${effectivePanel}`;
  });
  sidebar.classList.remove('open');

  if (tab === 'dashboard' || tab === 'attendance') {
    loadDashboardAndGrid();
  } else if (tab === 'workers') {
    loadWorkersTable();
  } else if (tab === 'leaves') {
    loadLeavesOverview();
  } else if (tab === 'reports') {
    loadReportsPreview();
  } else if (tab === 'settings') {
    loadCalendarAndSettings();
  } else if (tab === 'audit') {
    loadAuditLog();
  }
}

function symbolForState(cellState) {
  switch (cellState) {
    case 'ON_TIME': return '✓';
    case 'LATE': return 'L';
    case 'ABSENT': return '✕';
    case 'LEAVE': return 'LV';
    case 'HOLIDAY': return 'H';
    case 'WEEKLY_OFF': return 'W';
    case 'NOT_DUE': return '·';
    default: return '—';
  }
}

function labelForState(cellState) {
  return cellState.replace(/_/g, ' ').toLowerCase();
}

async function loadDashboardAndGrid() {
  setGlobalError('');
  const loading = document.getElementById('grid-loading');
  const empty = document.getElementById('grid-empty');
  const tableWrap = document.getElementById('attendance-table-wrap');
  loading.hidden = false;

  try {
    const [dashboard, grid] = await Promise.all([
      apiFetch(`/api/v1/admin/dashboard?month=${encodeURIComponent(state.month)}`),
      apiFetch(`/api/v1/admin/attendance?month=${encodeURIComponent(state.month)}&query=${encodeURIComponent(state.query)}&team=${encodeURIComponent(state.team)}&page=${state.page}&size=${state.size}`)
    ]);

    document.getElementById('topbar-workplace').textContent = dashboard.workplaceName;
    document.getElementById('topbar-timezone').textContent = `Timezone: ${dashboard.timezoneId} · Today: ${dashboard.todayDate}`;
    document.getElementById('kpi-working-days').textContent = dashboard.configuredWorkingDays;
    document.getElementById('kpi-present-today').textContent = `${dashboard.presentTodayCount} / ${dashboard.activeWorkersCount}`;
    document.getElementById('kpi-ontime-today').textContent = dashboard.onTimeTodayCount;
    document.getElementById('kpi-late-today').textContent = dashboard.lateTodayCount;
    document.getElementById('kpi-absent-today').textContent = dashboard.absentTodayCount;
    const leavesTodayEl = document.getElementById('kpi-leaves-today');
    if (leavesTodayEl) leavesTodayEl.textContent = dashboard.leavesTodayCount ?? 0;
    document.getElementById('kpi-notdue-today').textContent = dashboard.notDueTodayCount;
    const leavesPendingEl = document.getElementById('kpi-leaves-pending');
    if (leavesPendingEl) leavesPendingEl.textContent = dashboard.totalPendingLeavesCount ?? 0;
    const leavesTakenEl = document.getElementById('kpi-leaves-taken');
    if (leavesTakenEl) leavesTakenEl.textContent = dashboard.totalApprovedLeavesCount ?? 0;

    document.getElementById('grid-subtitle').textContent =
      `${grid.month} · ${grid.configuredWorkingDays} configured working days · ${grid.totalWorkers} employees`;

    // Sync team filter options
    const teamSelect = document.getElementById('grid-team-filter');
    const prevTeam = state.team;
    teamSelect.innerHTML = '<option value="">All teams</option>';
    grid.teams.forEach((teamName) => {
      const opt = document.createElement('option');
      opt.value = teamName;
      opt.textContent = teamName;
      if (teamName === prevTeam) opt.selected = true;
      teamSelect.appendChild(opt);
    });

    renderGrid(grid);
    empty.hidden = grid.rows.length > 0;
    tableWrap.hidden = grid.rows.length === 0;

    document.getElementById('pagination-info').textContent =
      `Page ${grid.page + 1} of ${Math.max(1, grid.totalPages)} (${grid.totalWorkers} employees)`;
    document.getElementById('btn-page-prev').disabled = grid.page <= 0;
    document.getElementById('btn-page-next').disabled = grid.page + 1 >= grid.totalPages;
  } catch (err) {
    setGlobalError(err.message || 'Failed to load attendance data.');
  } finally {
    loading.hidden = true;
  }
}

function renderGrid(grid) {
  const thead = document.getElementById('attendance-thead');
  const tbody = document.getElementById('attendance-tbody');
  thead.innerHTML = '';
  tbody.innerHTML = '';

  const headerRow = document.createElement('tr');
  const workerTh = document.createElement('th');
  workerTh.className = 'sticky-col';
  workerTh.textContent = 'Employee';
  headerRow.appendChild(workerTh);

  const summaryTh = document.createElement('th');
  summaryTh.textContent = 'Worked';
  headerRow.appendChild(summaryTh);

  grid.days.forEach((day) => {
    const th = document.createElement('th');
    const d = new Date(`${day.date}T00:00:00Z`);
    const dayNum = String(d.getUTCDate()).padStart(2, '0');
    const weekday = d.toLocaleDateString('en-US', { weekday: 'short', timeZone: 'UTC' }).slice(0, 2);
    th.innerHTML = `<div>${dayNum}</div><div style="font-weight:400;color:var(--text-muted);">${weekday}</div>`;
    if (day.note) th.title = day.note;
    headerRow.appendChild(th);
  });
  thead.appendChild(headerRow);

  grid.rows.forEach((row) => {
    const tr = document.createElement('tr');

    const nameTd = document.createElement('td');
    nameTd.className = 'sticky-col';
    nameTd.innerHTML = `
      <div class="worker-cell-name">${escapeHtml(row.fullName)}</div>
      <div class="worker-cell-meta">${escapeHtml(row.workerCode)}${row.teamName ? ' · ' + escapeHtml(row.teamName) : ''}</div>
    `;
    tr.appendChild(nameTd);

    const sumTd = document.createElement('td');
    sumTd.innerHTML = `<strong>${row.workedDays}</strong>/${row.applicableWorkingDays}`;
    sumTd.title = `On time: ${row.onTimeDays}, Late: ${row.lateDays}, Absent: ${row.absentDays}`;
    tr.appendChild(sumTd);

    row.cells.forEach((cell) => {
      const td = document.createElement('td');
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `cell-btn cell-${cell.state}`;
      btn.textContent = symbolForState(cell.state);
      const timePart = cell.checkInLocalTime ? ` at ${cell.checkInLocalTime}` : '';
      const logoutPart = cell.checkOutLocalTime
        ? ` · Logout ${cell.checkOutLocalTime} (${cell.logoutStatusLabel || ''})`
        : '';
      const leavePart = cell.state === 'LEAVE' && cell.leaveTypeLabel
        ? ` (${cell.leaveTypeLabel}${cell.leaveSourceLabel ? ' · ' + cell.leaveSourceLabel : ''})`
        : '';
      const proxyPart = cell.method === 'PROXY' && cell.actorWorkerName ? ` (proxy by ${cell.actorWorkerName})` : '';
      btn.setAttribute('aria-label', `${row.fullName}, ${cell.date}: ${labelForState(cell.state)}${timePart}${logoutPart}${leavePart}${proxyPart}`);
      btn.title = `${cell.date}: ${labelForState(cell.state)}${timePart}${logoutPart}${leavePart}${proxyPart}`;

      if (cell.logoutStatus === 'EARLY_LOGOUT') {
        const dot = document.createElement('span');
        dot.className = 'proxy-dot';
        dot.style.background = '#d97706';
        dot.textContent = 'E';
        btn.appendChild(dot);
      } else if (cell.logoutStatus === 'EXTENDED_WORK_HOURS') {
        const dot = document.createElement('span');
        dot.className = 'proxy-dot';
        dot.style.background = '#059669';
        dot.textContent = '+';
        btn.appendChild(dot);
      } else if (cell.method === 'PROXY') {
        const dot = document.createElement('span');
        dot.className = 'proxy-dot';
        dot.textContent = 'P';
        btn.appendChild(dot);
      }

      if (cell.recordId) {
        btn.addEventListener('click', () => openDetailDrawer(cell.recordId));
      } else if (cell.state === 'LEAVE') {
        btn.addEventListener('click', () => openLeaveDetailDrawer(row, cell));
      } else if (cell.state === 'ABSENT' || cell.state === 'NOT_DUE') {
        btn.addEventListener('click', () => openManualExceptionDrawer(row.workerId, row.fullName, row.workerCode, cell.date));
      } else {
        btn.disabled = true;
      }
      td.appendChild(btn);
      tr.appendChild(td);
    });

    tbody.appendChild(tr);
  });
}

async function openDetailDrawer(recordId) {
  const drawer = document.getElementById('detail-drawer');
  const body = document.getElementById('drawer-body');
  body.innerHTML = '<p class="helper-text">Loading record details...</p>';
  drawer.hidden = false;

  try {
    const d = await apiFetch(`/api/v1/admin/attendance/${encodeURIComponent(recordId)}`);
    body.innerHTML = `
      <div class="detail-kv"><span>Employee</span><strong>${escapeHtml(d.subjectWorkerName)} (${escapeHtml(d.subjectWorkerCode)})</strong></div>
      <div class="detail-kv"><span>Date</span><strong>${escapeHtml(d.attendanceDate)}</strong></div>
      <div class="detail-kv"><span>Local Check-in</span><strong>${escapeHtml(d.checkInLocalTime)} (${escapeHtml(d.timezoneId)})</strong></div>
      <div class="detail-kv"><span>Status</span><strong>${escapeHtml(d.attendanceStatus)}</strong></div>
      <div class="detail-kv"><span>Logout Time</span><strong>${d.checkOutLocalTime ? `${escapeHtml(d.checkOutLocalTime)} (${escapeHtml(d.timezoneId)})` : 'Not logged out yet'}</strong></div>
      ${d.logoutStatusLabel ? `<div class="detail-kv"><span>Logout Status</span><strong style="color:${d.logoutStatus === 'EARLY_LOGOUT' ? '#b45309' : '#047857'};">${escapeHtml(d.logoutStatusLabel)}</strong></div>` : ''}
      <div class="detail-kv"><span>UTC Timestamp</span><span>${escapeHtml(d.checkInAtUtc)}</span></div>
      <div class="detail-kv"><span>Method</span><strong>${escapeHtml(d.attendanceMethod)}</strong></div>
      <div class="detail-kv"><span>Recorded By</span><strong>${d.actorWorkerName ? `${escapeHtml(d.actorWorkerName)}${d.actorWorkerCode ? ' (' + escapeHtml(d.actorWorkerCode) + ')' : ''}` : 'Administrator (Manual)'}</strong></div>
      ${d.notes ? `<div class="detail-kv"><span>Notes</span><span>${escapeHtml(d.notes)}</span></div>` : ''}
      <h3 style="margin: 20px 0 8px; font-size: 0.95rem;">Admin Correction (Audited)</h3>
      <div id="drawer-error" class="banner banner-error" hidden></div>
      <form id="form-correct-attendance" class="inline-form-card">
        <div class="field">
          <label for="corr-status">Updated status</label>
          <select id="corr-status" class="select">
            <option value="ON_TIME" ${d.attendanceStatus === 'ON_TIME' ? 'selected' : ''}>ON_TIME</option>
            <option value="LATE" ${d.attendanceStatus === 'LATE' ? 'selected' : ''}>LATE</option>
            <option value="MANUAL_REVIEW" ${d.attendanceStatus === 'MANUAL_REVIEW' ? 'selected' : ''}>MANUAL_REVIEW</option>
          </select>
        </div>
        <div class="field">
          <label for="corr-reason">Reason (required)</label>
          <input id="corr-reason" class="input" type="text" required placeholder="Explain why this record is corrected">
        </div>
        <button type="submit" class="btn btn-primary btn-block">Save Correction</button>
      </form>

      <h3 style="margin: 20px 0 8px; font-size: 0.95rem;">Correction History</h3>
      <div>
        ${(d.corrections || []).map((c) => `
          <div style="padding: 8px 0; border-bottom: 1px solid var(--border-subtle); font-size: 0.8125rem;">
            <strong>${escapeHtml(c.previousStatus || '-')} &rarr; ${escapeHtml(c.newStatus)}</strong><br>
            <span>${escapeHtml(c.reason)}</span><br>
            <span class="helper-text">${escapeHtml(c.createdAt)}</span>
          </div>
        `).join('') || '<p class="helper-text">No corrections recorded.</p>'}
      </div>

      <h3 style="margin: 20px 0 8px; font-size: 0.95rem;">Recent Audit Events</h3>
      <div>
        ${(d.recentAudits || []).map((a) => `
          <div style="padding: 8px 0; border-bottom: 1px solid var(--border-subtle); font-size: 0.8125rem;">
            <strong>${escapeHtml(a.eventType)}</strong> · ${escapeHtml(a.actorType)}<br>
            <span class="helper-text">${escapeHtml(a.eventTimestampUtc)}</span>
          </div>
        `).join('') || '<p class="helper-text">No audit events recorded.</p>'}
      </div>
    `;

    document.getElementById('form-correct-attendance').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const errBox = document.getElementById('drawer-error');
      errBox.hidden = true;
      try {
        await apiFetch(`/api/v1/admin/attendance/${encodeURIComponent(recordId)}/correct`, {
          method: 'POST',
          body: {
            newStatus: document.getElementById('corr-status').value,
            reason: document.getElementById('corr-reason').value.trim()
          }
        });
        await openDetailDrawer(recordId);
        await loadDashboardAndGrid();
      } catch (err) {
        errBox.textContent = err.message || 'Could not save correction.';
        errBox.hidden = false;
      }
    });
  } catch (err) {
    body.innerHTML = `<div class="banner banner-error">${escapeHtml(err.message || 'Failed to load details.')}</div>`;
  }
}

function openLeaveDetailDrawer(row, cell) {
  const drawer = document.getElementById('detail-drawer');
  const body = document.getElementById('drawer-body');
  drawer.hidden = false;
  body.innerHTML = `
    <div class="detail-kv"><span>Employee</span><strong>${escapeHtml(row.fullName)} (${escapeHtml(row.workerCode)})</strong></div>
    <div class="detail-kv"><span>Date</span><strong>${escapeHtml(cell.date)}</strong></div>
    <div class="detail-kv"><span>Status</span><strong>Leave (Auto-approved)</strong></div>
    <div class="detail-kv"><span>Leave Type</span><strong>${escapeHtml(cell.leaveTypeLabel || '-')}</strong></div>
    <div class="detail-kv"><span>Recorded Via</span><strong>${escapeHtml(cell.leaveSourceLabel || '-')}</strong></div>
    <div class="detail-kv"><span>Paid / Sick Pending</span><strong>${row.paidLeavePending ?? 0} remaining (${row.paidLeaveTaken ?? 0} taken)</strong></div>
    <div class="detail-kv"><span>Annual Leave Pending</span><strong>${row.annualLeavePending ?? 0} remaining (${row.annualLeaveTaken ?? 0} taken)</strong></div>
    <p class="helper-text" style="margin-top: 14px;">
      Need to replace a post-cutoff leave with actual attendance? Use the <strong>Leaves</strong> tab or contact your admin.
    </p>
  `;
}

function openManualExceptionDrawer(workerId, fullName, workerCode, date) {
  const drawer = document.getElementById('detail-drawer');
  const body = document.getElementById('drawer-body');
  drawer.hidden = false;
  body.innerHTML = `
    <p class="helper-text" style="margin-bottom: 12px;">
      Record audited manual attendance when an employee cannot use face recognition (camera unavailable, disability, or consent fallback).
    </p>
    <div class="detail-kv"><span>Employee</span><strong>${escapeHtml(fullName)} (${escapeHtml(workerCode)})</strong></div>
    <div class="detail-kv"><span>Date</span><strong>${escapeHtml(date)}</strong></div>
    <div id="drawer-error" class="banner banner-error" style="margin-top: 12px;" hidden></div>
    <form id="form-manual-exception" class="inline-form-card" style="margin-top: 16px;">
      <div class="field">
        <label for="man-status">Status</label>
        <select id="man-status" class="select">
          <option value="ON_TIME">ON_TIME</option>
          <option value="LATE">LATE</option>
          <option value="MANUAL_REVIEW">MANUAL_REVIEW</option>
        </select>
      </div>
      <div class="field">
        <label for="man-time">Check-in local time</label>
        <input id="man-time" class="input" type="time" value="${escapeHtml(state.shiftStartLocal || '09:00')}">
      </div>
      <div class="field">
        <label for="man-reason">Exception reason (required)</label>
        <input id="man-reason" class="input" type="text" required placeholder="e.g. Camera hardware unavailable">
      </div>
      <button type="submit" class="btn btn-primary btn-block">Record Manual Attendance</button>
    </form>
  `;
  document.getElementById('form-manual-exception').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const errBox = document.getElementById('drawer-error');
    errBox.hidden = true;
    try {
      const res = await apiFetch('/api/v1/admin/attendance/manual', {
        method: 'POST',
        body: {
          workerId,
          attendanceDate: date,
          status: document.getElementById('man-status').value,
          checkInLocalTime: document.getElementById('man-time').value,
          reason: document.getElementById('man-reason').value.trim()
        }
      });
      await openDetailDrawer(res.id);
      await loadDashboardAndGrid();
    } catch (err) {
      errBox.textContent = err.message || 'Could not record manual attendance.';
      errBox.hidden = false;
    }
  });
}

const selectedWorkerIds = new Set();
let loadedWorkers = [];
let pendingDeleteTargets = [];
let pendingDeleteBulk = false;

function syncWorkersBulkUi() {
  const bar = document.getElementById('workers-bulk-bar');
  const countEl = document.getElementById('workers-selected-count');
  const selectAll = document.getElementById('workers-select-all');
  const count = selectedWorkerIds.size;
  if (bar && countEl) {
    countEl.textContent = `${count} selected`;
    bar.hidden = count === 0;
  }
  if (selectAll) {
    selectAll.checked = loadedWorkers.length > 0 && loadedWorkers.every((w) => selectedWorkerIds.has(w.id));
  }
}

function openDeleteModal(workersList, isBulk) {
  pendingDeleteTargets = workersList;
  pendingDeleteBulk = isBulk;

  const modal = document.getElementById('delete-worker-modal');
  const title = document.getElementById('delete-modal-title');
  const summary = document.getElementById('delete-modal-summary');
  const reasonLabel = document.getElementById('delete-reason-label');
  const reasonInput = document.getElementById('delete-reason-input');
  const pwdInput = document.getElementById('delete-admin-password');
  const errEl = document.getElementById('delete-modal-error');

  errEl.hidden = true;
  errEl.textContent = '';
  reasonInput.value = '';
  pwdInput.value = '';
  reasonInput.required = isBulk;
  reasonLabel.textContent = isBulk ? 'Reason for deletion (required)' : 'Reason for deletion (optional)';

  if (isBulk) {
    title.textContent = `Delete ${workersList.length} Selected Employees`;
    const names = workersList.slice(0, 5).map((w) => `${w.fullName} (${w.workerCode})`).join(', ');
    const extra = workersList.length > 5 ? ` and ${workersList.length - 5} more` : '';
    summary.textContent = `Permanently delete ${names}${extra}? Their attendance records, biometric templates, and sessions will be removed. Audit history is retained.`;
  } else {
    const w = workersList[0];
    title.textContent = `Delete Employee ${w.workerCode}`;
    summary.textContent = `Permanently delete ${w.fullName} (${w.workerCode})? Their attendance records, biometric template, and sessions will be removed. Audit history is retained.`;
  }

  modal.hidden = false;
  (isBulk ? reasonInput : pwdInput).focus();
}

function closeDeleteModal() {
  document.getElementById('delete-worker-modal').hidden = true;
  document.getElementById('delete-admin-password').value = '';
  pendingDeleteTargets = [];
}

async function loadWorkersTable() {
  const tbody = document.getElementById('workers-tbody');
  const query = document.getElementById('workers-search')?.value.trim() || '';
  const status = document.getElementById('workers-status-filter')?.value || '';
  tbody.innerHTML = '<tr><td colspan="8" class="helper-text">Loading employees...</td></tr>';
  try {
    const page = await apiFetch(
      `/api/v1/admin/workers?size=100&query=${encodeURIComponent(query)}&status=${encodeURIComponent(status)}`
    );
    loadedWorkers = page.content;
    const validIds = new Set(loadedWorkers.map((w) => w.id));
    Array.from(selectedWorkerIds).forEach((id) => {
      if (!validIds.has(id)) selectedWorkerIds.delete(id);
    });

    tbody.innerHTML = '';
    if (page.content.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="helper-text">No employees found. Register employees using "+ Register Employee (Photo)".</td></tr>';
      syncWorkersBulkUi();
      return;
    }
    page.content.forEach((w) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><input type="checkbox" class="worker-select-cb" data-id="${w.id}" aria-label="Select ${escapeHtml(w.fullName)} (${escapeHtml(w.workerCode)})" ${selectedWorkerIds.has(w.id) ? 'checked' : ''}></td>
        <td><strong>${escapeHtml(w.workerCode)}</strong></td>
        <td>${escapeHtml(w.fullName)}</td>
        <td>${escapeHtml(w.teamName || '-')}</td>
        <td>${escapeHtml(w.status)}</td>
        <td>${w.biometricsEnrolled ? (w.reEnrollRequired ? '<span style="color:var(--warn-text,#b45309);font-weight:600;">Re-enroll needed</span>' : 'Enrolled') : 'Not enrolled'}</td>
        <td>${w.activeSessions}</td>
        <td>
            <div style="display:flex;gap:6px;flex-wrap:wrap;">
            ${!w.biometricsEnrolled && w.status !== 'DISABLED' ? `<button type="button" class="btn btn-primary btn-enroll-row" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Enroll Face</button>` : ''}
            ${w.biometricsEnrolled ? `<button type="button" class="btn btn-secondary btn-reset-bio" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Reset Face</button>` : ''}
            <button type="button" class="btn btn-secondary btn-revoke-sess" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Revoke Sessions</button>
            ${w.status === 'DISABLED'
              ? `<button type="button" class="btn btn-secondary btn-enable-w" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Re-enable</button>`
              : `<button type="button" class="btn btn-secondary btn-disable-w" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Disable</button>`}
            <button type="button" class="btn btn-danger-outline btn-delete-w" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Delete</button>
          </div>
        </td>
      `;
      tbody.appendChild(tr);
    });

    syncWorkersBulkUi();

    tbody.querySelectorAll('.worker-select-cb').forEach((chk) => {
      chk.addEventListener('change', (e) => {
        if (e.target.checked) {
          selectedWorkerIds.add(chk.dataset.id);
        } else {
          selectedWorkerIds.delete(chk.dataset.id);
        }
        syncWorkersBulkUi();
      });
    });

    tbody.querySelectorAll('.btn-enroll-row').forEach((btn) => {
      btn.addEventListener('click', () => {
        const target = loadedWorkers.find((w) => w.id === btn.dataset.id);
        if (target) {
          openEnrollCameraModal(target);
        }
      });
    });

    tbody.querySelectorAll('.btn-revoke-sess').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/sessions/revoke`, { method: 'POST' });
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not revoke employee sessions.');
        }
      });
    });
    tbody.querySelectorAll('.btn-reset-bio').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          const res = await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/biometric-reset`, {
            method: 'POST',
            body: { reason: 'Admin biometric reset' }
          });
          showTimedBanner('invite-created-banner',
            `Face template cleared for ${res.worker.workerCode}. Click "Enroll Face" on their row to capture a new photo.`, 5000);
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not reset biometrics.');
        }
      });
    });
    tbody.querySelectorAll('.btn-enable-w').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          const res = await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/enable`, { method: 'POST' });
          showTimedBanner('invite-created-banner',
            `Re-enabled ${res.fullName} (${res.workerCode}) (${res.status}).`, 3000);
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not re-enable employee.');
        }
      });
    });
    tbody.querySelectorAll('.btn-disable-w').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/disable`, { method: 'POST' });
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not disable employee.');
        }
      });
    });
    tbody.querySelectorAll('.btn-delete-w').forEach((btn) => {
      btn.addEventListener('click', () => {
        const target = loadedWorkers.find((w) => w.id === btn.dataset.id);
        if (target) {
          openDeleteModal([target], false);
        }
      });
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="8" class="banner banner-error">${escapeHtml(err.message)}</td></tr>`;
  }
}

let auditPage = 0;
async function loadAuditLog() {
  const tbody = document.getElementById('audit-tbody');
  const eventType = document.getElementById('audit-event-type').value.trim();
  const fromDate = document.getElementById('audit-from-date').value;
  const toDate = document.getElementById('audit-to-date').value;
  tbody.innerHTML = '<tr><td colspan="6" class="helper-text">Loading audit events...</td></tr>';
  try {
    const page = await apiFetch(
      `/api/v1/admin/audit-events?page=${auditPage}&size=25&eventType=${encodeURIComponent(eventType)}&fromDate=${encodeURIComponent(fromDate)}&toDate=${encodeURIComponent(toDate)}`
    );
    tbody.innerHTML = '';
    page.content.forEach((ev) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${escapeHtml(ev.eventTimestampUtc)}</td>
        <td><strong>${escapeHtml(ev.eventType)}</strong></td>
        <td>${escapeHtml(ev.actorType)}${ev.actorId ? '<br><span class="helper-text">' + escapeHtml(ev.actorId) + '</span>' : ''}</td>
        <td>${escapeHtml(ev.subjectWorkerId || '-')}</td>
        <td><code>${escapeHtml(ev.correlationId || '')}</code></td>
        <td><code>${escapeHtml(ev.metadataJson || '{}')}</code></td>
      `;
      tbody.appendChild(tr);
    });
    document.getElementById('audit-pagination-info').textContent =
      `Page ${page.number + 1} of ${Math.max(1, page.totalPages)} (${page.totalElements} events)`;
    document.getElementById('btn-audit-prev').disabled = page.number <= 0;
    document.getElementById('btn-audit-next').disabled = page.number + 1 >= page.totalPages;
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6" class="banner banner-error">${escapeHtml(err.message)}</td></tr>`;
  }
}

function shiftMonth(delta) {
  const [y, m] = state.month.split('-').map(Number);
  const d = new Date(Date.UTC(y, m - 1 + delta, 1));
  state.month = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
  document.getElementById('input-month').value = state.month;
  state.page = 0;
  loadDashboardAndGrid();
  if (state.activeTab === 'settings') {
    loadCalendarAndSettings();
  } else if (state.activeTab === 'leaves') {
    loadLeavesOverview();
  } else if (state.activeTab === 'reports') {
    loadReportsPreview();
  }
}

async function loadLeavesOverview() {
  setGlobalError('');
  const query = document.getElementById('leaves-search')?.value.trim() || '';
  const team = document.getElementById('leaves-team-filter')?.value || '';
  const balanceBody = document.getElementById('leaves-balance-tbody');
  const entriesBody = document.getElementById('leaves-entries-tbody');
  if (balanceBody) balanceBody.innerHTML = '<tr><td colspan="11" class="helper-text">Loading employee leaves...</td></tr>';
  if (entriesBody) entriesBody.innerHTML = '<tr><td colspan="7" class="helper-text">Loading leave entries...</td></tr>';

  try {
    const data = await apiFetch(
      `/api/v1/admin/leaves?month=${encodeURIComponent(state.month)}&query=${encodeURIComponent(query)}&team=${encodeURIComponent(team)}`
    );
    document.getElementById('leaves-kpi-working-days').textContent = data.totalWorkingDays;
    document.getElementById('leaves-kpi-paid-quota').textContent = `${data.paidLeaveQuota} days`;
    document.getElementById('leaves-kpi-annual-quota').textContent = `${data.annualLeaveQuota} days`;
    document.getElementById('leaves-kpi-taken').textContent = data.totalApprovedTaken;
    document.getElementById('leaves-kpi-pending').textContent = data.totalPendingRemaining;
    document.getElementById('leaves-subtitle').textContent =
      `${data.month} (${data.timezoneId}) · Total working days this month: ${data.totalWorkingDays} · Auto-leave after cutoff: ${data.autoMarkLeaveAfterCutoff ? 'On' : 'Off'}`;

    const teamSel = document.getElementById('leaves-team-filter');
    if (teamSel) {
      teamSel.innerHTML = '<option value="">All teams</option>';
      (data.teams || []).forEach((t) => {
        const opt = document.createElement('option');
        opt.value = t;
        opt.textContent = t;
        if (t === team) opt.selected = true;
        teamSel.appendChild(opt);
      });
    }

    balanceBody.innerHTML = '';
    if (!data.employees || data.employees.length === 0) {
      balanceBody.innerHTML = '<tr><td colspan="11" class="helper-text">No matching employees found.</td></tr>';
    } else {
      data.employees.forEach((emp) => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><strong>${escapeHtml(emp.workerCode)}</strong></td>
          <td>${escapeHtml(emp.fullName)}</td>
          <td>${escapeHtml(emp.teamName || '-')}</td>
          <td><strong>${emp.applicableWorkingDays}</strong> / ${emp.monthWorkingDays}</td>
          <td>${emp.monthLeaveDays}</td>
          <td>${emp.paidLeaveTaken} / ${emp.paidLeaveQuota}</td>
          <td><strong>${emp.paidLeavePending}</strong></td>
          <td>${emp.annualLeaveTaken} / ${emp.annualLeaveQuota}</td>
          <td><strong>${emp.annualLeavePending}</strong></td>
          <td><strong>${emp.totalApprovedTaken}</strong></td>
          <td><strong>${emp.totalPendingRemaining}</strong></td>
        `;
        balanceBody.appendChild(tr);
      });
    }

    entriesBody.innerHTML = '';
    if (!data.entries || data.entries.length === 0) {
      entriesBody.innerHTML = '<tr><td colspan="7" class="helper-text">No leaves recorded in this month.</td></tr>';
    } else {
      data.entries.forEach((entry) => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><strong>${escapeHtml(entry.leaveDate)}</strong></td>
          <td>${escapeHtml(entry.workerCode)}</td>
          <td>${escapeHtml(entry.fullName)}</td>
          <td>${escapeHtml(entry.leaveTypeLabel)}</td>
          <td>${escapeHtml(entry.sourceLabel)}</td>
          <td><span class="status-badge status-ontime">Approved</span></td>
          <td class="helper-text">${escapeHtml(entry.notes || '-')}</td>
        `;
        entriesBody.appendChild(tr);
      });
    }
  } catch (err) {
    if (balanceBody) balanceBody.innerHTML = '';
    if (entriesBody) entriesBody.innerHTML = '';
    setGlobalError(err.message || 'Could not load leave overview.');
  }
}

async function loadReportsPreview() {
  setGlobalError('');
  const query = document.getElementById('reports-search').value.trim();
  const team = document.getElementById('reports-team-filter').value;
  const tbody = document.getElementById('reports-tbody');
  tbody.innerHTML = '<tr><td colspan="9" class="helper-text">Loading report preview...</td></tr>';

  try {
    const preview = await apiFetch(
      `/api/v1/admin/reports/preview?month=${encodeURIComponent(state.month)}&query=${encodeURIComponent(query)}&team=${encodeURIComponent(team)}`
    );
    document.getElementById('reports-subtitle').textContent =
      `${preview.month} (${preview.timezoneId}) · ${preview.configuredWorkingDays} configured working days`;

    const teamSel = document.getElementById('reports-team-filter');
    teamSel.innerHTML = '<option value="">All teams</option>';
    preview.teams.forEach((t) => {
      const opt = document.createElement('option');
      opt.value = t;
      opt.textContent = t;
      if (t === team) opt.selected = true;
      teamSel.appendChild(opt);
    });

    tbody.innerHTML = '';
    if (preview.workers.length === 0) {
      tbody.innerHTML = '<tr><td colspan="12" class="helper-text">No matching employees found.</td></tr>';
      return;
    }
    preview.workers.forEach((w) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${escapeHtml(w.workerCode)}</strong></td>
        <td>${escapeHtml(w.fullName)}</td>
        <td>${escapeHtml(w.teamName || '-')}</td>
        <td>${w.applicableWorkingDays}</td>
        <td><strong>${w.workedDays}</strong></td>
        <td>${w.onTimeDays}</td>
        <td>${w.lateDays}</td>
        <td>${w.absentDays}</td>
        <td>${w.leaveDays ?? 0}</td>
        <td>${w.holidayDays + w.weeklyOffDays}</td>
        <td>${w.earlyLogoutDays ?? 0}</td>
        <td>${w.extendedWorkHoursDays ?? 0}</td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    tbody.innerHTML = '';
    setGlobalError(err.message || 'Could not load report preview.');
  }
}

async function downloadCsvExport() {
  const btn = document.getElementById('btn-download-csv');
  const feedback = document.getElementById('reports-feedback');
  feedback.hidden = true;
  btn.disabled = true;
  const query = document.getElementById('reports-search')?.value.trim() || state.query || '';
  const team = document.getElementById('reports-team-filter')?.value || state.team || '';
  const url = `/api/v1/admin/reports/attendance.csv?month=${encodeURIComponent(state.month)}&query=${encodeURIComponent(query)}&team=${encodeURIComponent(team)}`;

  try {
    const resp = await fetch(url, { method: 'GET', credentials: 'same-origin' });
    if (!resp.ok) {
      throw new Error(`Export failed (${resp.status})`);
    }
    const blob = await resp.blob();
    const dlUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = dlUrl;
    a.download = `attendance_${state.month}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(dlUrl);
    showTimedBanner(feedback, `Downloaded attendance_${state.month}.csv`, 3000);
  } catch (err) {
    setGlobalError(err.message || 'Could not download CSV export.');
  } finally {
    btn.disabled = false;
  }
}

async function loadCalendarAndSettings() {
  setGlobalError('');
  try {
    const [cal, s] = await Promise.all([
      apiFetch(`/api/v1/admin/calendar?month=${encodeURIComponent(state.month)}`),
      apiFetch('/api/v1/admin/settings')
    ]);
    renderCalendarEditor(cal);

    document.getElementById('set-workplace-name').value = s.workplaceName;
    document.getElementById('set-timezone').value = s.timezoneId;
    state.shiftStartLocal = s.shiftStartLocal.slice(0, 5);
    document.getElementById('set-shift-start').value = state.shiftStartLocal;
    document.getElementById('set-grace-minutes').value = s.gracePeriodMinutes;
    document.getElementById('set-absence-cutoff').value = s.absenceCutoffLocal.slice(0, 5);
    document.getElementById('set-shift-end').value = (s.shiftEndLocal || s.absenceCutoffLocal).slice(0, 5);
    document.getElementById('set-proxy-cap').value = s.maxProxyPerActorPerDay;
    document.getElementById('set-proxy-enabled').checked = !!s.proxyEnabled;

    document.getElementById('set-geofence-enabled').checked = !!s.geofenceEnabled;
    document.getElementById('set-geofence-latitude').value = s.geofenceLatitude != null ? s.geofenceLatitude : '';
    document.getElementById('set-geofence-longitude').value = s.geofenceLongitude != null ? s.geofenceLongitude : '';
    document.getElementById('set-geofence-radius').value = s.geofenceRadiusMeters != null ? s.geofenceRadiusMeters : 50;
    syncGeofenceStatusUi(s);
    const paidQuotaEl = document.getElementById('set-paid-leave-quota');
    if (paidQuotaEl) paidQuotaEl.value = s.paidLeaveQuota != null ? s.paidLeaveQuota : 12;
    const annualQuotaEl = document.getElementById('set-annual-leave-quota');
    if (annualQuotaEl) annualQuotaEl.value = s.annualLeaveQuota != null ? s.annualLeaveQuota : 12;
    const autoLeaveEl = document.getElementById('set-auto-leave-cutoff');
    if (autoLeaveEl) autoLeaveEl.checked = s.autoMarkLeaveAfterCutoff !== false;

    const threshInput = document.getElementById('set-face-threshold');
    const modelInput = document.getElementById('set-face-model-version');
    threshInput.value = s.faceMatchThreshold;
    modelInput.value = s.faceModelVersion;
    threshInput.disabled = !s.canEditBiometricConfig;
    modelInput.disabled = !s.canEditBiometricConfig;
  } catch (err) {
    setGlobalError(err.message || 'Failed to load calendar and settings.');
  }
}

function renderCalendarEditor(cal) {
  document.getElementById('cal-summary').textContent =
    `${cal.month} (${cal.timezoneId}) · ${cal.workingDaysCount} configured working days`;
  const grid = document.getElementById('cal-days-grid');
  grid.innerHTML = '';

  cal.days.forEach((day) => {
    const card = document.createElement('div');
    card.className = `cal-day-card day-${day.dayType}`;
    card.dataset.date = day.date;
    const d = new Date(`${day.date}T00:00:00Z`);
    const weekday = d.toLocaleDateString('en-US', { weekday: 'short', timeZone: 'UTC' });

    card.innerHTML = `
      <div class="cal-day-head">
        <span>${escapeHtml(day.date)}</span>
        <span>${weekday}</span>
      </div>
      <select class="cal-day-type" aria-label="Day type for ${escapeHtml(day.date)}">
        <option value="WORKING" ${day.dayType === 'WORKING' ? 'selected' : ''}>Working</option>
        <option value="WEEKLY_OFF" ${day.dayType === 'WEEKLY_OFF' ? 'selected' : ''}>Weekly Off</option>
        <option value="HOLIDAY" ${day.dayType === 'HOLIDAY' ? 'selected' : ''}>Holiday</option>
      </select>
      <input class="cal-day-note" type="text" placeholder="Note (optional)" value="${escapeHtml(day.note || '')}">
    `;
    card.querySelector('.cal-day-type').addEventListener('change', (e) => {
      card.className = `cal-day-card day-${e.target.value}`;
    });
    grid.appendChild(card);
  });
}

function showSettingsFeedback(msg) {
  showTimedBanner('settings-feedback', msg, 3000);
}

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function enterApp(admin) {
  state.admin = admin;
  loginView.hidden = true;
  appShell.hidden = false;
  document.getElementById('admin-user-label').textContent = `${admin.displayName} (${admin.role})`;
  await loadDashboardAndGrid();
}

async function init() {
  const monthInput = document.getElementById('input-month');
  if (monthInput) monthInput.value = state.month;
  try {
    const admin = await apiFetch('/api/v1/admin/auth/me');
    await enterApp(admin);
  } catch {
    appShell.hidden = true;
    loginView.hidden = false;
  }
}

document.getElementById('form-admin-login').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errEl = document.getElementById('login-error');
  errEl.hidden = true;
  try {
    const admin = await apiFetch('/api/v1/admin/auth/login', {
      method: 'POST',
      body: {
        username: document.getElementById('login-username').value.trim(),
        password: document.getElementById('login-password').value
      }
    });
    await enterApp(admin);
  } catch (err) {
    errEl.textContent = err.message || 'Sign in failed.';
    errEl.hidden = false;
  }
});

document.getElementById('btn-admin-logout').addEventListener('click', async () => {
  stopEnrollCamera();
  await apiFetch('/api/v1/admin/auth/logout', { method: 'POST' }).catch(() => {});
  state.admin = null;
  appShell.hidden = true;
  loginView.hidden = false;
});

document.querySelectorAll('.nav-item').forEach((btn) => {
  btn.addEventListener('click', () => showTab(btn.dataset.tab));
});

document.getElementById('btn-open-nav').addEventListener('click', () => sidebar.classList.add('open'));
document.getElementById('btn-close-nav').addEventListener('click', () => sidebar.classList.remove('open'));
document.getElementById('btn-close-drawer').addEventListener('click', () => {
  document.getElementById('detail-drawer').hidden = true;
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    const camModal = document.getElementById('enroll-camera-modal');
    if (camModal && !camModal.hidden) {
      closeEnrollCameraModal();
    }
    const modal = document.getElementById('delete-worker-modal');
    if (modal && !modal.hidden) {
      closeDeleteModal();
    }
    const drawer = document.getElementById('detail-drawer');
    if (!drawer.hidden) {
      drawer.hidden = true;
    }
    sidebar.classList.remove('open');
  }
});

document.getElementById('input-month').addEventListener('change', (e) => {
  if (e.target.value) {
    state.month = e.target.value;
    state.page = 0;
    loadDashboardAndGrid();
    if (state.activeTab === 'settings') {
      loadCalendarAndSettings();
    } else if (state.activeTab === 'reports') {
      loadReportsPreview();
    }
  }
});

document.getElementById('btn-cal-save').addEventListener('click', async () => {
  setGlobalError('');
  const cards = Array.from(document.querySelectorAll('#cal-days-grid .cal-day-card'));
  const days = cards.map((card) => ({
    date: card.dataset.date,
    dayType: card.querySelector('.cal-day-type').value,
    note: card.querySelector('.cal-day-note').value.trim() || null
  }));
  try {
    const updated = await apiFetch(`/api/v1/admin/calendar?month=${encodeURIComponent(state.month)}`, {
      method: 'PUT',
      body: { days }
    });
    renderCalendarEditor(updated);
    showSettingsFeedback(`Saved ${updated.month} calendar (${updated.workingDaysCount} working days).`);
    await loadDashboardAndGrid();
  } catch (err) {
    setGlobalError(err.message || 'Could not save calendar.');
  }
});

document.getElementById('btn-cal-apply-weekly').addEventListener('click', async () => {
  setGlobalError('');
  const weeklyOffDays = Array.from(document.querySelectorAll('input[name="weeklyOff"]:checked'))
    .map((chk) => chk.value);
  try {
    const updated = await apiFetch(`/api/v1/admin/calendar?month=${encodeURIComponent(state.month)}`, {
      method: 'PUT',
      body: { weeklyOffDays }
    });
    renderCalendarEditor(updated);
    showSettingsFeedback(`Applied weekly off pattern to ${updated.month} (${updated.workingDaysCount} working days).`);
    await loadDashboardAndGrid();
  } catch (err) {
    setGlobalError(err.message || 'Could not apply weekly pattern.');
  }
});

document.getElementById('btn-cal-copy-prev').addEventListener('click', async () => {
  setGlobalError('');
  const [y, m] = state.month.split('-').map(Number);
  const prev = new Date(Date.UTC(y, m - 2, 1));
  const copyFromMonth = `${prev.getUTCFullYear()}-${String(prev.getUTCMonth() + 1).padStart(2, '0')}`;
  try {
    const updated = await apiFetch(`/api/v1/admin/calendar?month=${encodeURIComponent(state.month)}`, {
      method: 'PUT',
      body: { copyFromMonth }
    });
    renderCalendarEditor(updated);
    showSettingsFeedback(`Copied pattern from ${copyFromMonth} to ${updated.month} (${updated.workingDaysCount} working days).`);
    await loadDashboardAndGrid();
  } catch (err) {
    setGlobalError(err.message || 'Could not copy prior month pattern.');
  }
});

function syncGeofenceStatusUi(settingsObj) {
  const enabled = !!settingsObj.geofenceEnabled;
  const radius = settingsObj.geofenceRadiusMeters || 50;
  const badge = document.getElementById('geofence-state-badge');
  const toggleBtn = document.getElementById('btn-toggle-geofence');
  const locStatus = document.getElementById('geofence-loc-status');

  if (badge) {
    badge.className = `status-badge ${enabled ? 'status-ontime' : 'status-notdue'}`;
    badge.textContent = enabled ? `Enabled (${radius}m)` : 'Disabled';
  }
  if (toggleBtn) {
    toggleBtn.textContent = enabled ? 'Disable Geofencing' : 'Enable Geofencing';
    toggleBtn.className = enabled ? 'btn btn-danger' : 'btn btn-primary';
  }
  if (locStatus) {
    if (settingsObj.geofenceLatitude != null && settingsObj.geofenceLongitude != null) {
      locStatus.textContent = enabled
        ? `Active office zone: ${Number(settingsObj.geofenceLatitude).toFixed(6)}, ${Number(settingsObj.geofenceLongitude).toFixed(6)} (${radius}m radius).`
        : `Geofencing is currently disabled. Saved coordinates: ${Number(settingsObj.geofenceLatitude).toFixed(6)}, ${Number(settingsObj.geofenceLongitude).toFixed(6)} (${radius}m radius).`;
    } else {
      locStatus.textContent = enabled
        ? 'Enter exact office coordinates or tap Use My Current Location at the office.'
        : 'Geofencing is disabled. Employees can mark attendance without location restriction.';
    }
  }
}

function buildSettingsPayloadFromForm(overrideGeofenceEnabled = null) {
  const threshInput = document.getElementById('set-face-threshold');
  const modelInput = document.getElementById('set-face-model-version');
  const geofenceEnabled = overrideGeofenceEnabled !== null
    ? overrideGeofenceEnabled
    : document.getElementById('set-geofence-enabled').checked;
  const latRaw = document.getElementById('set-geofence-latitude').value.trim();
  const lngRaw = document.getElementById('set-geofence-longitude').value.trim();
  const radiusRaw = document.getElementById('set-geofence-radius').value.trim();

  const geofenceLatitude = latRaw !== '' ? Number(latRaw) : null;
  const geofenceLongitude = lngRaw !== '' ? Number(lngRaw) : null;
  const geofenceRadiusMeters = radiusRaw !== '' ? Number(radiusRaw) : 50;

  if (geofenceEnabled && (geofenceLatitude === null || geofenceLongitude === null || Number.isNaN(geofenceLatitude) || Number.isNaN(geofenceLongitude))) {
    throw new Error('Please enter both Office latitude and Office longitude before enabling geofencing.');
  }

  const body = {
    workplaceName: document.getElementById('set-workplace-name').value.trim(),
    timezoneId: document.getElementById('set-timezone').value.trim(),
    shiftStartLocal: document.getElementById('set-shift-start').value,
    gracePeriodMinutes: Number(document.getElementById('set-grace-minutes').value),
    absenceCutoffLocal: document.getElementById('set-absence-cutoff').value,
    shiftEndLocal: document.getElementById('set-shift-end').value,
    proxyEnabled: document.getElementById('set-proxy-enabled').checked,
    maxProxyPerActorPerDay: Number(document.getElementById('set-proxy-cap').value),
    proxyCapCountsSuccessfulOnly: true,
    geofenceEnabled,
    geofenceLatitude,
    geofenceLongitude,
    geofenceRadiusMeters,
    paidLeaveQuota: Number(document.getElementById('set-paid-leave-quota')?.value ?? 12),
    annualLeaveQuota: Number(document.getElementById('set-annual-leave-quota')?.value ?? 12),
    autoMarkLeaveAfterCutoff: document.getElementById('set-auto-leave-cutoff')?.checked ?? true
  };
  if (!threshInput.disabled) {
    body.faceMatchThreshold = Number(threshInput.value);
    body.faceModelVersion = modelInput.value.trim();
  }
  return body;
}

document.getElementById('set-geofence-latitude')?.addEventListener('paste', (e) => {
  const text = (e.clipboardData || window.clipboardData)?.getData('text') || '';
  const match = text.trim().match(/^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$/);
  if (match) {
    e.preventDefault();
    document.getElementById('set-geofence-latitude').value = match[1];
    document.getElementById('set-geofence-longitude').value = match[2];
  }
});

document.getElementById('btn-toggle-geofence')?.addEventListener('click', async () => {
  setGlobalError('');
  const currentlyChecked = document.getElementById('set-geofence-enabled').checked;
  const targetEnabled = !currentlyChecked;
  let body;
  try {
    body = buildSettingsPayloadFromForm(targetEnabled);
  } catch (err) {
    setGlobalError(err.message);
    return;
  }
  const btn = document.getElementById('btn-toggle-geofence');
  btn.disabled = true;
  try {
    const saved = await apiFetch('/api/v1/admin/settings', { method: 'PUT', body });
    document.getElementById('set-geofence-enabled').checked = !!saved.geofenceEnabled;
    syncGeofenceStatusUi(saved);
    showSettingsFeedback(saved.geofenceEnabled
      ? `Office geofencing enabled (${saved.geofenceRadiusMeters || 50}m radius).`
      : 'Office geofencing disabled. Employees can mark attendance from any location.');
  } catch (err) {
    setGlobalError(err.message || 'Could not update geofencing status.');
  } finally {
    btn.disabled = false;
  }
});

document.getElementById('set-geofence-enabled')?.addEventListener('change', (e) => {
  syncGeofenceStatusUi({
    geofenceEnabled: e.target.checked,
    geofenceLatitude: document.getElementById('set-geofence-latitude').value.trim() || null,
    geofenceLongitude: document.getElementById('set-geofence-longitude').value.trim() || null,
    geofenceRadiusMeters: Number(document.getElementById('set-geofence-radius').value) || 50
  });
});

document.getElementById('btn-use-current-location')?.addEventListener('click', () => {
  const statusEl = document.getElementById('geofence-loc-status');
  const btn = document.getElementById('btn-use-current-location');
  if (!navigator.geolocation) {
    if (statusEl) statusEl.textContent = 'Geolocation is not supported in this browser.';
    return;
  }
  btn.disabled = true;
  if (statusEl) statusEl.textContent = 'Fetching current GPS coordinates...';
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      btn.disabled = false;
      const lat = Number(pos.coords.latitude.toFixed(6));
      const lng = Number(pos.coords.longitude.toFixed(6));
      document.getElementById('set-geofence-latitude').value = lat;
      document.getElementById('set-geofence-longitude').value = lng;
      if (statusEl) {
        statusEl.textContent = `Captured: ${lat}, ${lng} (±${Math.round(pos.coords.accuracy)}m GPS accuracy). Click Save Settings to apply.`;
      }
    },
    (err) => {
      btn.disabled = false;
      if (statusEl) {
        statusEl.textContent = err.code === 1
          ? 'Location permission denied. Allow location access in your browser or enter coordinates manually.'
          : 'Could not read GPS location. Ensure GPS/location is enabled or enter coordinates manually.';
      }
    },
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 }
  );
});

document.getElementById('form-settings').addEventListener('submit', async (e) => {
  e.preventDefault();
  setGlobalError('');
  let body;
  try {
    body = buildSettingsPayloadFromForm(null);
  } catch (err) {
    setGlobalError(err.message);
    return;
  }
  try {
    const saved = await apiFetch('/api/v1/admin/settings', { method: 'PUT', body });
    state.shiftStartLocal = body.shiftStartLocal;
    document.getElementById('set-geofence-enabled').checked = !!saved.geofenceEnabled;
    syncGeofenceStatusUi(saved);
    showSettingsFeedback('Workplace and attendance settings saved.');
    await loadDashboardAndGrid();
  } catch (err) {
    setGlobalError(err.message || 'Could not save settings.');
  }
});

document.getElementById('btn-prev-month').addEventListener('click', () => shiftMonth(-1));
document.getElementById('btn-next-month').addEventListener('click', () => shiftMonth(1));

let searchTimer = null;
document.getElementById('grid-search').addEventListener('input', (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.query = e.target.value;
    state.page = 0;
    loadDashboardAndGrid();
  }, 250);
});

document.getElementById('grid-team-filter').addEventListener('change', (e) => {
  state.team = e.target.value;
  state.page = 0;
  loadDashboardAndGrid();
});

document.getElementById('btn-page-prev').addEventListener('click', () => {
  if (state.page > 0) {
    state.page--;
    loadDashboardAndGrid();
  }
});

document.getElementById('btn-page-next').addEventListener('click', () => {
  state.page++;
  loadDashboardAndGrid();
});

document.getElementById('btn-refresh-attendance').addEventListener('click', async () => {
  const btn = document.getElementById('btn-refresh-attendance');
  btn.disabled = true;
  btn.classList.add('spinning');
  try {
    await loadDashboardAndGrid();
  } finally {
    btn.classList.remove('spinning');
    btn.disabled = false;
  }
});

document.getElementById('btn-export-shortcut').addEventListener('click', () => {
  showTab('reports');
});

document.getElementById('btn-download-csv').addEventListener('click', downloadCsvExport);
document.getElementById('reports-team-filter').addEventListener('change', loadReportsPreview);
let reportsSearchTimer = null;
document.getElementById('reports-search').addEventListener('input', () => {
  clearTimeout(reportsSearchTimer);
  reportsSearchTimer = setTimeout(loadReportsPreview, 250);
});

document.getElementById('btn-leaves-refresh')?.addEventListener('click', loadLeavesOverview);
document.getElementById('btn-leaves-settings')?.addEventListener('click', () => showTab('settings'));
document.getElementById('leaves-team-filter')?.addEventListener('change', loadLeavesOverview);
let leavesSearchTimer = null;
document.getElementById('leaves-search')?.addEventListener('input', () => {
  clearTimeout(leavesSearchTimer);
  leavesSearchTimer = setTimeout(loadLeavesOverview, 250);
});

const enrollState = {
  stream: null,
  imageBase64: null,
  batchCount: 0,
  targetWorker: null,
  uploadFromModal: false
};

function setEnrollError(msg) {
  const el = document.getElementById('enroll-error');
  if (!el) return;
  if (!msg) {
    el.hidden = true;
    el.textContent = '';
  } else {
    el.textContent = msg;
    el.hidden = false;
  }
}

function setEnrollCamModalError(msg) {
  const el = document.getElementById('enroll-cam-error');
  if (!el) return;
  if (!msg) {
    el.hidden = true;
    el.textContent = '';
  } else {
    el.textContent = msg;
    el.hidden = false;
  }
}

function updateEnrollPreviewUi() {
  const wrap = document.getElementById('enroll-preview-wrap');
  const img = document.getElementById('enroll-preview-img');
  const startCamBtn = document.getElementById('btn-enroll-start-cam');
  const clearBtn = document.getElementById('btn-enroll-clear-photo');

  if (enrollState.imageBase64) {
    img.src = `data:image/jpeg;base64,${enrollState.imageBase64}`;
    wrap.hidden = false;
    clearBtn.hidden = false;
    startCamBtn.textContent = 'Retake Picture';
  } else {
    img.removeAttribute('src');
    wrap.hidden = true;
    clearBtn.hidden = true;
    startCamBtn.textContent = 'Start Camera';
  }
}

function stopEnrollCamera() {
  if (enrollState.stream) {
    enrollState.stream.getTracks().forEach((t) => t.stop());
    enrollState.stream = null;
  }
  const video = document.getElementById('enroll-video');
  if (video) {
    video.srcObject = null;
  }
}

function closeEnrollCameraModal() {
  stopEnrollCamera();
  enrollState.targetWorker = null;
  enrollState.uploadFromModal = false;
  const modal = document.getElementById('enroll-camera-modal');
  if (modal) {
    modal.hidden = true;
  }
}

async function openEnrollCameraModal(targetWorker = null) {
  setEnrollError('');
  setEnrollCamModalError('');
  stopEnrollCamera();
  enrollState.targetWorker = targetWorker;
  enrollState.uploadFromModal = false;

  const titleEl = document.getElementById('enroll-cam-modal-title');
  const consentRow = document.getElementById('enroll-cam-consent-row');
  const consentChk = document.getElementById('enroll-cam-consent');
  const uploadBtn = document.getElementById('btn-enroll-cam-upload');
  const snapBtn = document.getElementById('btn-enroll-snap');

  if (targetWorker) {
    titleEl.textContent = `Enroll Face — ${targetWorker.fullName} (${targetWorker.workerCode})`;
    consentRow.hidden = false;
    consentChk.checked = false;
    uploadBtn.hidden = false;
    snapBtn.textContent = 'Picture & Save';
  } else {
    titleEl.textContent = 'Capture Employee Photo';
    consentRow.hidden = true;
    uploadBtn.hidden = true;
    snapBtn.textContent = 'Picture';
  }

  const modal = document.getElementById('enroll-camera-modal');
  modal.hidden = false;

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    setEnrollCamModalError(targetWorker
      ? 'Live webcam is unavailable here. Click "Upload Photo" below instead.'
      : 'Live webcam is unavailable here. Close this dialog and use "Upload Photo" instead.');
    return;
  }
  try {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } },
        audio: false
      });
    } catch {
      stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
    }
    enrollState.stream = stream;
    const video = document.getElementById('enroll-video');
    video.srcObject = stream;
    await video.play().catch(() => {});
  } catch {
    setEnrollCamModalError('Camera permission denied or unavailable. You can still use "Upload Photo".');
  }
}

async function submitRowFaceEnrollment(worker, imageBase64) {
  const snapBtn = document.getElementById('btn-enroll-snap');
  snapBtn.disabled = true;
  snapBtn.textContent = 'Saving...';
  try {
    const enrolled = await apiFetch('/api/v1/admin/workers/enroll', {
      method: 'POST',
      body: {
        workerId: worker.id,
        fullName: worker.fullName,
        teamName: worker.teamName,
        consentGiven: true,
        imageBase64
      }
    });
    closeEnrollCameraModal();
    showTimedBanner('invite-created-banner',
      `Face enrolled for ${enrolled.fullName} (${enrolled.workerCode}).`, 3000);
    await loadWorkersTable();
  } catch (err) {
    setEnrollCamModalError(err.message || 'Could not enroll employee face.');
  } finally {
    snapBtn.disabled = false;
    snapBtn.textContent = enrollState.targetWorker ? 'Picture & Save' : 'Picture';
  }
}

function compressFileToJpegBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Could not read the selected photo.'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Unsupported image format. Please choose a JPG or PNG photo.'));
      img.onload = () => {
        const maxSide = 1280;
        const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
        const w = Math.max(1, Math.round(img.width * scale));
        const h = Math.max(1, Math.round(img.height * scale));
        const canvas = document.getElementById('enroll-canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, w, h);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
        resolve(dataUrl.split(',')[1]);
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}

document.getElementById('btn-toggle-enroll-form').addEventListener('click', () => {
  const form = document.getElementById('form-enroll-worker');
  const inviteForm = document.getElementById('form-create-worker');
  inviteForm.hidden = true;
  form.hidden = !form.hidden;
  if (!form.hidden) {
    setEnrollError('');
    updateEnrollPreviewUi();
    document.getElementById('enroll-worker-code').focus();
  } else {
    closeEnrollCameraModal();
  }
});

document.getElementById('btn-enroll-close').addEventListener('click', () => {
  closeEnrollCameraModal();
  document.getElementById('form-enroll-worker').hidden = true;
});

document.getElementById('btn-enroll-start-cam').addEventListener('click', () => openEnrollCameraModal(null));
document.getElementById('btn-enroll-cam-close').addEventListener('click', closeEnrollCameraModal);
document.getElementById('btn-enroll-cam-cancel').addEventListener('click', closeEnrollCameraModal);

document.getElementById('btn-enroll-cam-upload').addEventListener('click', () => {
  setEnrollCamModalError('');
  if (enrollState.targetWorker && !document.getElementById('enroll-cam-consent').checked) {
    setEnrollCamModalError('Please confirm the employee gave consent before enrolling.');
    return;
  }
  enrollState.uploadFromModal = true;
  document.getElementById('enroll-file-input').click();
});

document.getElementById('btn-enroll-snap').addEventListener('click', async () => {
  setEnrollCamModalError('');
  if (enrollState.targetWorker && !document.getElementById('enroll-cam-consent').checked) {
    setEnrollCamModalError('Please confirm the employee gave consent before enrolling.');
    return;
  }
  const video = document.getElementById('enroll-video');
  if (!video.videoWidth || !video.videoHeight) {
    setEnrollCamModalError('Camera frame is not ready yet. Please wait a moment and try again.');
    return;
  }
  const canvas = document.getElementById('enroll-canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
  const b64 = canvas.toDataURL('image/jpeg', 0.9).split(',')[1];
  if (enrollState.targetWorker) {
    await submitRowFaceEnrollment(enrollState.targetWorker, b64);
    return;
  }
  enrollState.imageBase64 = b64;
  closeEnrollCameraModal();
  setEnrollError('');
  updateEnrollPreviewUi();
});

document.getElementById('btn-enroll-upload').addEventListener('click', () => {
  enrollState.uploadFromModal = false;
  document.getElementById('enroll-file-input').click();
});

document.getElementById('enroll-file-input').addEventListener('change', async (e) => {
  const file = e.target.files?.[0];
  e.target.value = '';
  if (!file) {
    enrollState.uploadFromModal = false;
    return;
  }
  try {
    const b64 = await compressFileToJpegBase64(file);
    if (enrollState.uploadFromModal && enrollState.targetWorker) {
      enrollState.uploadFromModal = false;
      await submitRowFaceEnrollment(enrollState.targetWorker, b64);
      return;
    }
    enrollState.uploadFromModal = false;
    setEnrollError('');
    enrollState.imageBase64 = b64;
    updateEnrollPreviewUi();
  } catch (err) {
    if (enrollState.uploadFromModal) {
      enrollState.uploadFromModal = false;
      setEnrollCamModalError(err.message || 'Could not load image.');
    } else {
      setEnrollError(err.message || 'Could not load image.');
    }
  }
});

document.getElementById('btn-enroll-clear-photo').addEventListener('click', () => {
  enrollState.imageBase64 = null;
  updateEnrollPreviewUi();
});

document.getElementById('form-enroll-worker').addEventListener('submit', async (e) => {
  e.preventDefault();
  setEnrollError('');

  const workerCode = document.getElementById('enroll-worker-code').value.trim();
  const fullName = document.getElementById('enroll-worker-name').value.trim();
  const teamName = document.getElementById('enroll-worker-team').value.trim() || null;
  const consentGiven = document.getElementById('enroll-consent-checkbox').checked;

  if (!workerCode) {
    setEnrollError('Please enter an employee code (e.g. W001).');
    return;
  }
  if (!fullName) {
    setEnrollError('Please enter the employee’s full name.');
    return;
  }
  if (!consentGiven) {
    setEnrollError('Please confirm the employee gave consent before registering.');
    return;
  }
  if (!enrollState.imageBase64) {
    setEnrollError('Please click Start Camera to capture a photo or upload one first.');
    return;
  }

  const submitBtn = document.getElementById('btn-enroll-submit');
  submitBtn.disabled = true;
  submitBtn.textContent = 'Enrolling face...';

  try {
    const enrolled = await apiFetch('/api/v1/admin/workers/enroll', {
      method: 'POST',
      body: {
        workerCode,
        fullName,
        teamName,
        consentGiven: true,
        imageBase64: enrollState.imageBase64
      }
    });

    enrollState.batchCount += 1;
    showTimedBanner('enroll-batch-banner',
      `Registered ${enrolled.fullName} (${enrolled.workerCode}) — ${enrollState.batchCount} employee(s) registered in this session. Ready for the next employee.`, 3000);
    showTimedBanner('invite-created-banner',
      `Employee ${enrolled.fullName} (${enrolled.workerCode}) registered with face template.`, 3000);

    const codeInput = document.getElementById('enroll-worker-code');
    codeInput.value = '';
    document.getElementById('enroll-worker-name').value = '';
    document.getElementById('enroll-worker-team').value = '';
    document.getElementById('enroll-consent-checkbox').checked = false;
    enrollState.imageBase64 = null;
    updateEnrollPreviewUi();

    await loadWorkersTable();
    codeInput.focus();
  } catch (err) {
    setEnrollError(err.message || 'Could not enroll employee photo.');
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Register & Next Employee';
  }
});

document.getElementById('btn-toggle-invite-form').addEventListener('click', () => {
  const form = document.getElementById('form-create-worker');
  document.getElementById('form-enroll-worker').hidden = true;
  stopEnrollCamera();
  form.hidden = !form.hidden;
});

document.getElementById('workers-status-filter').addEventListener('change', loadWorkersTable);
let workersSearchTimer = null;
document.getElementById('workers-search').addEventListener('input', () => {
  clearTimeout(workersSearchTimer);
  workersSearchTimer = setTimeout(loadWorkersTable, 250);
});

document.getElementById('btn-audit-filter').addEventListener('click', () => {
  auditPage = 0;
  loadAuditLog();
});
document.getElementById('btn-audit-prev').addEventListener('click', () => {
  if (auditPage > 0) {
    auditPage--;
    loadAuditLog();
  }
});
document.getElementById('btn-audit-next').addEventListener('click', () => {
  auditPage++;
  loadAuditLog();
});

document.getElementById('form-create-worker').addEventListener('submit', async (e) => {
  e.preventDefault();
  const banner = document.getElementById('invite-created-banner');
  banner.hidden = true;
  try {
    const res = await apiFetch('/api/v1/admin/workers', {
      method: 'POST',
      body: {
        workerCode: document.getElementById('new-worker-code').value.trim(),
        fullName: document.getElementById('new-worker-name').value.trim(),
        teamName: document.getElementById('new-worker-team').value.trim() || null
      }
    });
    showTimedBanner(banner, `Employee ${res.worker.workerCode} invited. One-time invitation code: ${res.invitationCode}`, 8000);
    e.target.reset();
    await loadWorkersTable();
  } catch (err) {
    setGlobalError(err.message || 'Could not create employee invitation.');
  }
});

document.getElementById('workers-select-all').addEventListener('change', (e) => {
  if (e.target.checked) {
    loadedWorkers.forEach((w) => selectedWorkerIds.add(w.id));
  } else {
    loadedWorkers.forEach((w) => selectedWorkerIds.delete(w.id));
  }
  document.querySelectorAll('.worker-select-cb').forEach((chk) => {
    chk.checked = selectedWorkerIds.has(chk.dataset.id);
  });
  syncWorkersBulkUi();
});

document.getElementById('btn-clear-selection').addEventListener('click', () => {
  selectedWorkerIds.clear();
  document.querySelectorAll('.worker-select-cb').forEach((chk) => {
    chk.checked = false;
  });
  syncWorkersBulkUi();
});

document.getElementById('btn-bulk-delete-workers').addEventListener('click', () => {
  const targets = loadedWorkers.filter((w) => selectedWorkerIds.has(w.id));
  if (targets.length > 0) {
    openDeleteModal(targets, true);
  }
});

document.getElementById('btn-delete-modal-close').addEventListener('click', closeDeleteModal);
document.getElementById('btn-delete-cancel').addEventListener('click', closeDeleteModal);

document.getElementById('form-delete-workers').addEventListener('submit', async (e) => {
  e.preventDefault();
  if (pendingDeleteTargets.length === 0) {
    closeDeleteModal();
    return;
  }
  const errEl = document.getElementById('delete-modal-error');
  errEl.hidden = true;
  const reason = document.getElementById('delete-reason-input').value.trim();
  const adminPassword = document.getElementById('delete-admin-password').value;

  if (pendingDeleteBulk && !reason) {
    errEl.textContent = 'Please enter a reason for bulk deletion.';
    errEl.hidden = false;
    return;
  }
  if (!adminPassword) {
    errEl.textContent = 'Administrator password is required.';
    errEl.hidden = false;
    return;
  }

  const submitBtn = document.getElementById('btn-delete-confirm');
  submitBtn.disabled = true;
  submitBtn.textContent = 'Deleting...';

  try {
    let result;
    if (pendingDeleteBulk) {
      result = await apiFetch('/api/v1/admin/workers/bulk-delete', {
        method: 'POST',
        body: {
          workerIds: pendingDeleteTargets.map((w) => w.id),
          reason,
          adminPassword
        }
      });
    } else {
      result = await apiFetch(`/api/v1/admin/workers/${pendingDeleteTargets[0].id}/delete`, {
        method: 'POST',
        body: {
          adminPassword,
          reason: reason || null
        }
      });
    }
    const deletedIds = new Set(pendingDeleteTargets.map((w) => w.id));
    deletedIds.forEach((id) => selectedWorkerIds.delete(id));
    closeDeleteModal();
    showTimedBanner('invite-created-banner',
      `Deleted ${result.deletedCount} employee(s): ${result.deletedWorkerCodes.join(', ')}.`, 3000);
    await Promise.all([loadWorkersTable(), loadDashboardAndGrid()]);
  } catch (err) {
    errEl.textContent = err.message || 'Could not delete employee(s).';
    errEl.hidden = false;
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Confirm Delete';
  }
});

init();
