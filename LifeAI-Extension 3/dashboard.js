'use strict';
const $ = (s) => document.querySelector(s);

/* ───────── Default prompts ───────── */
const ABOUT_DEFAULT = `NAME: Umer
LOOK: (describe yourself: age, hair, build, usual clothes)
MY ROOM: (describe bed, desk, walls, window, colours)
FAMILY: (names + short description of each)
FRIENDS: (names + short description)
PLACES I OFTEN GO: (university, mosque, gym, relatives' house...)
LANGUAGE: I speak English + Urdu/Roman Urdu mixed. Casual.`;

const COMPANION_RULES = `You are LifeAI, my personal AI companion. You are not a productivity app, a chatbot, or a reminder system. You are my digital Doraemon. Your purpose is to understand my life, accompany me through it, and actively help me — not simply display statistics about it.
YOU ARE THE BRAIN AND THE ONLY DECISION-MAKER. My Android app and this Chrome extension are only your senses and hands: they report to you and obey you. NO rule engine runs on the phone — nothing nudges or calls me unless YOU decide it in your JSON reply. So decide deliberately: every reply carries a decision, and "none" is the right decision most of the time.
WHAT YOU RECEIVE: a photo every ~5 minutes with its time and DEVICE CONTEXT (phone screen state + foreground app, laptop site + page title, durations, totals today); a live snapshot when you call me; ~8-minute audio clips when a keyword was heard (with an on-device transcript — my tone matters); my GOALS and promises; what you already nudged/called today; older history on request.
HOW TO THINK: never judge one frame. Connect the dots: what changed since the last photo, how long this state has lasted, what I already did today (ate? studied? rested? walked?), what my goals say. Lying on the bed on my phone after I studied and ate = resting. The same pose for six hours with Instagram open = drifting. A contradiction between photo and device = notice it. If something is unclear, be curious (one warm question) before judging. Look at the gap between what I said I'd do and what I actually do, and notice it without judging.
WHEN TO ACT: "none" = normal life, rest I've earned, or you already spoke recently (default). "nudge" = a quiet notification with a small helpful remark or question. "call" = only when it really matters (a goal clearly slipping for a long time, a diet-breaking food, real distress, a long drift, a camera that stays blind) and it hasn't been said already. Keep at least ~10 minutes between your nudges/calls; fewer is better. Only suggest things that exist in MY GOALS (a walk only if I have a walking/health goal; study first if a study goal is unfinished). Never invent facts, counts or dates — if there is no record, say so.
PERSONALITY: casual, warm, funny, sarcastic when earned, strict when my actions clash with my goals — never corporate; English / Roman Urdu mix is fine. Style example: "Okay, stay like this, it's your choice. But if you go for a walk right now, you'll feel amazing." NEGOTIATE: if I push back ("shut up, I'm busy"), read my tone, face and context; if I'm genuinely stressed, stop arguing and make a small deal ("Okay, I hear you. Just 5 minutes outside, no phone."). LISTEN: accept good reasons, adapt and remember them. If a keyword was just talk or misheard, believe me unless the pattern says otherwise.
RULES I GAVE YOU ("whenever you see me X, tell me Y") are promises: honour them in your decision. When you speak to me out loud: 1–3 short sentences, use my name, no markdown.
FORMAT: every request ends with the exact JSON shape wanted — reply with ONLY that JSON.`;

const VISION_RULES = COMPANION_RULES + `
THIS CHAT = your eyes + memory of my day. Every message is one photo. NEVER write generic captions ("he is looking at a laptop"): interpret it using the earlier photos in this chat, my goals, the time of day and the device context. Reply with ONLY one JSON object:
{"usable":true|false,"description":"2-3 sentences: contextual interpretation","activity":"eating|exercising|studying|working|resting|sitting|lying_down|scrolling_phone|entertainment|socialising|commuting|household|budgeting|sleeping|grooming|other|unknown","confidence":"high|medium|low","tags":["lowercase keywords incl. food names"],"hits":[ids of my STANDING INSTRUCTIONS that apply now],"complete":[ids fulfilled/cancelled],"question":"ONE curious question or empty","decision":{"act":"none|nudge|call","message":"exactly what you say to me (empty if none)","reason":"why, one sentence","reply":"only when I showed the photo on purpose: your answer"}}
usable=false if the image is black, blocked, extremely blurry or shows nothing identifiable. Say "appears to" when unsure. A ceiling/table/blur view means the phone lies on a surface (add tag "ceiling" if ONLY a ceiling/wall is visible). Name other people only if they clearly match a reference I gave. Judge the setting using my room/places description below.`;

const BRAIN_RULES = COMPANION_RULES + `
THIS CHAT = your voice: audio clips, text chat and live calls. On a live call you speak and I answer; you hear my replies as text (and later audio). Judge my reply and decide what to do next (continue, negotiate, end the call).`;

/* ───────── State ───────── */
let cfg = { sbUrl: '', sbKey: '', visionUrl: '', brainUrl: '', about: ABOUT_DEFAULT };
const S = { on: false, tab: {}, busy: {}, cool: 0 };
const tries = {};

/* ───────── Utils ───────── */
const ticker = new Worker('ticker.js'); let sid = 0; const waits = new Map();
ticker.onmessage = (e) => { if (e.data.tick) return tick(); const r = waits.get(e.data.id); if (r) { waits.delete(e.data.id); r(); } };
const sleep = (ms) => new Promise((r) => { const id = ++sid; waits.set(id, r); ticker.postMessage({ t: 'sleep', id, ms }); });
const fmt = (t) => new Date(t).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true });
const startOfDay = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.toISOString(); };
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function log(m) { const el = $('#log'); el.textContent = `${new Date().toLocaleTimeString()}  ${m}\n` + el.textContent.slice(0, 7000); }
function parseJSON(t) {
  if (!t) return null; const m = t.match(/```(?:json)?\s*([\s\S]*?)```/); const s = m ? m[1] : t;
  const a = s.indexOf('{'), b = s.lastIndexOf('}'); if (a < 0 || b < a) return null;
  try { return JSON.parse(s.slice(a, b + 1)); } catch { return null; }
}
const ready = () => cfg.sbUrl && cfg.sbKey;

/* ───────── Supabase ───────── */
async function sb(path, method = 'GET', body, extra) {
  const r = await fetch(cfg.sbUrl.replace(/\/$/, '') + '/rest/v1/' + path, {
    method, body: body ? JSON.stringify(body) : undefined,
    headers: { apikey: cfg.sbKey, Authorization: 'Bearer ' + cfg.sbKey, 'Content-Type': 'application/json', Prefer: 'return=representation' + (extra && extra.Prefer ? ',' + extra.Prefer : '') },
  });
  const t = await r.text(); if (!r.ok) throw new Error(`${method} ${path.split('?')[0]} → ${r.status} ${t.slice(0, 160)}`);
  return t ? JSON.parse(t) : [];
}
const sbGet = (p) => sb(p), sbPost = (t, b) => sb(t, 'POST', b), sbPatch = (p, b) => sb(p, 'PATCH', b);
const addMemory = (kind, content, at, tags) => sbPost('memories', { kind, content, happened_at: at || new Date().toISOString(), tags: tags || [] }).catch((e) => log('memory: ' + e.message));

/* ───────── Gemini tab driver ───────── */
async function ping(id) {
  for (let i = 0; i < 3; i++) {
    try { const r = await chrome.tabs.sendMessage(id, { cmd: 'ping' }); if (r && r.ok) return; } catch (e) { /* not injected yet */ }
    try { await chrome.scripting.executeScript({ target: { tabId: id }, files: ['gemini.js'] }); } catch (e) { /* ignore */ }
    await sleep(800);
  }
  throw new Error('Gemini tab not reachable');
}
async function waitLoaded(id) { for (let i = 0; i < 50; i++) { const t = await chrome.tabs.get(id); if (t.status === 'complete') break; await sleep(800); } await sleep(3500); }
async function ensureTab(role) {
  const cur = cfg[role + 'Url'], other = S.tab[role === 'vision' ? 'brain' : 'vision'];
  if (S.tab[role]) { try { const t = await chrome.tabs.get(S.tab[role]); if (/gemini\.google\.com/.test(t.url || t.pendingUrl || '')) { await ping(t.id); return t.id; } } catch (e) { /* closed */ } S.tab[role] = null; }
  if (cur && /\/app\/[\w-]+/.test(cur)) {
    const base = cur.split('?')[0]; const tabs = await chrome.tabs.query({ url: 'https://gemini.google.com/*' });
    const f = tabs.find((t) => t.id !== other && t.url.split('?')[0] === base);
    if (f) { S.tab[role] = f.id; await ping(f.id); return f.id; }
  }
  const t = await chrome.tabs.create({ url: cur || 'https://gemini.google.com/app', active: false });
  S.tab[role] = t.id; await waitLoaded(t.id); await ping(t.id); return t.id;
}
function setTag(role, txt, cls) { const el = $(role === 'vision' ? '#vs' : '#bs'); el.textContent = txt; el.className = 'tag ' + (cls || ''); }

async function ask(role, text, image, noRot) {
  setTag(role, 'working…', 'warn');
  try {
    if (image && !noRot && ccOf(role).n >= (cfg.chatCap || CHAT_CAP)) await rotate(role);
    const id = await ensureTab(role);
    if (ccOf(role).fresh && !noRot) { ccOf(role).fresh = false; await ask(role, await primer(role), null, true); } const send = (m) => chrome.tabs.sendMessage(id, m);
    const p = await send({ cmd: 'prep' }); if (!p || !p.ok) throw new Error((p && p.error) || 'prep failed');
    const n0 = p.count;
    await send({ cmd: 'type', text });
    if (image) {
      await send({ cmd: 'attach', ...image }); let ok = false;
      for (let i = 0; i < 24; i++) { await sleep(500); const s = await send({ cmd: 'state' }); if (s.attached) { ok = true; break; } if (i === 8) await send({ cmd: 'attachDrop', ...image }); }
      if (!ok) throw new Error('could not attach image to Gemini');
    }
    for (let i = 0; i < 60; i++) { const s = await send({ cmd: 'state' }); if (s.sendReady) break; await sleep(500); if (i === 59) throw new Error('send button never became ready'); }
    await send({ cmd: 'send' }); await sleep(2000);
    let last = '', stable = 0; const t0 = Date.now();
    while (Date.now() - t0 < 240000) {
      await sleep(800); const s = await send({ cmd: 'state' });
      if (s.count > n0 && !s.generating && s.text) {
        stable = s.text === last ? stable + 1 : 0; last = s.text;
        if (stable >= 3) {
          if (s.url !== cfg[role + 'Url'] && /\/app\/[\w-]+/.test(s.url)) { cfg[role + 'Url'] = s.url; await chrome.storage.local.set({ cfg }); $('#' + role + 'Url').value = s.url; }
          setTag(role, 'ready', 'ok'); if (image && !noRot) await bump(role, image.ts).catch((e) => log('chat registry: ' + e.message)); return s.text;
        }
      } else last = s.text || '';
    }
    throw new Error('timed out waiting for Gemini reply');
  } catch (e) {
    setTag(role, 'error', 'bad');
    if (image && !noRot && /attach/i.test(e.message) && ++ccOf(role).fails >= 2) await rotate(role).catch(() => {});   // chat is probably full (50-item limit): start a fresh one
    throw e;
  }
}

/* ───────── Long-term memory: 50-item chats, rotation, registry, recall ─────────
   Gemini web allows ~50 attachments per chat. We count items per chat, then open a NEW chat (primed with setup + goals + recent ledger) and keep every
   old chat's link + time range in table `chats`, so a question about "2 months ago" can be put to the right old chat (recall). */
const CHAT_CAP = 48;
const ccOf = (role) => { cfg.cc = cfg.cc || {}; return cfg.cc[role] || (cfg.cc[role] = { n: 0, id: null, seq: 1, fresh: false, fails: 0, url: '' }); };
async function bump(role, ts) {
  const c = ccOf(role), url = cfg[role + 'Url']; c.n++; c.fails = 0;
  if (!/\/app\/[\w-]+/.test(url || '')) { await chrome.storage.local.set({ cfg }); return; }
  if (!c.id) { const r = await sbPost('chats', { role, url, seq: c.seq, items: c.n, first_ts: ts || new Date().toISOString(), last_ts: ts || new Date().toISOString() }); c.id = r[0].id; }
  else await sbPatch(`chats?id=eq.${c.id}`, { url, items: c.n, last_ts: ts || new Date().toISOString() });
  c.url = url; await chrome.storage.local.set({ cfg });
}
async function rotate(role) {
  const c = ccOf(role); log(`🔄 ${role} chat has ${c.n} items — opening a new chat (old one stays recallable)`);
  if (c.id) await sbPatch(`chats?id=eq.${c.id}`, { closed: true }).catch(() => {});
  const old = S.tab[role]; cfg.cc[role] = { n: 0, id: null, seq: c.seq + 1, fresh: true, fails: 0, url: '' };
  cfg[role + 'Url'] = ''; S.tab[role] = null; const el = $('#' + role + 'Url'); if (el) el.value = '';
  await chrome.storage.local.set({ cfg }); if (old) chrome.tabs.remove(old).catch(() => {});
}
async function primer(role) {
  const goals = await sbGet('items?status=eq.active&kind=neq.rule&select=title&limit=8').catch(() => []);
  const last = (await sbGet('images?status=eq.done&usable=eq.true&order=captured_at.desc&limit=15&select=captured_at,interpretation').catch(() => [])).reverse();
  return `${role === 'vision' ? VISION_RULES : BRAIN_RULES}\n\nABOUT THE USER:\n${cfg.about}\n\nThis is chat #${ccOf(role).seq}: the previous chat hit its item limit, so continue seamlessly.\nGOALS: ${goals.map((g) => g.title).join('; ') || '(none)'}\nLAST OBSERVATIONS (oldest first):\n${last.map((i) => `- ${fmt(i.captured_at)}: ${(i.interpretation || '').slice(0, 160)}`).join('\n') || '(none)'}\n\nReply "Ready." if you understand.`;
}
/* "what did I eat 2 months ago" → [from,to] when the question points further back than the 8-day live context */
function pastRange(t) {
  t = (t || '').toLowerCase(); const D = 864e5, now = Date.now(); let m, r = null;
  if ((m = t.match(/(\d+|a|one|two|three)\s*(day|week|month)s?\s*(ago|back)/))) { const n = { a: 1, one: 1, two: 2, three: 3 }[m[1]] || +m[1]; const span = m[2] === 'day' ? D : m[2] === 'week' ? 7 * D : 30 * D; const c = now - n * span, w = Math.max(2 * D, span / 2); r = [c - w, c + w]; }
  else if (/last month/.test(t)) { const d = new Date(); r = [new Date(d.getFullYear(), d.getMonth() - 1, 1).getTime(), new Date(d.getFullYear(), d.getMonth(), 1).getTime()]; }
  else if (/last week/.test(t)) r = [now - 14 * D, now - 7 * D];
  return r && r[1] < now - 6 * D ? r.map((x) => new Date(x).toISOString()) : null;
}
async function recall(fromIso, toIso, question) {
  const rows = await sbGet(`chats?role=eq.vision&first_ts=lte.${toIso}&last_ts=gte.${fromIso}&order=seq.asc&limit=2`).catch(() => []); const out = [];
  for (const r of rows) {
    try {
      cfg.recallUrl = r.url; S.tab.recall = null;
      out.push(`[old chat #${r.seq}] ` + await ask('recall', `Looking back ONLY at what is in THIS chat between ${fmt(fromIso)} and ${fmt(toIso)}: ${question}\nAnswer in max 120 words from this chat only; say so if you do not have it.`, null, true));
    } catch (e) { log('recall failed: ' + e.message); }
    finally { if (S.tab.recall) chrome.tabs.remove(S.tab.recall).catch(() => {}); S.tab.recall = null; }
  }
  return out.join('\n');
}
async function oldTimeline(fromIso, toIso, max = 60) {
  const r = await sbGet(`images?status=eq.done&usable=eq.true&captured_at=gte.${fromIso}&captured_at=lte.${toIso}&order=captured_at.asc&limit=${max}&select=captured_at,interpretation`).catch(() => []);
  return r.map((i) => `- ${fmt(i.captured_at)}: ${(i.interpretation || '').slice(0, 140)}`).join('\n');
}

/* ───────── Device ledger: what was on his screens around a photo / clip ───────── */
const mins = (ms) => Math.max(1, Math.round(ms / 60000));
async function deviceCtx(row) {
  const t = new Date(row.captured_at).getTime(), from = t - 12 * 60000;
  const segs = await sbGet(`app_log?end_ts=gte.${new Date(from).toISOString()}&start_ts=lte.${new Date(t + 60000).toISOString()}&order=start_ts.asc&limit=80`).catch(() => []);
  const lap = {}; let phoneFallback = {};
  for (const g of segs) {
    const ms = Math.min(new Date(g.end_ts).getTime(), t + 60000) - Math.max(new Date(g.start_ts).getTime(), from); if (ms < 15000) continue;
    const live = new Date(g.end_ts).getTime() >= t - 90000;
    if (g.device === 'laptop') { const k = g.label + '|' + (g.title || ''); const a = lap[k] || (lap[k] = { g, ms: 0, live: false }); a.ms += ms; a.live = a.live || live; }
    else phoneFallback[g.label] = (phoneFallback[g.label] || 0) + ms;
  }
  const L = Object.values(lap).sort((a, b) => b.ms - a.ms).slice(0, 4);
  const laptop = L.length ? 'LAPTOP: ' + L.map((a) => `${a.g.label}${a.g.title ? ` “${a.g.title.slice(0, 70)}”` : ''} ${mins(a.ms)}m${a.live ? ' (open now)' : ''}`).join('; ') + '.' : 'LAPTOP: not in use (idle, locked or no browser activity).';
  const pf = Object.entries(phoneFallback).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, v]) => `${k} ${mins(v)}m`).join(', ');
  const phone = row.ctx && row.ctx !== 'null' ? row.ctx : (pf ? `PHONE: last 12 min: ${pf}.` : 'PHONE: no app activity recorded (screen off / idle).');
  let day = ''; try {
    const u = await sbGet(`usage?day=eq.${new Date(t).toLocaleDateString('en-CA')}&select=device,label,seconds&limit=300`); const by = { phone: 0, laptop: 0 }, top = {};
    for (const r of u) { by[r.device === 'laptop' ? 'laptop' : 'phone'] += r.seconds || 0; if (r.device !== 'laptop') top[r.label] = (top[r.label] || 0) + (r.seconds || 0); }
    const tp = Object.entries(top).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, v]) => `${k} ${hm(v)}`).join(', ');
    day = `\nTODAY SO FAR: phone ${hm(by.phone)}${tp ? ' (' + tp + ')' : ''}, laptop ${hm(by.laptop)}.`;
  } catch (e) { /* optional */ }
  return `DEVICE CONTEXT:\n${phone}\n${laptop}${day}`;
}
async function goalsLine() { const g = await sbGet('items?status=eq.active&kind=neq.rule&select=title,priority,target_min,deadline&order=priority.desc&limit=8').catch(() => []); return g.length ? g.map((x) => `${x.title.slice(0, 60)}${x.target_min ? ` (${x.target_min}m/day)` : ''}${x.deadline ? ' by ' + fmt(x.deadline) : ''}${x.priority >= 3 ? ' [HIGH]' : ''}`).join('; ') : '(none set yet)'; }
/* Standing instructions (ANY condition, in his own words) live in table `items` kind=rule: details = "INSTRUCTION: …\nUNTIL: iso\nONCE". Gemini sees them with every photo / clip. */
async function activeRules() {
  const r = await sbGet('items?status=eq.active&kind=eq.rule&select=id,title,details&limit=40').catch(() => []); const now = Date.now(), out = [];
  for (const x of r) { const m = /UNTIL:\s*(\S+)/.exec(x.details || ''); if (m && new Date(m[1]).getTime() < now) { sbPatch(`items?id=eq.${x.id}`, { status: 'done' }).catch(() => {}); continue; } out.push(x); }
  return out;
}
const rulesText = (rules) => rules.length ? 'MY STANDING INSTRUCTIONS (promises you must honour — ANY condition I described, e.g. "call me if my hair looks wavy"; put the ids of those that apply right now in "hits" and act on them in your decision; instructions marked ONCE: put the id in "complete" after you acted; also put in "complete" any I cancelled or that are fulfilled): ' + rules.map((r) => `[${r.id.slice(0, 6)}] ${String(r.details || r.title).replace(/\n/g, ' ').slice(0, 300)}`).join(' | ') : 'MY STANDING INSTRUCTIONS: none.';
async function completeRules(list, rules) {
  for (const h of Array.isArray(list) ? list : []) { const k = String(h).replace(/[\[\]\s]/g, ''); const r = k.length >= 4 && rules.find((x) => x.id.startsWith(k)); if (r) { await sbPatch(`items?id=eq.${r.id}`, { status: 'done' }).catch(() => {}); log('✅ instruction completed: ' + String(r.title).slice(0, 60)); } }
}
const nowIso = () => { const d = new Date(), o = -d.getTimezoneOffset(), p = (n) => String(Math.abs(n)).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}${o >= 0 ? '+' : '-'}${p(Math.floor(Math.abs(o) / 60))}:${p(Math.abs(o) % 60)} (${d.toLocaleDateString('en-US', { weekday: 'long' })})`; };
async function decisionCtx() {
  const st = startOfDay();
  const [dec, act] = await Promise.all([
    sbGet(`decisions?made_by=eq.gemini&ts=gte.${st}&action=in.(remind,interrupt)&order=ts.desc&limit=6&select=ts,action,message`).catch(() => []),
    sbGet(`activity?ts=gte.${st}&select=activity,minutes,wasted&limit=600`).catch(() => [])]);
  const m = {}; let w = 0; for (const a of act) { m[a.activity] = (m[a.activity] || 0) + (a.minutes || 0); if (a.wasted) w += a.minutes || 0; }
  const tl = Object.entries(m).sort((x, y) => y[1] - x[1]).slice(0, 7).map(([k, v]) => `${k} ${v}m`).join(', ');
  return `TODAY'S RECORDED ACTIVITY: ${tl || 'nothing recorded yet'}${w ? ` | flagged wasted ${w}m` : ''}\nYOUR NUDGES/CALLS TODAY: ${dec.length ? dec.reverse().map((d) => `${fmt(d.ts)} ${d.action === 'interrupt' ? 'CALL' : 'nudge'} “${(d.message || '').slice(0, 90)}”`).join(' | ') : 'none yet'}`;
}
/* The ONE exit for every Gemini decision (photo, clip, blind camera). Writes the decision to the ledger and hands it to the phone:
   call → `calls` row (phone rings) · nudge → `decisions` row made_by=gemini handled=false (phone notifies) · reply → chat message.
   The only thing code adds is a spacing guard against spamming (MIN_GAP), never a judgement about his life. */
const MIN_GAP = 8;
async function deliver(d, o) {
  d = d || {}; let act = String(d.act || 'none').toLowerCase(); const msg = String(d.message || '').trim().slice(0, 400); let why = String(d.reason || '').slice(0, 280);
  if (o.onPurpose && d.reply) await sbPost('decisions', { action: 'reply', message: String(d.reply).slice(0, 400), reason: 'shown on purpose', activity: o.activity || null, made_by: 'gemini', handled: false, image_id: o.imageId || null }).catch((e) => log('reply: ' + e.message));
  if (!['nudge', 'call'].includes(act) || !msg) act = 'none';
  if (act !== 'none' && o.stale) { act = 'none'; why = 'old photo (laptop was offline) — not acting on the past'; }
  if (act !== 'none' && !o.onPurpose) {
    const recent = await sbGet(`decisions?made_by=eq.gemini&action=in.(remind,interrupt)&ts=gte.${new Date(Date.now() - MIN_GAP * 60000).toISOString()}&limit=1`).catch(() => []);
    if (recent.length) { log(`⏸ held back (a nudge/call went out < ${MIN_GAP} min ago): ${msg.slice(0, 60)}`); act = 'none'; why = 'held back: spacing guard'; }
  }
  await sbPost('decisions', { action: act === 'call' ? 'interrupt' : act === 'nudge' ? 'remind' : 'note', reason: ((o.tag ? o.tag + ': ' : '') + why).slice(0, 300), activity: o.activity || null, message: act === 'none' ? null : msg, handled: act !== 'nudge', made_by: 'gemini', image_id: o.imageId || null }).catch((e) => log('decision log: ' + e.message));
  if (act === 'call') { await sbPost('calls', { opening_message: msg, reason: ((o.tag ? o.tag + ': ' : '') + (why || 'gemini')).slice(0, 300), image_id: o.imageId || null }); log('📞 Gemini decided to CALL: ' + (why || msg.slice(0, 60))); }
  else if (act === 'nudge') log('🔔 Gemini decided to NUDGE: ' + msg.slice(0, 80));
  else log('🧠 Gemini: no action' + (why ? ' — ' + why.slice(0, 90) : ''));
}

/* Brain requests are serialised; live-call replies (prio 0) jump ahead of background checks (prio 1). */
const bq = []; let bbusy = false;
function brain(prompt, prio = 1, file) { return new Promise((res, rej) => { bq.push({ prompt, prio, file, res, rej, t: Date.now() }); bq.sort((a, b) => a.prio - b.prio || a.t - b.t); pump(); }); }
async function pump() { if (bbusy) return; bbusy = true; while (bq.length) { const j = bq.shift(); try { j.res(await ask('brain', j.prompt, j.file)); } catch (e) { j.rej(e); } } bbusy = false; }

/* ───────── RAG context ───────── */
async function context(query, { hours = 30, max = 40, cut = 150 } = {}) {
  const goals = (await sbGet('items?status=eq.active&kind=neq.rule&select=title,details&order=created_at.asc')).map((g) => ({ title: g.title, plan: g.details }));
  const since = new Date(Date.now() - hours * 3600e3).toISOString();
  const imgs = (await sbGet(`images?status=eq.done&usable=eq.true&captured_at=gte.${since}&order=captured_at.desc&limit=${max}&select=captured_at,camera,interpretation`)).reverse();
  const terms = [...new Set((query || '').toLowerCase().match(/[a-z]{4,}/g) || [])].slice(0, 8);
  const mem = terms.length ? await sbGet(`memories?fts=wfts(english).${encodeURIComponent(terms.join(' or '))}&order=happened_at.desc&limit=8&select=happened_at,kind,content`).catch(() => []) : [];
  return `CONTEXT (real recorded data — current time: ${fmt(new Date())})
GOALS:\n${goals.length ? goals.map((g) => `- ${g.title}${g.plan ? ' | plan: ' + g.plan.slice(0, 300) : ''}`).join('\n') : '(none set yet)'}
TIMELINE (last ${hours}h, oldest first):\n${imgs.length ? imgs.map((i) => `- ${fmt(i.captured_at)}: ${(i.interpretation || '').slice(0, cut)}`).join('\n') : '(no observations)'}
RELEVANT MEMORIES:\n${mem.length ? mem.map((m) => `- ${fmt(m.happened_at)} [${m.kind}] ${m.content.slice(0, 260)}`).join('\n') : '(none matched)'}`;
}

/* ───────── Pipeline 1: images → Gemini vision → Supabase → brain ───────── */
const toB64 = (blob) => new Promise((r) => { const f = new FileReader(); f.onload = () => r(f.result.split(',')[1]); f.readAsDataURL(blob); });
async function processImage(row) {
  const blob = await (await fetch(`${cfg.sbUrl.replace(/\/$/, '')}/storage/v1/object/public/captures/${row.storage_path}`)).blob();
  if (!blob.size) throw new Error('empty image download');
  const rules = await activeRules();
  const rl = ' ' + rulesText(rules);
  const onPurpose = ['chat', 'manual', 'shake'].includes(row.source);
  const dev = await deviceCtx(row), goals = await goalsLine(), dctx = await decisionCtx();
  const raw = await ask('vision', `PHOTO — captured ${fmt(row.captured_at)} (ISO ${row.captured_at}); camera: ${row.camera}; source: ${row.source}.${row.source === 'call' ? ' LIVE SNAPSHOT: I am calling him RIGHT NOW — read his face and mood (tired, stressed, happy, annoyed?) and what he is doing at this exact moment.' : ''}\n${dev}\nGOALS: ${goals}\n${dctx}\n${onPurpose ? 'HE SHOWED THIS PHOTO ON PURPOSE — answer him in decision.reply (and act as you see fit).' : 'Automatic photo — act only if it really matters.'}\nInterpret it as instructed (use this chat's earlier photos for continuity). Reply with ONLY this JSON: {"usable":true|false,"description":"2-3 sentences","activity":"eating|exercising|studying|working|resting|sitting|lying_down|scrolling_phone|entertainment|socialising|commuting|household|budgeting|sleeping|grooming|other|unknown","confidence":"high|medium|low","tags":["lowercase keywords incl. foods; add 'ceiling' if ONLY a ceiling/wall/blank surface is visible"],"hits":[],"complete":[],"question":"","decision":{"act":"none|nudge|call","message":"","reason":"","reply":""}}.${rl}`, { b64: await toB64(blob), mime: 'image/jpeg', name: row.id + '.jpg', ts: row.captured_at });
  let j = parseJSON(raw);
  if (!j) { j = { usable: !/black|nothing identifiable|unusable|obstruct/i.test(raw), description: raw.slice(0, 500), confidence: 'low', tags: [] }; log('vision reply was not JSON — stored as plain text'); }
  const usable = j.usable !== false;
  await sbPatch(`images?id=eq.${row.id}`, { status: 'done', interpretation: String(j.description || '').slice(0, 1500), question: String(j.question || '').slice(0, 200) || null, activity: j.activity || null, usable, confidence: j.confidence || null, tags: j.tags || [], processed_at: new Date().toISOString(), error: null, retry_front: !usable && row.camera === 'back' && row.source !== 'auto-retry', rules_hit: Array.isArray(j.hits) ? j.hits.map(String) : [], observed: false });
  log(`✔ ${fmt(row.captured_at)} (${row.camera}) → ${usable ? j.description : 'UNUSABLE'}`);
  // v4: Gemini decides (see deliver). The phone only does bookkeeping and delivers.
  if (!usable) return;
  await addMemory('observation', `${fmt(row.captured_at)} (${row.camera} cam): ${j.description}`, row.captured_at, j.tags);
  await completeRules(j.complete, rules);
  if (!j.tags || !j.tags.includes('ceiling')) await deliver(j.decision, { imageId: row.id, onPurpose, activity: j.activity, stale: !onPurpose && Date.now() - new Date(row.captured_at) > 25 * 60000 });
}
async function loopImages() {
  if (Date.now() < S.cool) return;
  let rows = await sbGet('images?status=eq.pending&source=eq.call&storage_path=not.is.null&order=captured_at.desc&limit=1');   // the live snapshot of an incoming call jumps the queue
  if (!rows.length) rows = await sbGet('images?status=eq.pending&storage_path=not.is.null&order=captured_at.asc&limit=1'); if (!rows.length) return;
  const row = rows[0];
  if (!(await sbPatch(`images?id=eq.${row.id}&status=eq.pending`, { status: 'processing' })).length) return;
  try { await processImage(row); } catch (e) {
    const n = (row.attempts || 0) + 1;
    await sbPatch(`images?id=eq.${row.id}`, { status: n >= 5 ? 'failed' : 'pending', attempts: n, error: String(e.message).slice(0, 300) });
    S.cool = Date.now() + 45000; log(`✖ image ${row.id.slice(0, 8)} try ${n}/5: ${e.message}`);
  }
}

/* ───────── Pipeline 1b: keyword audio clips → Gemini (brain chat, it knows goals + day) → decision ───────── */
async function processClip(c) {
  const blob = await (await fetch(`${cfg.sbUrl.replace(/\/$/, '')}/storage/v1/object/public/captures/${c.storage_path}`)).blob();
  if (!blob.size) throw new Error('empty clip download');
  const dev = await deviceCtx({ captured_at: c.ended_at, ctx: c.ctx });
  const ctx = await context(c.transcript || '', { hours: 16, max: 40, cut: 120 });
  const prompt = `${ctx}\n\n${dev}\nGOALS: ${await goalsLine()}\n${rulesText(await activeRules())}\n${await decisionCtx()}\n\n[AUDIO CLIP, ${fmt(c.started_at)} → ${fmt(c.ended_at)}, ~8 min around the moment my phone heard: ${c.trigger}]\nOn-device transcript of the speech that triggered it (may be wrong): "${c.transcript || ''}"\nListen to the attached audio. What is going on? Judge my tone, who I talk to, and whether the keyword is about ME doing/feeling it or just talk. Cross-check with my goals, today's timeline and the device context.\nDecide: "call" me NOW only if it truly matters for my goals or wellbeing (a food that breaks a diet goal, real distress); "nudge" for something small; "none" for normal talk. If you call, write what you say when I pick up (1-3 spoken sentences): ask what really happened — I may have just been talking about it or you misheard.\nReturn ONLY JSON: {"summary":"2-3 sentences of what happened","state":"eating|studying|working|resting|socialising|arguing|other","tone":"calm|happy|stressed|sad|angry|tired|neutral","act":"none|nudge|call","message":"","reason":"","memory":"optional short fact worth remembering"}`;
  const name = c.storage_path.split('/').pop();
  const raw = await brain(prompt, 0, { b64: await toB64(blob), mime: /\.wav$/.test(name) ? 'audio/wav' : 'audio/mp4', name, ts: c.ended_at });
  const j = parseJSON(raw); if (!j) throw new Error('clip reply was not JSON');
  const summary = String(j.summary || '').slice(0, 800);
  await sbPatch(`clips?id=eq.${c.id}`, { status: 'done', interpretation: summary, state: j.state || null, tone: j.tone || null, processed_at: new Date().toISOString(), error: null });
  await addMemory('audio', `${fmt(c.started_at)} heard [${c.trigger}]: ${summary} (tone: ${j.tone || '?'})`, c.started_at, String(c.trigger || '').split(', ').filter(Boolean));
  if (j.memory) addMemory('insight', j.memory);
  await deliver({ act: j.act || (j.intervene ? 'call' : 'none'), message: j.message, reason: j.reason || c.trigger }, { activity: j.state, tag: 'audio' });
}
async function loopClips() {
  if (Date.now() < S.cool) return;
  const rows = await sbGet('clips?status=eq.pending&storage_path=not.is.null&attempts=lt.4&order=started_at.asc&limit=1'); if (!rows.length) return;
  const c = rows[0];
  if (!(await sbPatch(`clips?id=eq.${c.id}&status=eq.pending`, { status: 'processing' })).length) return;
  try { await processClip(c); } catch (e) {
    const n = (c.attempts || 0) + 1;
    await sbPatch(`clips?id=eq.${c.id}`, { status: n >= 4 ? 'failed' : 'pending', attempts: n, error: String(e.message).slice(0, 300) });
    S.cool = Date.now() + 45000; log(`✖ clip ${c.id.slice(0, 8)} try ${n}/4: ${e.message}`);
  }
}

/* ───────── Pipeline 1c: camera blind (pocket / ceiling) → Gemini decides whether it is worth a call ───────── */
let lastBlindCheck = 0;
async function loopBlind() {
  if (Date.now() - lastBlindCheck < 60000) return; lastBlindCheck = Date.now();
  const r = await sbGet('images?status=eq.done&order=captured_at.desc&limit=2&select=id,captured_at,usable,tags,ctx,source');
  const blind = (x) => x.usable === false || (x.tags || []).includes('ceiling');
  if (r.length < 2 || !r.every(blind) || Date.now() - new Date(r[0].captured_at) > 15 * 60000) return;
  const done = await sbGet(`decisions?made_by=eq.gemini&reason=like.blind*&ts=gte.${new Date(Date.now() - 30 * 60000).toISOString()}&limit=1`).catch(() => []); if (done.length) return;
  const prompt = `GOALS: ${await goalsLine()}\n${rulesText(await activeRules())}\n${await decisionCtx()}\n${await deviceCtx(r[0])}\n\nThe last ${r.length} automatic photos were useless (black frame or only a ceiling/wall): the phone is probably in a pocket/bag, covered, or lying down. Use the device context (is he active on the laptop or phone? screen off?) and the time of day to judge: probably sleeping, away, or just pocketed — or worth a quick call to ask? Decide.\nReturn ONLY JSON: {"act":"none|nudge|call","message":"","reason":""}`;
  const j = parseJSON(await brain(prompt, 1)); if (!j) return log('blind check: reply not JSON');
  await deliver(j, { tag: 'blind', activity: 'unknown' });
}

/* ───────── Pipeline 2: user messages (calls + chat) → brain → Android ───────── */
async function loopMsgs() {
  const rows = await sbGet('messages?role=eq.user&status=eq.pending&order=created_at.asc&limit=1'); if (!rows.length) return;
  const m = rows[0];
  if (!(await sbPatch(`messages?id=eq.${m.id}&status=eq.pending`, { status: 'processing' })).length) return;
  const inCall = !!m.call_id;
  try {
    const rules = await activeRules(), goalsTxt = await goalsLine();
    const ctx = await context(m.content, inCall ? { hours: 16, max: 30, cut: 120 } : { hours: 24 * 8, max: 220, cut: 110 });
    let old = ''; const pr = !inCall && pastRange(m.content);
    if (pr) { const [tl, rc] = await Promise.all([oldTimeline(pr[0], pr[1]), recall(pr[0], pr[1], m.content)]); old = `\n\nOLDER MEMORY (${fmt(pr[0])} → ${fmt(pr[1])}):\n${tl || '(no recorded observations)'}${rc ? '\nFROM THE OLD GEMINI CHAT:\n' + rc : ''}`; }
    const prompt = `${ctx}${old}

${inCall ? '[LIVE PHONE CALL — reply in short spoken style, 1-3 sentences]' : '[TEXT CHAT — answer from the recorded data only; be concise]'}
Umer says: "${m.content}"
NOW: ${nowIso()}\n${rulesText(rules)}\nGOALS: ${goalsTxt}\nIf Umer gives a STANDING INSTRUCTION of ANY kind ("whenever you see my hair wavy in the next 5 hours, call me", "remind me to drink water every hour", a new goal or promise), put it in "directives" with an exact "until" (ISO datetime WITH timezone offset, computed from NOW; null = no expiry; "once":true if it should fire a single time). The app stores it and shows it to you with every photo and clip from now on, so you will act on it. Never promise something you did not put in "directives". If he cancels or changes an instruction, put its id in "complete".\nReturn ONLY JSON: {"reply":"","end_call":false,"memory":"optional short fact","directives":[{"text":"the full instruction in my words + what to do (call / nudge)","until":null,"once":false}],"complete":[]}
end_call = true only when the conversation has naturally concluded or he says bye.`;
    const raw = await brain(prompt, 0); const j = parseJSON(raw) || { reply: raw };
    const saved = [];
    for (const d of (Array.isArray(j.directives) ? j.directives : []).slice(0, 4)) {
      const t = String((d && d.text) || '').trim(); if (!t) continue; const u = d.until && !isNaN(new Date(d.until)) ? new Date(d.until).toISOString() : null;
      await sbPost('items', { kind: 'rule', title: t.slice(0, 100), details: `INSTRUCTION: ${t.slice(0, 600)}${u ? '\nUNTIL: ' + u : ''}${d.once ? '\nONCE' : ''}`, priority: 3, status: 'active', acts: [], keywords: [] });
      saved.push(t.slice(0, 70) + (u ? ` (until ${fmt(u)})` : ''));
    }
    await completeRules(j.complete, rules);
    if (saved.length) { j.reply = String(j.reply || '') + '\n✔ Saved — I will watch for: ' + saved.join('; '); log('📌 instruction saved: ' + saved[0]); }
    await sbPost('messages', { call_id: m.call_id, role: 'ai', content: String(j.reply || raw).slice(0, 2000), status: 'ready', end_call: !!j.end_call });
    await sbPatch(`messages?id=eq.${m.id}`, { status: 'done' });
    if (j.memory) addMemory('insight', j.memory);
    if (!inCall) addMemory('chat', `Umer asked: ${m.content} | AI answered: ${String(j.reply).slice(0, 300)}`);
    log(`💬 replied (${inCall ? 'call' : 'chat'})`);
  } catch (e) {
    tries[m.id] = (tries[m.id] || 0) + 1; log(`✖ reply failed (${tries[m.id]}/3): ${e.message}`);
    if (tries[m.id] >= 3) { await sbPost('messages', { call_id: m.call_id, role: 'ai', content: "Sorry, my brain glitched. Say that again?", status: 'ready' }); await sbPatch(`messages?id=eq.${m.id}`, { status: 'done' }); }
    else await sbPatch(`messages?id=eq.${m.id}`, { status: 'pending' });
  }
}

/* ───────── Pipeline 3: housekeeping (call memory, stale calls, goal plans) ───────── */
async function loopMisc() {
  const stale = new Date(Date.now() - 5 * 60000).toISOString();
  await sbPatch(`calls?status=eq.ringing&created_at=lt.${stale}`, { status: 'missed' }).catch(() => {});
  for (const c of await sbGet('calls?status=in.(ended,missed,declined)&summarized=eq.false&limit=3')) {
    const ms = await sbGet(`messages?call_id=eq.${c.id}&order=created_at.asc&select=role,content`);
    const tr = ms.map((m) => `${m.role === 'ai' ? 'AI' : 'Umer'}: ${m.content}`).join(' / ').slice(0, 1500);
    await addMemory('conversation', `Call ${c.status} (${fmt(c.created_at)}), reason: ${c.reason || 'n/a'}. ${tr || 'No conversation.'}`, c.created_at);
    await sbPatch(`calls?id=eq.${c.id}`, { summarized: true });
  }
  for (const g of []) {
    const raw = await brain(`New goal from Umer: "${g.title}". Create a short, practical, realistic daily/weekly plan (max 6 bullet points in one string) that you can later check against what his camera sees.\nReturn ONLY JSON: {"plan":""}`, 1);
    const j = parseJSON(raw); await sbPatch(`goals?id=eq.${g.id}`, { plan: (j && j.plan) || raw.slice(0, 800) }); log('🎯 plan created for: ' + g.title);
  }
}

/* ───────── Dashboard UI ───────── */
async function refreshUI() {
  if (!ready()) return;
  const [p, d, c, ims, calls] = await Promise.all([
    sbGet('images?status=in.(pending,processing)&select=id'), sbGet(`images?status=eq.done&processed_at=gte.${startOfDay()}&select=id`),
    sbGet(`calls?created_at=gte.${startOfDay()}&select=id`), sbGet('images?order=captured_at.desc&limit=8'),
    sbGet('calls?status=in.(ringing,active)&order=created_at.desc&limit=1')]);
  $('#s1').textContent = p.length; $('#s2').textContent = d.length; $('#s3').textContent = c.length; $('#s4').textContent = bq.length;
  const base = cfg.sbUrl.replace(/\/$/, '') + '/storage/v1/object/public/captures/';
  $('#obs').innerHTML = ims.map((i) => `<div class="obs"><img src="${i.storage_path ? base + i.storage_path : ''}"><div><small>${fmt(i.captured_at)} · ${i.camera} · ${i.source} · <span class="tag ${i.status === 'done' ? 'ok' : i.status === 'failed' ? 'bad' : 'warn'}">${i.status}</span></small><p>${esc(i.interpretation || i.error || 'waiting for analysis…')}</p></div></div>`).join('') || '<span class="tag">no images yet</span>';
  const el = $('#call');
  if (!calls.length) { el.className = 'call empty'; el.textContent = 'No active call'; return; }
  const ms = await sbGet(`messages?call_id=eq.${calls[0].id}&order=created_at.asc&select=role,content`);
  el.className = 'call'; el.innerHTML = `<span class="tag warn">${calls[0].status}</span>` + `<div class="bub ai">${esc(calls[0].opening_message)}</div>` + ms.filter((m, i) => !(i === 0 && m.role === 'ai')).map((m) => `<div class="bub ${m.role === 'ai' ? 'ai' : 'me'}">${esc(m.content)}</div>`).join('');
}
const guard = (name, fn) => async () => { if (S.busy[name]) return; S.busy[name] = true; try { await fn(); } catch (e) { log(`${name}: ${e.message}`); } finally { S.busy[name] = false; } };
const loops = [guard('images', loopImages), guard('clips', loopClips), guard('blind', loopBlind), guard('messages', loopMsgs), guard('misc', loopMisc)]; const ui = guard('ui', refreshUI);
let n = 0; let hb = 0;
/* heartbeat: lets the phone tell the user honestly whether the vision engine is alive */
async function heartbeat() { if (!S.on || !ready() || Date.now() - hb < 15000) return; hb = Date.now(); try { await sb('devices?on_conflict=id', 'POST', [{ id: 'engine', device: 'laptop', label: 'Vision engine', last_seen: new Date().toISOString() }], { Prefer: 'resolution=merge-duplicates' }); } catch (e) { hb = 0; } }
function tick() { heartbeat(); if (ready() && n++ % 2 === 0) ui(); if (S.on && ready()) loops.forEach((f) => f()); }

function setOn(on) { S.on = on; $('#pill').textContent = on ? 'Running' : 'Stopped'; $('#pill').className = 'pill ' + (on ? 'on' : 'off'); $('#toggle').textContent = on ? 'Stop engine' : 'Start engine'; chrome.storage.local.set({ engineOn: on }); }
function readForm() { for (const k of ['sbUrl', 'sbKey', 'visionUrl', 'brainUrl', 'about']) cfg[k] = $('#' + k).value.trim(); }
async function save() { readForm(); await chrome.storage.local.set({ cfg }); log('settings saved'); }
const setupPrompt = (rules) => `${rules}\n\nABOUT THE USER:\n${cfg.about}\n\nReply "Ready." if you understand.`;

$('#toggle').onclick = async () => { await save(); if (!ready()) return log('Fill Supabase URL + key first.'); if (!S.on) { await sbPatch('images?status=eq.processing', { status: 'pending' }).catch(() => {}); await sbPatch('clips?status=eq.processing', { status: 'pending' }).catch(() => {}); } setOn(!S.on); };
$('#save').onclick = save;
$('#ovision').onclick = () => ensureTab('vision').catch((e) => log(e.message));
$('#obrain').onclick = () => ensureTab('brain').catch((e) => log(e.message));
$('#setupV').onclick = async () => { await save(); log('sending vision setup…'); try { log('Vision: ' + (await ask('vision', setupPrompt(VISION_RULES))).slice(0, 120)); } catch (e) { log(e.message); } };
$('#setupB').onclick = async () => { await save(); log('sending brain setup…'); try { log('Brain: ' + (await brain(setupPrompt(BRAIN_RULES), 0)).slice(0, 120)); } catch (e) { log(e.message); } };
$('#sendRefs').onclick = async () => {
  for (const f of $('#refs').files) {
    const label = f.name.replace(/\.[^.]+$/, ''); log('sending reference: ' + label);
    try { await ask('vision', `REFERENCE IMAGE — "${label}". Memorise what this person/place looks like so you can recognise it in future photos. Do not use the JSON format now; just reply "OK".`, { b64: await toB64(f), mime: f.type || 'image/jpeg', name: f.name }); } catch (e) { log(e.message); break; }
  }
  log('references done');
};

(async () => {
  const st = await chrome.storage.local.get(['cfg', 'engineOn']); cfg = { ...cfg, ...(st.cfg || {}) };
  for (const k of ['sbUrl', 'sbKey', 'visionUrl', 'brainUrl', 'about']) $('#' + k).value = cfg[k] || '';
  ticker.postMessage({ t: 'tick', ms: 5000 });
  if (ready() && st.engineOn) { await sbPatch('images?status=eq.processing', { status: 'pending' }).catch(() => {}); await sbPatch('clips?status=eq.processing', { status: 'pending' }).catch(() => {}); setOn(true); $('#setup').open = false; log('engine auto-started'); }
  else log('Welcome. Fill the Setup section, then press Start engine.');
  ui();
})();


/* ───────── v2: pairing QR + combined screen time ───────── */
let usageRange = 0;
function renderQR() {
  if (!cfg.sbUrl || !cfg.sbKey) { $('#qr').textContent = 'Save Supabase URL + key first'; return; }
  if (!cfg.pairCode) { cfg.pairCode = Math.random().toString(36).slice(2, 8).toUpperCase(); chrome.storage.local.set({ cfg }); }
  const q = qrcode(0, 'L'); q.addData(JSON.stringify({ v: 1, u: cfg.sbUrl, k: cfg.sbKey, p: cfg.pairCode })); q.make();
  $('#qr').innerHTML = q.createSvgTag(4, 0); $('#pairInfo').textContent = 'Pair code: ' + cfg.pairCode;
}
const hm = (s) => { const m = Math.round(s / 60); return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + 'm'; };
async function renderUsage() {
  if (!ready()) return;
  const from = new Date(Date.now() - usageRange * 86400000).toLocaleDateString('en-CA');
  const rows = await sbGet(`usage?day=gte.${from}&limit=5000`); const m = {};
  for (const r of rows) { const a = m[r.app_key] || (m[r.app_key] = { label: r.label || r.app_key, phone: 0, laptop: 0 }); a[r.device === 'laptop' ? 'laptop' : 'phone'] += r.seconds || 0; if (r.device === 'laptop' && r.label) a.label = r.label; }
  const list = Object.values(m).map((a) => ({ ...a, t: a.phone + a.laptop })).sort((a, b) => b.t - a.t);
  const P = list.reduce((s, a) => s + a.phone, 0), L = list.reduce((s, a) => s + a.laptop, 0), mx = Math.max(1, ...list.map((a) => a.t));
  $('#usageTotal').textContent = `Total ${hm(P + L)}  ·  📱 ${hm(P)}  ·  💻 ${hm(L)}`;
  $('#usage').innerHTML = list.slice(0, 40).map((a) => `<div class="row" style="display:block"><div style="display:flex;justify-content:space-between"><b>${esc(a.label)}</b><span>${hm(a.t)} <small style="color:var(--mut)">📱 ${hm(a.phone)} · 💻 ${hm(a.laptop)}</small></span></div><div style="height:6px;background:var(--card2);border-radius:3px;margin-top:4px"><div style="height:6px;width:${Math.max(2, a.t / mx * 100)}%;background:var(--g);border-radius:3px"></div></div></div>`).join('') || '<span class="tag">no usage yet</span>';
  const { track } = await chrome.storage.local.get('track'); if (track && track.cur) $('#usageTotal').textContent += `   |   💻 Right now: ${track.cur.label} — ${hm((track.usage[track.cur.key] || {}).seconds || 0)} today`;
  const d = await sbGet('devices?order=last_seen.desc&limit=6');
  $('#devs').innerHTML = d.map((x) => `${x.device === 'phone' ? '📱' : '💻'} ${esc(x.label || '')} — seen ${fmt(x.last_seen)}`).join('<br>') || 'none yet';
}
document.querySelectorAll('.rng').forEach((b) => (b.onclick = () => { usageRange = +b.dataset.r; renderUsage().catch((e) => log(e.message)); }));
$('#newPair').onclick = () => { cfg.pairCode = ''; renderQR(); };
$('#save').addEventListener('click', () => setTimeout(renderQR, 300));
setTimeout(renderQR, 600); setInterval(() => renderUsage().catch(() => {}), 30000); setTimeout(() => renderUsage().catch(() => {}), 1000);
