// Plus/minus buttons for snack combos and the live order total.
(function () {
  'use strict';
  const form = document.getElementById('comboForm');
  if (!form) {
    return;
  }
  const money = (n) => n.toLocaleString('vi-VN') + 'đ';
  const ticketAmount = Number(form.dataset.ticketAmount);
  const comboTotal = document.getElementById('comboTotal');
  const grandTotal = document.getElementById('grandTotal');

  function refresh() {
    let sum = 0;
    form.querySelectorAll('input[data-price]').forEach((input) => {
      sum += Number(input.dataset.price) * Math.max(0, Number(input.value) || 0);
    });
    comboTotal.textContent = money(sum);
    grandTotal.textContent = money(sum + ticketAmount);
  }

  form.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-step]');
    if (!btn) {
      return;
    }
    const input = form.querySelector('#' + btn.dataset.target);
    const next = Math.min(20, Math.max(0, (Number(input.value) || 0) + Number(btn.dataset.step)));
    input.value = next;
    refresh();
  });
  form.addEventListener('input', refresh);
  refresh();
})();
