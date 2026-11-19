// Admin seat layout editor: click a seat to cycle Standard -> VIP -> Couple -> gap -> Standard.
(function () {
  'use strict';
  const editor = document.getElementById('seatEditor');
  if (!editor) {
    return;
  }
  const order = ['STANDARD', 'VIP', 'COUPLE', 'GAP'];
  const status = document.getElementById('editorStatus');

  editor.addEventListener('click', (e) => {
    const seat = e.target.closest('.seat[data-seat-id]');
    if (!seat) {
      return;
    }
    const current = seat.dataset.active === 'true' ? seat.dataset.type : 'GAP';
    const next = order[(order.indexOf(current) + 1) % order.length];
    const type = next === 'GAP' ? 'STANDARD' : next;
    const active = next !== 'GAP';
    const body = new URLSearchParams({ type: type, active: String(active) });
    fetch(editor.dataset.url + '/' + seat.dataset.seatId, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, window.bbCsrf()),
      body: body
    }).then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((saved) => {
        seat.dataset.type = saved.type;
        seat.dataset.active = String(saved.active);
        seat.className = 'seat ' + saved.type + (saved.active ? '' : ' inactive-seat');
        status.textContent = 'Đã lưu ghế ' + seat.dataset.label + ': ' + (saved.active ? saved.type : 'lối đi');
      })
      .catch(() => { status.textContent = 'Không lưu được, vui lòng tải lại trang.'; });
  });
})();
