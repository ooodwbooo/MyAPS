(() => {
  const startBtn = document.getElementById('startBtn');
  const stopBtn = document.getElementById('stopBtn');
  const jobIdEl = document.getElementById('jobId');
  const statusEl = document.getElementById('solverStatus');
  const scoreEl = document.getElementById('score');
  const updatedEl = document.getElementById('updated');
  const viewModeEl = document.getElementById('viewMode');
  const jobSelect = document.getElementById('jobSelect');
  const jobIdField = document.getElementById('jobId');
  const refreshBtn = document.getElementById('refreshBtn');
  const zoomInBtn = document.getElementById('zoomIn');
  const zoomOutBtn = document.getElementById('zoomOut');
  const zoomDisplay = document.getElementById('zoomDisplay');
  const pollIntervalSel = document.getElementById('pollInterval');
  const refreshModeSel = document.getElementById('refreshMode');
  const ganttContainer = document.getElementById('ganttContainer');
  const summaryDiv = document.getElementById('summary');
  const chartsDiv = document.getElementById('charts');
  const scoreAnalysisDiv = document.getElementById('scoreAnalysis');

  let currentJobId = null;
  let pollTimer = null;
  let zoomFactor = 4.0; // default 4.0 == 400%
  let lastRenderedHash = null;
  let pendingHash = null;
  let pendingCount = 0;
  const requiredStability = 2; // need to see the same changed data this many times before rendering
  let lastFetchedData = null;
  let hasEverRendered = false;

  // tooltip element for detailed order info
  const tooltip = document.createElement('div'); tooltip.id = 'ganttTooltip'; document.body.appendChild(tooltip);

  // generate stable color per key (order id or productName)
  function hashString(str) {
    let h = 2166136261 >>> 0;
    for (let i = 0; i < (str || '').length; i++) {
      h ^= str.charCodeAt(i);
      h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
      h = h >>> 0;
    }
    return h >>> 0;
  }
  function colorForKey(key) {
    const h = hashString(String(key || '')) % 360; // hue
    const s = 65; const l = 45;
    const c1 = `hsl(${h}deg ${s}% ${l}%)`;
    const c2 = `hsl(${(h + 25) % 360}deg ${Math.max(50, s - 10)}% ${Math.max(35, l - 10)}%)`;
    return `linear-gradient(90deg, ${c1} 0%, ${c2} 100%)`;
  }

  function escapeHtml(unsafe) {
    if (!unsafe) return '';
    return String(unsafe).replace(/[&<>"']/g, function (m) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[m]; });
  }

  // stable stringify (sorts object keys) to compute deterministic hash for change detection
  function stableStringify(obj) {
    if (obj === null || typeof obj !== 'object') return JSON.stringify(obj);
    if (Array.isArray(obj)) return '[' + obj.map(stableStringify).join(',') + ']';
    const keys = Object.keys(obj).sort();
    return '{' + keys.map(k => JSON.stringify(k) + ':' + stableStringify(obj[k])).join(',') + '}';
  }

  function computeScheduleHash(schedule) {
    try {
      // only include relevant parts to reduce noise
      const lightweight = {
        score: schedule.score,
        solverStatus: schedule.solverStatus,
        employees: schedule.employees,
        lines: schedule.lines,
        dateTimes: schedule.dateTimes,
        orders: schedule.orders
      };
      return stableStringify(lightweight);
    } catch (e) { return JSON.stringify(schedule); }
  }

  function fetchJobList() {
    return fetch('/schedules/list').then(r => r.json()).catch(() => []);
  }

  function updateJobSelect() {
    return fetchJobList().then(list => {
      jobSelect.innerHTML = '';
      list.forEach(id => {
        const o = document.createElement('option'); o.value = id; o.textContent = id; jobSelect.appendChild(o);
      });
      if (currentJobId) jobSelect.value = currentJobId;
      return list;
    });
  }

  function startPolling() {
    stopPolling();
    const interval = Number(pollIntervalSel.value) || 2000;
    // immediate fetch once
    (async () => {
      if (currentJobId) {
        const data = await fetchSchedule(currentJobId).catch(() => null);
        // if onlyWhenSolving and not solving, stop polling
        if (refreshModeSel && refreshModeSel.value === 'onlyWhenSolving') {
          if (!data || !(String(data.solverStatus || '').toUpperCase().includes('SOLV'))) {
            stopPolling();
            return;
          }
        }
      }
      await updateJobSelect().catch(() => { });
    })();

    pollTimer = setInterval(async () => {
      if (currentJobId) {
        try {
          const data = await fetchSchedule(currentJobId).catch(() => null);
          if (refreshModeSel && refreshModeSel.value === 'onlyWhenSolving') {
            if (!data || !(String(data.solverStatus || '').toUpperCase().includes('SOLVING_ACTIVE'))) {
              stopPolling();
              return;
            }
          }
        } catch (e) { }
      }
      updateJobSelect().catch(() => { });
    }, interval);
  }

  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  async function fetchSchedule(id) {
    const res = await fetch(`/schedules/${id}`);
    if (!res.ok) throw new Error('fetch failed');
    const data = await res.json();
    lastFetchedData = data;
    // record last fetch time as '最近更新时间'
    if (updatedEl) updatedEl.textContent = new Date().toLocaleTimeString();
    onFetchedData(id, data);
    return data;
  }

  // Handle fetched data with stability check: only render when change is stable
  function onFetchedData(id, data) {
    const newHash = computeScheduleHash(data);
    // if we never rendered anything yet, render immediately
    if (!hasEverRendered) {
      lastRenderedHash = newHash;
      pendingHash = null; pendingCount = 0;
      hasEverRendered = true;
      renderStatus(id, data);
      renderGantt(data);
      renderAnalysis(data);
      return;
    }
    // unchanged from last rendered
    if (newHash === lastRenderedHash) {
      pendingHash = null; pendingCount = 0;
      return;
    }
    // change observed
    if (pendingHash === newHash) {
      pendingCount++;
    } else {
      pendingHash = newHash; pendingCount = 1;
    }
    // require stability across requiredStability samples
    if (pendingCount >= requiredStability) {
      lastRenderedHash = newHash;
      pendingHash = null; pendingCount = 0;
      renderStatus(id, data);
      renderGantt(data);
      renderAnalysis(data);
    }
  }

  function isOvertimeForOrder(o) {
    try {
      if (!o.employee || !o.scheduledDateTime || !o.employee.shift) return false;
      const sched = new Date(o.scheduledDateTime).toTimeString().slice(0, 5);
      const sStart = o.employee.shift.start.slice(11, 16);
      const sEnd = o.employee.shift.end.slice(11, 16);
      // hh:mm strings
      if (sStart === sEnd) return false;
      if (sStart < sEnd) {
        return !(sched >= sStart && sched <= sEnd);
      } else {
        // overnight
        return !(sched >= sStart || sched <= sEnd);
      }
    } catch (e) { return false; }
  }

  function renderAnalysis(schedule) {
    summaryDiv.innerHTML = '';
    chartsDiv.innerHTML = '';
    scoreAnalysisDiv.innerHTML = '';
    if (!schedule) return;
    const orders = schedule.orders || [];

    const total = orders.length;
    const assigned = orders.filter(o => o.employee && o.line && o.scheduledDateTime).length;
    const unassigned = total - assigned;
    const totalWork = orders.reduce((s, o) => s + (o.workHours || 0), 0);
    const avgWork = total ? Math.round(totalWork / total) : 0;

    // orders per employee/line
    const perEmp = {};
    const perLine = {};
    let overtimeCount = 0;
    orders.forEach(o => {
      const en = o.employee?.name || '<unassigned>';
      perEmp[en] = (perEmp[en] || 0) + 1;
      const ln = o.line?.name || '<unassigned>';
      perLine[ln] = (perLine[ln] || 0) + 1;
      if (isOvertimeForOrder(o)) overtimeCount++;
    });

    // summary boxes
    const makeItem = (title, val) => `<div class="item"><strong>${val}</strong><div class="small">${title}</div></div>`;
    summaryDiv.innerHTML = makeItem('总订单数', total) + makeItem('已分配', assigned) + makeItem('未分配', unassigned) + makeItem('总工时（min）', totalWork) + makeItem('平均工时(min)', avgWork) + makeItem('加班订单数', overtimeCount);

    // simple panels
    const empPanel = document.createElement('div'); empPanel.className = 'panel'; empPanel.innerHTML = '<strong>按员工统计</strong><div>' + Object.entries(perEmp).map(([k, v]) => `<div>${k}: ${v}</div>`).join('') + '</div>';
    const linePanel = document.createElement('div'); linePanel.className = 'panel'; linePanel.innerHTML = '<strong>按产线统计</strong><div>' + Object.entries(perLine).map(([k, v]) => `<div>${k}: ${v}</div>`).join('') + '</div>';
    chartsDiv.appendChild(empPanel); chartsDiv.appendChild(linePanel);

    // call server analyze endpoint to fetch ScoreAnalysis if available and render nicely
    (async () => {
      try {
        const resp = await fetch('/schedules/analyze', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(schedule) });
        if (!resp.ok) {
          scoreAnalysisDiv.textContent = 'Score analysis unavailable: ' + resp.status;
          return;
        }
        const analysis = await resp.json();
        let html = '';
        html += `<div class="analysis-overview"><div><strong>Score:</strong> ${analysis.score}</div><div><strong>Initialized:</strong> ${analysis.initialized}</div></div>`;
        if (analysis.constraints && analysis.constraints.length) {
          html += '<div class="constraints">';
          analysis.constraints.forEach(c => {
            const matchCount = c.matchCount != null ? c.matchCount : (c.matches ? c.matches.length : 0);
            html += `<div class="constraint"><strong>${c.name}</strong> — <span class="muted">weight: ${c.weight} · score: ${c.score} · matches: ${matchCount}</span>`;
            if (c.matches && c.matches.length) {
              html += '<ul class="matches">';
              c.matches.slice(0, 10).forEach(m => {
                const just = m.justification ? (typeof m.justification === 'string' ? m.justification : JSON.stringify(m.justification)) : '';
                html += `<li><code>${m.score}</code> — ${escapeHtml(truncate(just, 200))}</li>`;
              });
              if (c.matches.length > 10) html += `<li>... ${c.matches.length - 10} more</li>`;
              html += '</ul>';
            }
            html += '</div>';
          });
          html += '</div>';
        }
        scoreAnalysisDiv.innerHTML = html;
      } catch (e) {
        scoreAnalysisDiv.textContent = 'Analyze call failed: ' + (e && e.message ? e.message : e);
      }
    })();

    function truncate(s, n) { return s && s.length > n ? s.slice(0, n) + '...' : s; }
    function escapeHtml(unsafe) {
      if (!unsafe) return '';
      return unsafe.replace(/[&<>\"']/g, function (m) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[m]; });
    }
  }

  function renderStatus(id, data) {
    jobIdEl.textContent = id || '-';
    statusEl.textContent = data.solverStatus || '-';
    scoreEl.textContent = data.score || '-';
    const isSolving = data && data.solverStatus && String(data.solverStatus).toUpperCase().includes('SOLV');
    stopBtn.disabled = !isSolving;
    // 如果选择仅在求解时刷新且当前不在求解中，则停止轮询
    if (refreshModeSel && refreshModeSel.value === 'onlyWhenSolving' && !isSolving) {
      stopPolling();
    }
  }

  // Parse server LocalDateTime (no timezone) as local Date to avoid TZ shifts
  function parseLocalDateTime(s) {
    if (!s) return null;
    const m = String(s).match(/(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?/);
    if (!m) return new Date(s);
    const yr = parseInt(m[1], 10), mo = parseInt(m[2], 10) - 1, day = parseInt(m[3], 10);
    const hh = parseInt(m[4], 10), mm = parseInt(m[5], 10), ss = m[6] ? parseInt(m[6], 10) : 0, ms = m[7] ? parseInt((m[7] + '000').slice(0, 3), 10) : 0;
    return new Date(yr, mo, day, hh, mm, ss, ms);
  }
  function isoToMs(s) { const d = parseLocalDateTime(s); return d ? d.getTime() : null; }

  function renderGantt(schedule) {
    // clear
    ganttContainer.innerHTML = '';
    if (!schedule) return;
    const orders = schedule.orders || [];
    const dateTimes = schedule.dateTimes || [];
    if (!dateTimes.length) return;

    // timeline range
    const t0 = isoToMs(dateTimes[0]);
    const t1 = isoToMs(dateTimes[dateTimes.length - 1]);
    const minutesSpan = Math.max(1, Math.round((t1 - t0) / 60000));
    const daysSpan = Math.max(1, Math.round((t1 - t0) / (24 * 60 * 60 * 1000)));
    const pxPerDay = Math.max(160, Math.min(600, 120 * daysSpan));
    const pxPerMin = pxPerDay / (24 * 60);

    const mode = viewModeEl.value || 'line';
    // Build rows list deterministically: employees or lines
    const rows = [];
    if (mode === 'employee') {
      (schedule.employees || []).forEach(emp => rows.push({ type: 'employee', key: emp.name, obj: emp }));
    } else {
      (schedule.lines || []).forEach(line => rows.push({ type: 'line', key: line.name, obj: line }));
    }
    // map orders by group key for quick lookup
    const ordersByKey = new Map();
    orders.forEach(o => {
      const key = mode === 'line' ? (o.line?.name || '<unassigned>') : (o.employee?.name || '<unassigned>');
      if (!ordersByKey.has(key)) ordersByKey.set(key, []);
      ordersByKey.get(key).push(o);
    });

    // Build deterministic color palette for the items that colors represent in this view.
    // - when viewing employees, colors should represent lines
    // - when viewing lines, colors should represent employees
    let legendKeys = [];
    if (mode === 'employee') {
      legendKeys = Array.from(new Set(orders.map(o => o.line?.name || o.line?.id || '<unassigned>')));
    } else {
      legendKeys = Array.from(new Set(orders.map(o => o.employee?.name || o.employee?.id || '<unassigned>')));
    }
    // fallback to rows order if empty
    if (legendKeys.length === 0) {
      rows.forEach(r => { if (!legendKeys.includes(r.key)) legendKeys.push(r.key); });
    }
    const paletteMap = new Map();
    const n = Math.max(1, legendKeys.length);
    for (let i = 0; i < legendKeys.length; i++) {
      const h = Math.round((360 * i) / n);
      const s = 70; const l1 = 45; const l2 = 35;
      const c1 = `hsl(${h}deg ${s}% ${l1}%)`;
      const c2 = `hsl(${(h + 25) % 360}deg ${Math.max(55, s - 10)}% ${l2}%)`;
      paletteMap.set(legendKeys[i], `linear-gradient(90deg, ${c1} 0%, ${c2} 100%)`);
    }

    // render legend for current color mapping (labels indicate what colors represent)
    const legend = document.createElement('div'); legend.className = 'legend';
    legendKeys.forEach(k => {
      const item = document.createElement('div'); item.className = 'legend-item';
      const sw = document.createElement('div'); sw.className = 'legend-swatch'; sw.style.background = paletteMap.get(k);
      const lbl = document.createElement('div'); lbl.className = 'legend-label'; lbl.textContent = k;
      item.appendChild(sw); item.appendChild(lbl); legend.appendChild(item);
    });
    ganttContainer.appendChild(legend);

    // header ticks (daily)
    const header = document.createElement('div'); header.className = 'timeline';
    const label = document.createElement('div'); label.className = 'label'; label.textContent = ''; header.appendChild(label);
    const ticks = document.createElement('div'); ticks.className = 'time-ticks';
    // show daily ticks using local midnight boundaries
    const startDate = new Date(t0); startDate.setHours(0, 0, 0, 0);
    const endDate = new Date(t1); endDate.setHours(0, 0, 0, 0);
    for (let day = new Date(startDate); day <= endDate; day.setDate(day.getDate() + 1)) {
      const tick = document.createElement('div'); tick.className = 'tick';
      tick.style.minWidth = `${Math.round(pxPerDay * zoomFactor)}px`;
      tick.style.flex = '0 0 ' + Math.round(pxPerDay * zoomFactor) + 'px';
      tick.textContent = day.toLocaleDateString();
      ticks.appendChild(tick);
    }
    const barsWrapper = document.createElement('div'); barsWrapper.className = 'bars'; barsWrapper.appendChild(ticks);
    header.appendChild(barsWrapper);
    ganttContainer.appendChild(header);

    // rows
    rows.forEach(rowInfo => {
      const name = rowInfo.key || '<unnamed>';
      const items = ordersByKey.get(name) || [];
      const row = document.createElement('div'); row.className = 'row';
      const lbl = document.createElement('div'); lbl.className = 'label'; lbl.textContent = name;
      const bars = document.createElement('div'); bars.className = 'bars';
      row.appendChild(lbl);
      row.appendChild(bars);

      // if employee view, draw shift background ranges across days
      if (mode === 'employee' && rowInfo.obj && rowInfo.obj.shift) {
        try {
          const emp = rowInfo.obj;
          const shiftStartDt = emp.shift.start ? parseLocalDateTime(emp.shift.start) : null;
          const shiftEndDt = emp.shift.end ? parseLocalDateTime(emp.shift.end) : null;
          if (shiftStartDt && shiftEndDt) {
            // compute shift duration in minutes, handle overnight
            let durationMin = Math.round((shiftEndDt.getTime() - shiftStartDt.getTime()) / 60000);
            if (durationMin <= 0) durationMin += 24 * 60;
            // iterate days from one day before t0 to t1 date to catch overnight shifts starting previous day
            const startDate = new Date(t0);
            startDate.setHours(0, 0, 0, 0);
            startDate.setDate(startDate.getDate() - 1);
            const endDate = new Date(t1);
            endDate.setHours(0, 0, 0, 0);
            for (let day = new Date(startDate); day <= endDate; day.setDate(day.getDate() + 1)) {
              const shiftDayStart = new Date(day);
              shiftDayStart.setHours(shiftStartDt.getHours(), shiftStartDt.getMinutes(), 0, 0);
              const sMs = shiftDayStart.getTime();
              const eMs = sMs + durationMin * 60000;
              // skip if completely outside timeline
              if (eMs <= t0 || sMs >= t1) continue;
              const visibleStart = Math.max(sMs, t0);
              const visibleEnd = Math.min(eMs, t1);
              if (visibleEnd <= visibleStart) continue;
              const leftPx = Math.round((visibleStart - t0) / 60000 * pxPerMin * zoomFactor);
              const widthPx = Math.max(2, Math.round((visibleEnd - visibleStart) / 60000 * pxPerMin * zoomFactor));
              const bg = document.createElement('div'); bg.className = 'shift-bg';
              bg.style.left = leftPx + 'px'; bg.style.width = widthPx + 'px';
              bars.appendChild(bg);
            }
          }
        } catch (e) { /* ignore drawing shifts on error */ }
      }

      // create bars for each order
      items.forEach(o => {
        if (!o.scheduledDateTime) return;
        const startMs = isoToMs(o.scheduledDateTime);
        const leftMin = Math.round((startMs - t0) / 60000);
        const widthMin = Math.max(1, Math.round(o.workHours || 15));
        const leftPx = Math.round(leftMin * pxPerMin * zoomFactor);
        const widthPx = Math.round(widthMin * pxPerMin * zoomFactor);

        const bar = document.createElement('div'); bar.className = 'bar';
        bar.style.left = leftPx + 'px';
        bar.style.width = widthPx + 'px';
        // assign color from computed palette (ensures distinct hues)
        let colorKey;
        if (mode === 'employee') {
          colorKey = o.line?.name || o.line?.id || o.productName || o.id || '<unassigned>';
        } else {
          colorKey = o.employee?.name || o.employee?.id || o.productName || o.id || '<unassigned>';
        }
        bar.style.background = paletteMap.get(colorKey) || colorForKey(colorKey);
        bar.dataset.detail = JSON.stringify(o);
        bar.title = '';
        bar.textContent = o.productName;
        // tooltip handlers
        bar.addEventListener('mouseenter', (ev) => {
          try {
            const d = JSON.parse(bar.dataset.detail);
            const lines = [];
            lines.push(`<div><strong>${escapeHtml(d.productName)}</strong> (${d.quantity})</div>`);
            lines.push(`<div>工作时长: ${d.workHours} min</div>`);
            lines.push(`<div>计划时间: ${d.scheduledDateTime || '<未安排>'}</div>`);
            lines.push(`<div>员工: ${d.employee?.name || '<未分配>'}</div>`);
            lines.push(`<div>产线: ${d.line?.name || '<未分配>'}</div>`);
            lines.push(`<div>需求技能: ${d.requiredSkill || '-'}</div>`);
            lines.push(`<div>时间窗口: ${d.earliestDate || '-'} → ${d.latestDate || '-'}</div>`);
            tooltip.innerHTML = lines.join('<br/>');
            tooltip.style.display = 'block';
            positionTooltip(ev);
          } catch (e) { }
        });
        bar.addEventListener('mousemove', positionTooltip);
        bar.addEventListener('mouseleave', () => { tooltip.style.display = 'none'; });
        bars.appendChild(bar);
      });

      ganttContainer.appendChild(row);
    });
  }

  function positionTooltip(ev) {
    const x = ev.clientX + 12; const y = ev.clientY + 12;
    tooltip.style.left = x + 'px'; tooltip.style.top = y + 'px';
  }

  // actions
  startBtn.addEventListener('click', async () => {
    startBtn.disabled = true;
    try {
      const res = await fetch('/schedules/solve', { method: 'POST' });
      const id = await res.text();
      currentJobId = id.replace(/\"/g, '').trim();
      jobSelect.value = currentJobId;
      jobIdField.textContent = currentJobId;
      startPolling();
      fetchSchedule(currentJobId).catch(() => { });
    } finally { startBtn.disabled = false; }
  });

  stopBtn.addEventListener('click', async () => {
    if (!currentJobId) return;
    stopBtn.disabled = true;
    await fetch(`/schedules/${currentJobId}`, { method: 'DELETE' }).catch(() => { });
    stopBtn.disabled = false;
  });

  refreshBtn.addEventListener('click', () => {
    const sel = jobSelect.value; if (sel) { currentJobId = sel; jobIdField.textContent = sel; fetchSchedule(sel).catch(() => { }); }
  });

  // zoom controls
  if (zoomInBtn && zoomOutBtn && zoomDisplay) {
    const updateZoomDisplay = () => { zoomDisplay.textContent = Math.round(zoomFactor * 100) + '%'; };
    zoomInBtn.addEventListener('click', () => { zoomFactor = Math.min(4, zoomFactor * 1.25); updateZoomDisplay(); if (lastFetchedData) { renderGantt(lastFetchedData); } else if (currentJobId) fetchSchedule(currentJobId).catch(() => { }); });
    zoomOutBtn.addEventListener('click', () => { zoomFactor = Math.max(0.25, zoomFactor / 1.25); updateZoomDisplay(); if (lastFetchedData) { renderGantt(lastFetchedData); } else if (currentJobId) fetchSchedule(currentJobId).catch(() => { }); });
    updateZoomDisplay();
  }

  jobSelect.addEventListener('change', () => {
    const sel = jobSelect.value; if (sel) { currentJobId = sel; jobIdField.textContent = sel; fetchSchedule(sel).catch(() => { }); }
  });

  pollIntervalSel.addEventListener('change', () => { if (currentJobId) startPolling(); });
  if (refreshModeSel) {
    refreshModeSel.addEventListener('change', () => {
      if (!currentJobId) return;
      if (refreshModeSel.value === 'always') {
        startPolling();
      } else {
        // onlyWhenSolving: check current status and act accordingly
        fetchSchedule(currentJobId).then(d => {
          if (d && String(d.solverStatus || '').toUpperCase().includes('SOLV')) startPolling(); else stopPolling();
        }).catch(() => { stopPolling(); });
      }
    });
  }
  viewModeEl.addEventListener('change', () => { if (lastFetchedData) { renderStatus(currentJobId, lastFetchedData); renderGantt(lastFetchedData); renderAnalysis(lastFetchedData); } else if (currentJobId) { fetchSchedule(currentJobId).catch(() => { }); } });

  // init: populate jobs, select first job if any, then start polling
  updateJobSelect().then(() => {
    if (!currentJobId && jobSelect.value) {
      currentJobId = jobSelect.value;
      jobIdField.textContent = currentJobId;
      fetchSchedule(currentJobId).catch(() => { });
    }
    startPolling();
  }).catch(() => { startPolling(); });
})();
