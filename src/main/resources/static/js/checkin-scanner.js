// Camera QR scanner for the staff check-in page. The QR on each ticket holds the 8-character
// booking code; a successful scan opens /staff/checkin?code=... which shows the ticket and the
// confirm button. The camera turns itself back on after the page reloads, until staff turn it off.
(function () {
  'use strict';

  const root = document.getElementById('scanner');
  if (!root) {
    return;
  }
  const startBtn = root.querySelector('[data-role="start"]');
  const stopBtn = root.querySelector('[data-role="stop"]');
  const camSelect = root.querySelector('[data-role="cameras"]');
  const view = root.querySelector('[data-role="view"]');
  const statusEl = root.querySelector('[data-role="status"]');
  const currentCode = root.dataset.currentCode || '';
  const STATE_KEY = 'bbScannerOn';
  const CAMERA_KEY = 'bbScannerCamera';
  // Booking codes use A-Z without I, L, O and digits 2-9 (see CodeGenerator).
  const CODE_PATTERN = /[A-HJKMNP-Z2-9]{8}/;

  const lib = window.__Html5QrcodeLibrary__;
  let scanner = null;
  let handled = false;

  function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = 'small mt-2 ' + (kind === 'error' ? 'text-danger' : 'text-muted');
  }

  function remember(key, value) {
    try {
      if (value === null) {
        sessionStorage.removeItem(key);
      } else {
        sessionStorage.setItem(key, value);
      }
    } catch (e) { /* storage blocked: the scanner simply won't resume by itself */ }
  }

  function recall(key) {
    try {
      return sessionStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  /** Extracts the booking code from the scanned text (a bare code or a link that ends with it). */
  function extractCode(text) {
    const upper = String(text || '').trim().toUpperCase();
    if (/^[A-Z0-9]{8}$/.test(upper)) {
      return upper;
    }
    const tail = upper.split(/[/?=#&\s]/).filter(Boolean).pop() || '';
    const match = tail.match(CODE_PATTERN) || upper.match(CODE_PATTERN);
    return match ? match[0] : null;
  }

  function onScan(text) {
    if (handled) {
      return;
    }
    const code = extractCode(text);
    if (!code) {
      setStatus('Mã QR không phải vé BBCinema: ' + text, 'error');
      return;
    }
    if (code === currentCode) {
      // The ticket on screen is still in front of the camera; wait for the next one.
      setStatus('Đang hiển thị vé ' + code + '. Đưa vé tiếp theo vào camera.');
      return;
    }
    handled = true;
    setStatus('Đã đọc mã ' + code + ', đang tra cứu...');
    if (navigator.vibrate) {
      navigator.vibrate(80);
    }
    window.location.href = '/staff/checkin?code=' + encodeURIComponent(code);
  }

  function showRunning(running) {
    startBtn.classList.toggle('d-none', running);
    stopBtn.classList.toggle('d-none', !running);
    view.classList.toggle('d-none', !running);
  }

  async function fillCameraList() {
    try {
      const cameras = await lib.Html5Qrcode.getCameras();
      if (cameras.length < 2) {
        return;
      }
      camSelect.innerHTML = '';
      cameras.forEach((cam, i) => {
        const opt = document.createElement('option');
        opt.value = cam.id;
        opt.textContent = cam.label || 'Camera ' + (i + 1);
        camSelect.appendChild(opt);
      });
      const saved = recall(CAMERA_KEY);
      if (saved && cameras.some((c) => c.id === saved)) {
        camSelect.value = saved;
      }
      camSelect.classList.remove('d-none');
    } catch (e) { /* camera list is optional */ }
  }

  async function start() {
    if (!window.isSecureContext) {
      setStatus('Trình duyệt chỉ cho mở camera trên localhost hoặc HTTPS. Hãy nhập mã vé bên dưới.', 'error');
      return;
    }
    if (!lib || !navigator.mediaDevices) {
      setStatus('Trình duyệt này không hỗ trợ camera. Hãy nhập mã vé bên dưới.', 'error');
      return;
    }
    if (scanner) {
      await stop(false);
    }
    showRunning(true);
    setStatus('Đang mở camera...');
    scanner = new lib.Html5Qrcode('scannerView', { verbose: false });
    const camera = camSelect.value ? { deviceId: { exact: camSelect.value } } : { facingMode: 'environment' };
    try {
      await scanner.start(camera, { fps: 10, qrbox: { width: 240, height: 240 } }, onScan, () => {});
      remember(STATE_KEY, '1');
      setStatus('Đưa mã QR trên vé vào khung hình.');
      fillCameraList();
    } catch (err) {
      scanner = null;
      showRunning(false);
      remember(STATE_KEY, null);
      const denied = err && (String(err).includes('NotAllowed') || String(err).includes('Permission'));
      setStatus(denied
        ? 'Bạn đã chặn quyền camera. Cho phép camera trong trình duyệt rồi bấm "Bật camera" lại.'
        : 'Không mở được camera (' + err + '). Hãy nhập mã vé bên dưới.', 'error');
    }
  }

  async function stop(forget) {
    if (forget) {
      remember(STATE_KEY, null);
    }
    if (scanner) {
      try {
        await scanner.stop();
        scanner.clear();
      } catch (e) { /* already stopped */ }
      scanner = null;
    }
    showRunning(false);
    if (forget) {
      setStatus('');
    }
  }

  startBtn.addEventListener('click', start);
  stopBtn.addEventListener('click', () => stop(true));
  camSelect.addEventListener('change', () => {
    remember(CAMERA_KEY, camSelect.value);
    start();
  });

  if (recall(STATE_KEY) === '1') {
    start();
  }
})();
