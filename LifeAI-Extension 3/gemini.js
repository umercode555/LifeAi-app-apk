// Content script on gemini.google.com. Only does instant DOM actions; all waiting is orchestrated by the dashboard.
(() => {
  if (window.__lifeai) return; window.__lifeai = true;
  const q = (s) => document.querySelector(s);
  const editor = () => q('rich-textarea .ql-editor') || q('.ql-editor[contenteditable="true"]') || q('div[contenteditable="true"][role="textbox"]') || q('[contenteditable="true"]');
  const sendBtn = () => q('button.send-button') || q('button[aria-label*="Send message" i]') || q('button[aria-label="Send" i]');
  const stopBtn = () => q('button[aria-label*="Stop" i]') || q('button.stop-button') || q('mat-icon[fonticon="stop"]');
  const responses = () => [...document.querySelectorAll('model-response')];
  const off = (b) => !b || b.disabled || b.getAttribute('aria-disabled') === 'true' || b.classList.contains('disabled');
  const lastText = () => { const r = responses().pop(); if (!r) return ''; const m = r.querySelector('message-content') || r.querySelector('.markdown') || r; return (m.innerText || '').trim(); };
  const state = () => ({
    editor: !!editor(), sendReady: !off(sendBtn()), generating: !!stopBtn(), count: responses().length, text: lastText(), url: location.href,
    attached: !!q('uploader-file-preview, .file-preview-container, .attachment-preview, [data-test-id*="file-preview"], img[src^="blob:"]'),
  });
  const mkFile = (m) => { const bin = atob(m.b64), u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new File([u], m.name || 'photo.jpg', { type: m.mime || 'image/jpeg' }); };
  const H = {
    ping: () => ({ ok: true }),
    state: () => ({ ok: true, ...state() }),
    prep: () => { const e = editor(); if (!e) return { ok: false, error: 'Gemini input not found — open the tab and make sure you are logged in.' };
      e.focus(); document.execCommand('selectAll'); document.execCommand('delete'); return { ok: true, count: responses().length }; },
    type: (m) => { const e = editor(); e.focus();
      const ok = document.execCommand('insertText', false, m.text);
      if (!ok || !e.innerText.trim()) { e.textContent = m.text; e.dispatchEvent(new InputEvent('input', { bubbles: true, data: m.text, inputType: 'insertText' })); }
      return { ok: true }; },
    attach: (m) => { const e = editor(); e.focus(); const dt = new DataTransfer(); dt.items.add(mkFile(m));
      e.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); return { ok: true }; },
    attachDrop: (m) => { const e = editor(); const t = e.closest('.text-input-field, .input-area, rich-textarea') || e; const dt = new DataTransfer(); dt.items.add(mkFile(m));
      for (const n of ['dragenter', 'dragover', 'drop']) t.dispatchEvent(new DragEvent(n, { dataTransfer: dt, bubbles: true, cancelable: true })); return { ok: true }; },
    send: () => { const b = sendBtn(); if (b && !off(b)) { b.click(); return { ok: true }; }
      const e = editor(); e.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true })); return { ok: true, viaEnter: true }; },
  };
  chrome.runtime.onMessage.addListener((m, _s, res) => {
    const f = H[m && m.cmd]; if (!f) return;
    try { res(f(m)); } catch (e) { res({ ok: false, error: String(e) }); }
  });
})();
