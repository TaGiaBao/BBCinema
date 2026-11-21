// Ticket type per seat on the counter sale page (staff/sell.html): adult, HSSV, child, senior. One select per chosen
// seat, named audience_<showtime seat id>. The discount shown here is a preview worked out like
// AudienceTicketService.discountOn; the server checks the rules (type on, holidays, P/K films, couple seats) and
// prices the sale itself. counter-payment.js listens for "bb:audience-changed".
(function () {
  'use strict';
  const form = document.getElementById('seatForm');
  const panel = document.getElementById('audiencePanel');
  const template = document.getElementById('audienceOptions');
  if (!form || !panel || !template) {
    return;
  }
  const list = panel.querySelector('[data-role="audience-list"]');
  const empty = panel.querySelector('[data-role="audience-empty"]');
  const seatTotalEl = document.getElementById('seatTotal');
  const money = (n) => n.toLocaleString('vi-VN') + 'đ';
  /** Seat id -> chosen type, kept while the seat stays selected. */
  const chosen = new Map();

  function discountOn(option, price) {
    if (!option || option.value === 'ADULT') {
      return 0;
    }
    const value = Number(option.dataset.value) || 0;
    const off = option.dataset.kind === 'AMOUNT' ? value : Math.floor(price * value / 100000) * 1000;
    return Math.max(0, Math.min(off, price));
  }

  function selectFor(seat) {
    const select = document.createElement('select');
    select.className = 'form-select form-select-sm';
    select.name = 'audience_' + seat.dataset.id;
    select.setAttribute('aria-label', 'Loại vé ghế ' + seat.dataset.label);
    template.content.querySelectorAll('option').forEach((o) => select.appendChild(o.cloneNode(true)));
    if (seat.dataset.type === 'COUPLE') {
      // Couple seats are never discounted: only the adult ticket is left.
      select.querySelectorAll('option:not([value="ADULT"])').forEach((o) => { o.disabled = true; });
    }
    const previous = chosen.get(seat.dataset.id);
    const keep = previous && select.querySelector('option[value="' + previous + '"]:not([disabled])');
    select.value = keep ? previous : 'ADULT';
    select.addEventListener('change', () => {
      chosen.set(seat.dataset.id, select.value);
      recompute();
    });
    return select;
  }

  let shownKey = null;

  function render() {
    const seats = Array.from(form.querySelectorAll('.seat.selected[data-id]'));
    // seat-map.js rewrites the total on every poll: redraw only when the chosen seats changed, so an open select
    // is not closed under staff's hand.
    const key = seats.map((s) => s.dataset.id).join(',');
    if (key === shownKey) {
      return;
    }
    shownKey = key;
    const ids = new Set(seats.map((s) => s.dataset.id));
    Array.from(chosen.keys()).forEach((id) => { if (!ids.has(id)) chosen.delete(id); });
    list.replaceChildren();
    seats.forEach((seat) => {
      const row = document.createElement('div');
      row.className = 'd-flex align-items-center gap-2 py-1';
      row.dataset.price = seat.dataset.price;
      const label = document.createElement('span');
      label.className = 'fw-semibold';
      label.style.minWidth = '3.2rem';
      label.textContent = seat.dataset.label;
      const price = document.createElement('span');
      price.className = 'small tabular text-end ms-auto';
      price.style.minWidth = '5.5rem';
      price.dataset.role = 'audience-price';
      row.append(label, selectFor(seat), price);
      if (seat.dataset.type === 'COUPLE') {
        row.title = 'Ghế đôi không áp dụng vé ưu đãi';
      }
      list.appendChild(row);
    });
    empty.classList.toggle('d-none', seats.length > 0);
    recompute();
  }

  function recompute() {
    let total = 0;
    list.querySelectorAll('select').forEach((select) => {
      const row = select.parentElement;
      const price = Number(row.dataset.price) || 0;
      const off = discountOn(select.selectedOptions[0], price);
      total += off;
      row.querySelector('[data-role="audience-price"]').textContent = off > 0
        ? money(price - off) + ' (-' + money(off) + ')' : money(price);
    });
    panel.dataset.discount = String(total);
    document.dispatchEvent(new CustomEvent('bb:audience-changed'));
  }

  // seat-map.js rewrites the seat total whenever seats are picked, dropped or taken by someone else.
  if (seatTotalEl) {
    new MutationObserver(render).observe(seatTotalEl, { childList: true, characterData: true, subtree: true });
  }
  // After a failed sale the server hands back "seatId:TYPE,...": pick those seats again (if still free) with
  // their ticket types, so staff only fix what went wrong.
  (panel.dataset.keptSeats || '').split(',').forEach((pair) => {
    const [id, type] = pair.split(':');
    const seat = id && form.querySelector('.seat[data-id="' + id + '"]');
    if (!seat || seat.classList.contains('taken') || seat.classList.contains('selected')) {
      return;
    }
    chosen.set(id, type || 'ADULT');
    seat.click(); // seat-map.js selects it and updates the totals
  });
  render();
})();
