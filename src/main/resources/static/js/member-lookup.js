// "Khách thành viên" panel of the counter sale page (staff/sell.html). Staff scan the member's QR (it holds
// only the member code) or type the member code / phone number; the member found is attached to the sale
// through the hidden memberId field. The discount shown is only a preview: the server checks the member
// again and prices the order itself. counter-payment.js listens for "bb:member-changed" to redo the total.
(function () {
  'use strict';

  const panel = document.getElementById('memberPanel');
  if (!panel) {
    return;
  }
  const q = (role) => panel.querySelector('[data-role="' + role + '"]');
  const idInput = q('member-id');
  const search = q('member-search');
  const queryInput = q('member-query');
  const findBtn = q('member-find');
  const scanBtn = q('member-scan');
  const scanStopBtn = q('member-scan-stop');
  const view = q('member-view');
  const card = q('member-card');
  const statusEl = q('member-status');
  const ageWarning = q('member-age-warning');
  const minAge = Number(panel.dataset.minAge) || 0;
  // Member codes: BB + 8 characters from the booking-code alphabet (no 0, O, 1, I, L), see CodeGenerator.
  const MEMBER_CODE = /BB[A-HJKMNP-Z2-9]{8}/;
  const lib = window.__Html5QrcodeLibrary__;
  let scanner = null;
  let busy = false;

  function setStatus(text, kind) {
    statusEl.textContent = text || '';
    statusEl.className = 'small mt-2 ' + (kind === 'error' ? 'text-danger' : 'text-muted');
  }

  function changed() {
    document.dispatchEvent(new CustomEvent('bb:member-changed'));
  }

  function attach(member) {
    idInput.value = member.id;
    panel.dataset.discountPercent = String(member.discountPercent || 0);
    q('member-name').textContent = member.fullName;
    q('member-code').textContent = member.memberCode || '';
    const info = ['Hạng ' + member.tierLabel + (member.discountPercent > 0 ? ' · giảm ' + member.discountPercent + '%' : '')];
    if (member.maskedPhone) {
      info.push(member.maskedPhone);
    }
    if (member.age !== null && member.age !== undefined) {
      info.push(member.age + ' tuổi');
    }
    q('member-info').textContent = info.join(' · ');
    const tooYoung = minAge > 0 && member.age !== null && member.age !== undefined && member.age < minAge;
    ageWarning.textContent = tooYoung
      ? 'Theo ngày sinh đã khai, khách mới ' + member.age + ' tuổi, chưa đủ ' + minAge + ' tuổi để xem phim này.'
      : '';
    ageWarning.classList.toggle('d-none', !tooYoung);
    card.classList.remove('d-none');
    search.classList.add('d-none');
    setStatus('Đã gắn khách thành viên vào đơn. Điểm được tích khi đơn thanh toán xong.');
    changed();
  }

  function detach() {
    idInput.value = '';
    delete panel.dataset.discountPercent;
    card.classList.add('d-none');
    search.classList.remove('d-none');
    queryInput.value = '';
    setStatus('');
    changed();
    queryInput.focus();
  }

  function lookup(text) {
    const value = String(text || '').trim();
    if (!value || busy) {
      return;
    }
    busy = true;
    findBtn.disabled = true;
    setStatus('Đang tìm thành viên...');
    const body = new URLSearchParams({ q: value });
    const headers = Object.assign({ Accept: 'application/json' }, window.bbCsrf ? window.bbCsrf() : {});
    fetch(panel.dataset.lookupUrl, {
      method: 'POST', headers: headers, body: body, credentials: 'same-origin', redirect: 'manual'
    })
      .then((response) => {
        const json = (response.headers.get('Content-Type') || '').indexOf('application/json') !== -1;
        if (response.ok && json) {
          return response.json().then(attach);
        }
        if (json) {
          return response.json().then((data) => setStatus((data && data.message) || 'Không tìm thấy thành viên', 'error'));
        }
        setStatus('Không tra cứu được (hết ca hoặc phiên đăng nhập). Tải lại trang.', 'error');
      })
      .catch(() => setStatus('Không kết nối được máy chủ, thử lại.', 'error'))
      .finally(() => {
        busy = false;
        findBtn.disabled = false;
      });
  }

  // ---- camera scan (same html5-qrcode library as the check-in page)

  function showScanning(on) {
    view.classList.toggle('d-none', !on);
    scanStopBtn.classList.toggle('d-none', !on);
    scanBtn.disabled = on;
  }

  async function stopScan() {
    if (scanner) {
      const s = scanner;
      scanner = null;
      try {
        await s.stop();
        s.clear();
      } catch (e) { /* already stopped */ }
    }
    showScanning(false);
  }

  function onScan(text) {
    if (!scanner) {
      return;
    }
    const match = String(text || '').toUpperCase().match(MEMBER_CODE);
    if (!match) {
      setStatus('Mã QR này không phải mã thành viên BBCinema.', 'error');
      return;
    }
    if (navigator.vibrate) {
      navigator.vibrate(80);
    }
    stopScan();
    queryInput.value = match[0];
    lookup(match[0]);
  }

  async function startScan() {
    if (!window.isSecureContext) {
      setStatus('Trình duyệt chỉ cho mở camera trên localhost hoặc HTTPS. Hãy nhập mã thành viên.', 'error');
      return;
    }
    if (!lib || !navigator.mediaDevices) {
      setStatus('Trình duyệt này không hỗ trợ camera. Hãy nhập mã thành viên.', 'error');
      return;
    }
    showScanning(true);
    setStatus('Đưa mã QR thành viên vào khung hình.');
    scanner = new lib.Html5Qrcode('memberScannerView', { verbose: false });
    try {
      await scanner.start({ facingMode: 'environment' }, { fps: 10, qrbox: { width: 220, height: 220 } }, onScan, () => {});
    } catch (err) {
      scanner = null;
      showScanning(false);
      const denied = err && (String(err).includes('NotAllowed') || String(err).includes('Permission'));
      setStatus(denied
        ? 'Bạn đã chặn quyền camera. Cho phép camera trong trình duyệt rồi thử lại.'
        : 'Không mở được camera (' + err + '). Hãy nhập mã thành viên.', 'error');
    }
  }

  // The browser may refill form fields on reload; the panel starts empty, so the member id must too.
  idInput.value = '';

  findBtn.addEventListener('click', () => lookup(queryInput.value));
  queryInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault(); // Enter here looks the member up; it must not submit the sale
      lookup(queryInput.value);
    }
  });
  scanBtn.addEventListener('click', startScan);
  scanStopBtn.addEventListener('click', () => {
    stopScan();
    setStatus('');
  });
  q('member-clear').addEventListener('click', detach);
  // Leaving the page with the camera on would keep it running until the tab closes.
  window.addEventListener('pagehide', stopScan);
})();
