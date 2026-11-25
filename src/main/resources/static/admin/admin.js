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
  } else if (tab === 'settings') {
    loadCalendarAndSettings();
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
      <div class="detail-kv"><span>Recorded By</span><strong>${escapeHtml(d.actorWorkerName)}${d.actorWorkerCode ? ' (' + escapeHtml(d.actorWorkerCode) + ')' : ''}</strong></div>
      ${d.notes ? `<div class="detail-kv"><span>Notes</span><span>${escapeHtml(d.notes)}</span></div>` : ''}
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
  } catch (err) {
    body.innerHTML = `<div class="banner banner-error">${escapeHtml(err.message || 'Failed to load details.')}</div>`;
  }
}

async function loadWorkersTable() {
  const tbody = document.getElementById('workers-tbody');
  tbody.innerHTML = '<tr><td colspan="5" class="helper-text">Loading workers...</td></tr>';
  try {
    const page = await apiFetch('/api/v1/admin/workers?size=100');
    tbody.innerHTML = '';
    page.content.forEach((w) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${escapeHtml(w.workerCode)}</strong></td>
        <td>${escapeHtml(w.fullName)}</td>
        <td>${escapeHtml(w.teamName || '-')}</td>
        <td>${escapeHtml(w.status)}</td>
        <td class="helper-text">Manage in PR 9</td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5" class="banner banner-error">${escapeHtml(err.message)}</td></tr>`;
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

document.getElementById('input-month').addEventListener('change', (e) => {
  if (e.target.value) {
    state.month = e.target.value;
    state.page = 0;
    loadDashboardAndGrid();
    if (state.activeTab === 'settings') {
      loadCalendarAndSettings();
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

document.getElementById('btn-export-shortcut').addEventListener('click', () => showTab('reports'));

document.getElementById('btn-toggle-invite-form').addEventListener('click', () => {
  const form = document.getElementById('form-create-worker');
  form.hidden = !form.hidden;
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
