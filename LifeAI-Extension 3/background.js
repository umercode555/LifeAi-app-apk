// Clicking the icon opens (or focuses) the Engine dashboard — a full pinned tab, not a popup.
const DASH = chrome.runtime.getURL('dashboard.html');
async function openDash() {
  const [t] = await chrome.tabs.query({ url: DASH });
  if (t) { await chrome.tabs.update(t.id, { active: true }); await chrome.windows.update(t.windowId, { focused: true }); }
  else await chrome.tabs.create({ url: DASH, pinned: true });
}
chrome.action.onClicked.addListener(openDash);
chrome.runtime.onStartup.addListener(openDash);
chrome.runtime.onInstalled.addListener(() => { openDash(); chrome.alarms.create('tick', { periodInMinutes: 0.5 }); });

/* ───────── Laptop screen-time tracker ─────────
   Counts seconds on the ACTIVE tab of the FOCUSED window while the computer is NOT idle.
   Totals per day are uploaded as absolute values to Supabase table `usage` (device = laptop), so uploads are idempotent.
   App keys match the phone app (youtube, instagram, reddit …) so both devices add up. */
const KNOWN = { 'youtube.com': 'youtube', 'instagram.com': 'instagram', 'reddit.com': 'reddit', 'facebook.com': 'facebook', 'x.com': 'x', 'twitter.com': 'x',
  'tiktok.com': 'tiktok', 'whatsapp.com': 'whatsapp', 'netflix.com': 'netflix', 'linkedin.com': 'linkedin', 'snapchat.com': 'snapchat', 'telegram.org': 'telegram',
  'discord.com': 'discord', 'spotify.com': 'spotify', 'github.com': 'github', 'chatgpt.com': 'chatgpt', 'openai.com': 'chatgpt', 'pinterest.com': 'pinterest', 'claude.ai': 'claude', 'gemini.google.com': 'gemini', 'mail.google.com': 'gmail', 'amazon.com': 'amazon' };
const SLD = new Set(['co', 'com', 'org', 'net', 'gov', 'ac', 'edu']);
function keyFor(url) {
  let h; try { const u = new URL(url); if (!/^https?:$/.test(u.protocol)) return null; h = u.hostname.replace(/^www\./, ''); } catch { return null; }
  if (h === 'localhost' || /^\d+\.\d+\.\d+\.\d+$/.test(h)) return null;
  const p = h.split('.'); const n = p.length >= 3 && SLD.has(p[p.length - 2]) && p[p.length - 1].length === 2 ? 3 : 2;
  const base = p.slice(-n).join('.'); const hit = KNOWN[h] || KNOWN[base]; const k = hit || base;
  return { key: k, label: hit ? k[0].toUpperCase() + k.slice(1) : base };
}
/* v3 ledger: every website segment (domain + page title + start/end) → table app_log, so Gemini knows exactly WHAT he was on and for HOW LONG. */
async function logSeg(c, end) {
  try {
    if (!c || end - c.segStart < 3000) return;
    const { cfg } = await chrome.storage.local.get('cfg'); if (!cfg || !cfg.sbUrl || !cfg.sbKey) return;
    let { deviceId } = await chrome.storage.local.get('deviceId'); if (!deviceId) { deviceId = Math.random().toString(36).slice(2, 10); await chrome.storage.local.set({ deviceId }); }
    await fetch(cfg.sbUrl.replace(/\/$/, '') + '/rest/v1/app_log?on_conflict=id', { method: 'POST',
      headers: { apikey: cfg.sbKey, Authorization: 'Bearer ' + cfg.sbKey, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify([{ id: `laptop-${deviceId}-${c.segStart}-${c.key}`, device: 'laptop', app_key: c.key, label: c.label, title: (c.title || '').slice(0, 120), start_ts: new Date(c.segStart).toISOString(), end_ts: new Date(end).toISOString(), seconds: Math.round((end - c.segStart) / 1000) }]) });
  } catch (e) { /* ledger is best-effort; screen-time totals are separate */ }
}
const today = () => new Date().toLocaleDateString('en-CA');
let chain = Promise.resolve(); const serial = (f) => (chain = chain.then(f).catch((e) => console.warn('lifeai tracker', e)));

async function load() { const { track } = await chrome.storage.local.get('track'); return track && track.day ? track : { day: today(), usage: {}, cur: null }; }
async function settle(now = Date.now()) {
  const t = await load();
  if (t.cur) { const dt = Math.min(300, Math.max(0, (now - t.cur.since) / 1000)); const u = t.usage[t.cur.key] || { label: t.cur.label, seconds: 0 }; u.seconds += dt; t.usage[t.cur.key] = u; t.cur.since = now; }
  if (t.day !== today()) { await flush(t); t.day = today(); t.usage = {}; if (t.cur) t.cur.since = now; }
  await chrome.storage.local.set({ track: t }); return t;
}
async function refreshCurrent() {
  const t = await load(); let cur = null;
  try {
    const idle = await chrome.idle.queryState(60); const w = await chrome.windows.getLastFocused({ populate: false });
    if (idle === 'active' && w && w.focused) { const [tab] = await chrome.tabs.query({ active: true, windowId: w.id }); const k = tab && tab.url ? keyFor(tab.url) : null; if (k) cur = { ...k, since: Date.now(), segStart: Date.now(), title: (tab.title || '') }; }
  } catch (e) { /* ignore */ }
  if (!(t.cur && cur && t.cur.key === cur.key)) { if (t.cur && t.cur.segStart) await logSeg(t.cur, Date.now()); t.cur = cur; }   // app/site changed → close the old segment
  else { t.cur.title = cur.title; if (!t.cur.segStart) t.cur.segStart = t.cur.since; }   // same site → keep the original start time
  await chrome.storage.local.set({ track: t });
}
async function flush(t) {
  const { cfg } = await chrome.storage.local.get('cfg'); if (!cfg || !cfg.sbUrl || !cfg.sbKey) return;
  let { deviceId } = await chrome.storage.local.get('deviceId'); if (!deviceId) { deviceId = Math.random().toString(36).slice(2, 10); await chrome.storage.local.set({ deviceId }); }
  const now = new Date().toISOString(); const base = cfg.sbUrl.replace(/\/$/, '') + '/rest/v1/';
  const H = { apikey: cfg.sbKey, Authorization: 'Bearer ' + cfg.sbKey, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates,return=minimal' };
  const rows = Object.entries(t.usage).filter(([, v]) => v.seconds >= 5).map(([k, v]) => ({ day: t.day, device: 'laptop', app_key: k, label: v.label, seconds: Math.round(v.seconds), updated_at: now }));
  if (rows.length) await fetch(base + 'usage?on_conflict=day,device,app_key', { method: 'POST', headers: H, body: JSON.stringify(rows) });
  await fetch(base + 'devices?on_conflict=id', { method: 'POST', headers: H, body: JSON.stringify([{ id: 'laptop-' + deviceId, device: 'laptop', label: 'Chrome' + (t.cur ? ' — now on ' + t.cur.label + ' (' + Math.round(((t.usage[t.cur.key] || {}).seconds || 0) / 60) + 'm today)' : ''), pair_code: cfg.pairCode || null, last_seen: now }]) });
}
const onChange = () => serial(async () => { await settle(); await refreshCurrent(); });
chrome.tabs.onActivated.addListener(onChange);
chrome.tabs.onUpdated.addListener((id, info) => { if (info.url || info.status === 'complete') onChange(); });
chrome.windows.onFocusChanged.addListener(onChange);
chrome.idle.setDetectionInterval(60); chrome.idle.onStateChanged.addListener(onChange);
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'tick') serial(async () => { const t = await settle(); await refreshCurrent(); await flush(t); const u = await load(); if (u.cur && u.cur.segStart) await logSeg(u.cur, Date.now()); }); });
chrome.runtime.onStartup.addListener(() => chrome.alarms.create('tick', { periodInMinutes: 0.5 }));
chrome.alarms.get('tick').then((a) => { if (!a) chrome.alarms.create('tick', { periodInMinutes: 0.5 }); });
onChange();
