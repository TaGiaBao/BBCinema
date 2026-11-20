// Counter sale payment box (staff/sell.html). Shows the total to pay (seats + snacks) and, for cash,
// the change to hand back or the amount still missing as staff type what the customer gave. For card,
// staff type the amount on the POS slip, which must equal the total. The server checks the same rules.
(function () {
  'use strict';
  const form = document.getElementById('seatForm');
  const box = document.querySelector('[data-role="amount-box"]');
  if (!form || !box) {
    return;
  }
  const money = (n) => n.toLocaleString('vi-VN') + 'đ';
  const input = document.getElementById('amountReceived');
  const hidden = box.querySelector('[data-role="amount-value"]');
  const label = box.querySelector('[data-role="amount-label"]');
  const quick = box.querySelector('[data-role="quick-cash"]');
  const exactBtn = quick.querySelector('[data-exact]');
  const result = document.getElementById('amountResult');
  const submit = document.getElementById('seatSubmit');
  const approvalBox = document.querySelector('[data-role="card-approval"]');
  const seatTotalEl = document.getElementById('seatTotal');
  const comboTotalEl = document.getElementById('comboTotal');
  const grandTotalEl = document.getElementById('grandTotal');
  const comboInputs = Array.from(form.querySelectorAll('input[data-price]'));
  let cardTouched = false;

  const method = () => (form.querySelector('input[name="paymentMethod"]:checked') || {}).value || 'CASH';
  const digits = (text) => Number(String(text).replace(/\D/g, '')) || 0;

  function seatTotal() {
    return Array.from(form.querySelectorAll('.seat.selected[data-id]'))
      .reduce((sum, s) => sum + Number(s.dataset.price), 0);
  }

  function comboTotal() {
    return comboInputs.reduce((sum, i) => sum + Math.max(0, Number(i.value) || 0) * Number(i.dataset.price), 0);
  }

  function total() {
    return seatTotal() + comboTotal();
  }

  /** Amounts a customer is likely to hand over: the total rounded up to common notes. */
  function suggestions(t) {
    const out = [];
    [10000, 50000, 100000, 200000, 500000].forEach((step) => {
      const v = Math.ceil(t / step) * step;
      if (v > t && !out.includes(v)) {
        out.push(v);
      }
    });
    return out.slice(0, 4);
  }

  function setAmount(value) {
    input.value = value > 0 ? value.toLocaleString('vi-VN') : '';
    update();
  }

  function renderQuick(t) {
    quick.querySelectorAll('[data-amount]').forEach((b) => b.remove());
    if (method() !== 'CASH' || t <= 0) {
      return;
    }
    suggestions(t).forEach((v) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'btn btn-sm btn-outline-secondary';
      b.dataset.amount = v;
      b.textContent = money(v);
      b.addEventListener('click', () => setAmount(v));
      quick.appendChild(b);
    });
  }

  function update() {
    const m = method();
    const t = total();
    const seats = seatTotal();
    comboTotalEl.textContent = money(comboTotal());
    grandTotalEl.textContent = money(t);
    approvalBox.classList.toggle('d-none', m !== 'CARD');
    approvalBox.querySelector('input').required = m === 'CARD';

    const takesAmount = m === 'CASH' || m === 'CARD';
    box.classList.toggle('d-none', !takesAmount);
    let ok = true;
    if (takesAmount) {
      label.textContent = m === 'CASH' ? 'Khách đưa' : 'Số tiền trên hoá đơn POS';
      exactBtn.textContent = m === 'CASH' ? 'Đưa đủ' : 'Đúng tổng tiền';
      if (m === 'CARD' && !cardTouched) {
        input.value = t > 0 ? t.toLocaleString('vi-VN') : '';
      }
      const received = digits(input.value);
      hidden.value = input.value.trim() === '' ? '' : String(received);
      result.className = 'mt-2 fw-semibold';
      if (input.value.trim() === '' || t === 0) {
        result.textContent = '';
        ok = t === 0;
      } else if (m === 'CASH') {
        const diff = received - t;
        if (diff >= 0) {
          result.textContent = 'Tiền thừa trả khách: ' + money(diff);
          result.classList.add('text-success');
        } else {
          result.textContent = 'Khách còn thiếu: ' + money(-diff);
          result.classList.add('text-danger');
          ok = false;
        }
      } else {
        const diff = received - t;
        if (diff === 0) {
          result.textContent = 'Khớp tổng tiền';
          result.classList.add('text-success');
        } else {
          result.textContent = 'Hoá đơn POS ' + (diff > 0 ? 'thừa ' : 'thiếu ') + money(Math.abs(diff))
            + '. Huỷ giao dịch trên máy POS và quẹt lại.';
          result.classList.add('text-danger');
          ok = false;
        }
      }
      renderQuick(t);
    } else {
      hidden.value = '';
    }
    submit.disabled = seats === 0 || !ok;
  }

  input.addEventListener('input', () => {
    if (method() === 'CARD') {
      cardTouched = true;
    }
    const n = digits(input.value);
    input.value = n > 0 ? n.toLocaleString('vi-VN') : '';
    update();
  });
  exactBtn.addEventListener('click', () => {
    cardTouched = false;
    setAmount(total());
  });
  form.querySelectorAll('input[name="paymentMethod"]').forEach((r) => r.addEventListener('change', () => {
    cardTouched = false;
    input.value = '';
    update();
    input.focus();
  }));
  comboInputs.forEach((i) => i.addEventListener('input', update));
  // seat-map.js redraws the seat total (and the submit button) when seats are picked or taken by others.
  if (seatTotalEl) {
    new MutationObserver(update).observe(seatTotalEl, { childList: true, characterData: true, subtree: true });
  }
  form.addEventListener('submit', (e) => {
    update();
    if (submit.disabled) {
      e.preventDefault();
      input.focus();
    }
  });
  update();
})();
