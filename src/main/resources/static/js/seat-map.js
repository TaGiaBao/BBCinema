// Seat selection: toggles seats, keeps the hidden checkboxes in sync and shows the running total.
(function () {
  'use strict';
  const form = document.getElementById('seatForm');
  if (!form) {
    return;
  }
  const money = (n) => n.toLocaleString('vi-VN') + 'đ';
  const selectedEl = document.getElementById('selectedSeats');
  const totalEl = document.getElementById('seatTotal');
  const submit = document.getElementById('seatSubmit');

  function refresh() {
    const chosen = Array.from(form.querySelectorAll('.seat.selected[data-id]'));
    const total = chosen.reduce((sum, s) => sum + Number(s.dataset.price), 0);
    if (selectedEl) {
      selectedEl.textContent = chosen.length ? chosen.map((s) => s.dataset.label).join(', ') : 'Chưa chọn ghế';
    }
    if (totalEl) {
      totalEl.textContent = money(total);
    }
    if (submit) {
      submit.disabled = chosen.length === 0;
    }
  }

  form.addEventListener('click', (e) => {
    const seat = e.target.closest('.seat[data-id]');
    if (!seat || seat.classList.contains('taken')) {
      return;
    }
    e.preventDefault();
    const box = form.querySelector('input[type="checkbox"][value="' + seat.dataset.id + '"]');
    seat.classList.toggle('selected');
    seat.setAttribute('aria-pressed', seat.classList.contains('selected'));
    if (box) {
      box.checked = seat.classList.contains('selected');
    }
    refresh();
  });
  refresh();
})();
