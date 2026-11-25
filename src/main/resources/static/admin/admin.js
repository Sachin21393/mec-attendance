import { apiFetch } from '/shared/api.js';

const state = {
  admin: null,
  month: '2026-11',
  query: '',
  team: '',
  page: 0,
  size: 25,
  activeTab: 'dashboard'
};

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

  if (tab === 'workers') {
    loadWorkersTable();
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
    document.getElementById('kpi-notdue-today').textContent = dashboard.notDueTodayCount;

    document.getElementById('grid-subtitle').textContent =
      `${grid.month} · ${grid.configuredWorkingDays} configured working days · ${grid.totalWorkers} workers`;

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
      `Page ${grid.page + 1} of ${Math.max(1, grid.totalPages)} (${grid.totalWorkers} workers)`;
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
  workerTh.textContent = 'Worker';
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
      const proxyPart = cell.method === 'PROXY' && cell.actorWorkerName ? ` (proxy by ${cell.actorWorkerName})` : '';
      btn.setAttribute('aria-label', `${row.fullName}, ${cell.date}: ${labelForState(cell.state)}${timePart}${proxyPart}`);
      btn.title = `${cell.date}: ${labelForState(cell.state)}${timePart}${proxyPart}`;

      if (cell.method === 'PROXY') {
        const dot = document.createElement('span');
        dot.className = 'proxy-dot';
        dot.textContent = 'P';
        btn.appendChild(dot);
      }

      if (cell.recordId) {
        btn.addEventListener('click', () => openDetailDrawer(cell.recordId));
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
      <div class="detail-kv"><span>Worker</span><strong>${escapeHtml(d.subjectWorkerName)} (${escapeHtml(d.subjectWorkerCode)})</strong></div>
      <div class="detail-kv"><span>Date</span><strong>${escapeHtml(d.attendanceDate)}</strong></div>
      <div class="detail-kv"><span>Local Check-in</span><strong>${escapeHtml(d.checkInLocalTime)} (${escapeHtml(d.timezoneId)})</strong></div>
      <div class="detail-kv"><span>UTC Timestamp</span><span>${escapeHtml(d.checkInAtUtc)}</span></div>
      <div class="detail-kv"><span>Status</span><strong>${escapeHtml(d.attendanceStatus)}</strong></div>
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

function openManualExceptionDrawer(workerId, fullName, workerCode, date) {
  const drawer = document.getElementById('detail-drawer');
  const body = document.getElementById('drawer-body');
  drawer.hidden = false;
  body.innerHTML = `
    <p class="helper-text" style="margin-bottom: 12px;">
      Record audited manual attendance when a worker cannot use face recognition (camera unavailable, disability, or consent fallback).
    </p>
    <div class="detail-kv"><span>Worker</span><strong>${escapeHtml(fullName)} (${escapeHtml(workerCode)})</strong></div>
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
        <input id="man-time" class="input" type="time" value="09:00">
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

async function loadWorkersTable() {
  const tbody = document.getElementById('workers-tbody');
  const query = document.getElementById('workers-search')?.value.trim() || '';
  const status = document.getElementById('workers-status-filter')?.value || '';
  tbody.innerHTML = '<tr><td colspan="7" class="helper-text">Loading workers...</td></tr>';
  try {
    const page = await apiFetch(
      `/api/v1/admin/workers?size=100&query=${encodeURIComponent(query)}&status=${encodeURIComponent(status)}`
    );
    tbody.innerHTML = '';
    if (page.content.length === 0) {
      tbody.innerHTML = '<tr><td colspan="7" class="helper-text">No workers match the current filter.</td></tr>';
      return;
    }
    page.content.forEach((w) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${escapeHtml(w.workerCode)}</strong></td>
        <td>${escapeHtml(w.fullName)}</td>
        <td>${escapeHtml(w.teamName || '-')}</td>
        <td>${escapeHtml(w.status)}</td>
        <td>${w.biometricsEnrolled ? 'Enrolled' : 'Not enrolled'}</td>
        <td>${w.activeSessions}</td>
        <td>
          <div style="display:flex;gap:6px;flex-wrap:wrap;">
            <button type="button" class="btn btn-secondary btn-revoke-sess" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Revoke Sessions</button>
            <button type="button" class="btn btn-secondary btn-reset-bio" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Reset Face</button>
            ${w.status !== 'DISABLED' ? `<button type="button" class="btn btn-secondary btn-disable-w" data-id="${w.id}" style="min-height:34px;padding:4px 10px;font-size:0.8rem;">Disable</button>` : ''}
          </div>
        </td>
      `;
      tbody.appendChild(tr);
    });

    tbody.querySelectorAll('.btn-revoke-sess').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/sessions/revoke`, { method: 'POST' });
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not revoke worker sessions.');
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
          const banner = document.getElementById('invite-created-banner');
          banner.textContent = `Biometric template cleared for ${res.worker.workerCode}. New one-time invitation code: ${res.invitationCode}`;
          banner.hidden = false;
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not reset biometrics.');
        }
      });
    });
    tbody.querySelectorAll('.btn-disable-w').forEach((btn) => {
      btn.addEventListener('click', async () => {
        try {
          await apiFetch(`/api/v1/admin/workers/${btn.dataset.id}/disable`, { method: 'POST' });
          await loadWorkersTable();
        } catch (err) {
          setGlobalError(err.message || 'Could not disable worker.');
        }
      });
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="7" class="banner banner-error">${escapeHtml(err.message)}</td></tr>`;
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
  } else if (state.activeTab === 'reports') {
    loadReportsPreview();
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
      tbody.innerHTML = '<tr><td colspan="9" class="helper-text">No matching workers found.</td></tr>';
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
        <td>${w.holidayDays + w.weeklyOffDays}</td>
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
    feedback.textContent = `Downloaded attendance_${state.month}.csv`;
    feedback.hidden = false;
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
    document.getElementById('set-shift-start').value = s.shiftStartLocal.slice(0, 5);
    document.getElementById('set-grace-minutes').value = s.gracePeriodMinutes;
    document.getElementById('set-absence-cutoff').value = s.absenceCutoffLocal.slice(0, 5);
    document.getElementById('set-proxy-cap').value = s.maxProxyPerActorPerDay;
    document.getElementById('set-proxy-enabled').checked = !!s.proxyEnabled;

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
  const banner = document.getElementById('settings-feedback');
  banner.textContent = msg;
  banner.hidden = false;
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

document.getElementById('form-settings').addEventListener('submit', async (e) => {
  e.preventDefault();
  setGlobalError('');
  const threshInput = document.getElementById('set-face-threshold');
  const modelInput = document.getElementById('set-face-model-version');
  const body = {
    workplaceName: document.getElementById('set-workplace-name').value.trim(),
    timezoneId: document.getElementById('set-timezone').value.trim(),
    shiftStartLocal: document.getElementById('set-shift-start').value,
    gracePeriodMinutes: Number(document.getElementById('set-grace-minutes').value),
    absenceCutoffLocal: document.getElementById('set-absence-cutoff').value,
    proxyEnabled: document.getElementById('set-proxy-enabled').checked,
    maxProxyPerActorPerDay: Number(document.getElementById('set-proxy-cap').value),
    proxyCapCountsSuccessfulOnly: true
  };
  if (!threshInput.disabled) {
    body.faceMatchThreshold = Number(threshInput.value);
    body.faceModelVersion = modelInput.value.trim();
  }
  try {
    await apiFetch('/api/v1/admin/settings', { method: 'PUT', body });
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

const enrollState = {
  stream: null,
  imageBase64: null,
  batchCount: 0,
  directory: []
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

function updateEnrollPreviewUi() {
  const video = document.getElementById('enroll-video');
  const img = document.getElementById('enroll-preview-img');
  const placeholder = document.getElementById('enroll-photo-placeholder');
  const snapBtn = document.getElementById('btn-enroll-snap');
  const clearBtn = document.getElementById('btn-enroll-clear-photo');

  if (enrollState.imageBase64) {
    img.src = `data:image/jpeg;base64,${enrollState.imageBase64}`;
    img.hidden = false;
    video.hidden = true;
    placeholder.hidden = true;
    snapBtn.hidden = true;
    clearBtn.hidden = false;
  } else if (enrollState.stream) {
    img.hidden = true;
    video.hidden = false;
    placeholder.hidden = true;
    snapBtn.hidden = false;
    clearBtn.hidden = true;
  } else {
    img.hidden = true;
    video.hidden = true;
    placeholder.hidden = false;
    snapBtn.hidden = true;
    clearBtn.hidden = true;
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
  updateEnrollPreviewUi();
}

async function startEnrollCamera() {
  setEnrollError('');
  enrollState.imageBase64 = null;
  stopEnrollCamera();
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    setEnrollError('Live webcam is unavailable here. Use "Upload / Phone Photo" instead.');
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
    updateEnrollPreviewUi();
  } catch {
    setEnrollError('Camera permission denied or unavailable. You can still use "Upload / Phone Photo".');
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

async function refreshEnrollExistingSelect() {
  try {
    const page = await apiFetch('/api/v1/admin/workers?size=100');
    enrollState.directory = page.content.filter((w) => !w.biometricsEnrolled && w.status !== 'DISABLED');
    const sel = document.getElementById('enroll-existing-worker');
    const currentVal = sel.value;
    sel.innerHTML = '<option value="">Create new worker (enter details below)</option>';
    enrollState.directory.forEach((w) => {
      const opt = document.createElement('option');
      opt.value = w.id;
      opt.textContent = `${w.workerCode} — ${w.fullName}${w.teamName ? ' (' + w.teamName + ')' : ''} [${w.status}]`;
      if (w.id === currentVal) opt.selected = true;
      sel.appendChild(opt);
    });
  } catch {
    // Non-fatal
  }
}

document.getElementById('btn-toggle-enroll-form').addEventListener('click', async () => {
  const form = document.getElementById('form-enroll-worker');
  const inviteForm = document.getElementById('form-create-worker');
  inviteForm.hidden = true;
  form.hidden = !form.hidden;
  if (!form.hidden) {
    setEnrollError('');
    await refreshEnrollExistingSelect();
    updateEnrollPreviewUi();
  } else {
    stopEnrollCamera();
  }
});

document.getElementById('btn-enroll-close').addEventListener('click', () => {
  stopEnrollCamera();
  document.getElementById('form-enroll-worker').hidden = true;
});

document.getElementById('enroll-existing-worker').addEventListener('change', (e) => {
  const selectedId = e.target.value;
  const codeInput = document.getElementById('enroll-worker-code');
  const nameInput = document.getElementById('enroll-worker-name');
  const teamInput = document.getElementById('enroll-worker-team');
  const found = enrollState.directory.find((w) => w.id === selectedId);
  if (found) {
    codeInput.value = found.workerCode;
    codeInput.disabled = true;
    nameInput.value = found.fullName;
    teamInput.value = found.teamName || '';
  } else {
    codeInput.disabled = false;
    codeInput.value = '';
    nameInput.value = '';
    teamInput.value = '';
  }
});

document.getElementById('btn-enroll-start-cam').addEventListener('click', startEnrollCamera);

document.getElementById('btn-enroll-snap').addEventListener('click', () => {
  const video = document.getElementById('enroll-video');
  if (!video.videoWidth || !video.videoHeight) {
    setEnrollError('Camera frame is not ready yet.');
    return;
  }
  const canvas = document.getElementById('enroll-canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
  enrollState.imageBase64 = canvas.toDataURL('image/jpeg', 0.9).split(',')[1];
  setEnrollError('');
  updateEnrollPreviewUi();
});

document.getElementById('btn-enroll-upload').addEventListener('click', () => {
  document.getElementById('enroll-file-input').click();
});

document.getElementById('enroll-file-input').addEventListener('change', async (e) => {
  const file = e.target.files?.[0];
  e.target.value = '';
  if (!file) return;
  setEnrollError('');
  try {
    enrollState.imageBase64 = await compressFileToJpegBase64(file);
    updateEnrollPreviewUi();
  } catch (err) {
    setEnrollError(err.message || 'Could not load image.');
  }
});

document.getElementById('btn-enroll-clear-photo').addEventListener('click', () => {
  enrollState.imageBase64 = null;
  updateEnrollPreviewUi();
});

document.getElementById('form-enroll-worker').addEventListener('submit', async (e) => {
  e.preventDefault();
  setEnrollError('');

  const selectedId = document.getElementById('enroll-existing-worker').value || null;
  const workerCode = document.getElementById('enroll-worker-code').value.trim();
  const fullName = document.getElementById('enroll-worker-name').value.trim();
  const teamName = document.getElementById('enroll-worker-team').value.trim() || null;
  const consentGiven = document.getElementById('enroll-consent-checkbox').checked;

  if (!selectedId && !workerCode) {
    setEnrollError('Please enter a worker code (e.g. W011).');
    return;
  }
  if (!fullName) {
    setEnrollError('Please enter the worker’s full name.');
    return;
  }
  if (!consentGiven) {
    setEnrollError('Please confirm the worker gave consent before registering.');
    return;
  }
  if (!enrollState.imageBase64) {
    setEnrollError('Please capture or upload a face photo first.');
    return;
  }

  const submitBtn = document.getElementById('btn-enroll-submit');
  submitBtn.disabled = true;
  submitBtn.textContent = 'Enrolling face...';

  try {
    const enrolled = await apiFetch('/api/v1/admin/workers/enroll', {
      method: 'POST',
      body: {
        workerId: selectedId,
        workerCode: selectedId ? null : workerCode,
        fullName,
        teamName,
        consentGiven: true,
        imageBase64: enrollState.imageBase64
      }
    });

    enrollState.batchCount += 1;
    const batchBanner = document.getElementById('enroll-batch-banner');
    batchBanner.textContent =
      `Registered ${enrolled.fullName} (${enrolled.workerCode}) — ${enrollState.batchCount} worker(s) registered in this session. Ready for the next worker.`;
    batchBanner.hidden = false;

    const successBanner = document.getElementById('invite-created-banner');
    successBanner.textContent = `Worker ${enrolled.fullName} (${enrolled.workerCode}) registered with face template.`;
    successBanner.hidden = false;

    // Reset form for next worker while keeping live camera open if already running
    document.getElementById('enroll-existing-worker').value = '';
    const codeInput = document.getElementById('enroll-worker-code');
    codeInput.disabled = false;
    codeInput.value = '';
    document.getElementById('enroll-worker-name').value = '';
    document.getElementById('enroll-worker-team').value = '';
    document.getElementById('enroll-consent-checkbox').checked = false;
    enrollState.imageBase64 = null;
    updateEnrollPreviewUi();

    await Promise.all([loadWorkersTable(), refreshEnrollExistingSelect()]);
    codeInput.focus();
  } catch (err) {
    setEnrollError(err.message || 'Could not enroll worker photo.');
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Register & Next Worker';
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
    banner.textContent = `Worker ${res.worker.workerCode} invited. One-time invitation code: ${res.invitationCode}`;
    banner.hidden = false;
    e.target.reset();
    await loadWorkersTable();
  } catch (err) {
    setGlobalError(err.message || 'Could not create worker invitation.');
  }
});

init();
