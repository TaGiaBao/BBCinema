// Seat selection: toggles seats, keeps the hidden checkboxes in sync and shows the running total.
// The map also polls the seat API so seats that other people hold or buy turn grey within a few
// seconds. The row lock on the server still decides who gets a seat; this only spares the user a
// failed "Tiếp tục".
(function () {
  'use strict';
  // Translated text for the page language (window.bbT from layout/main.html), Vietnamese otherwise.
  const t = (key, fallback, ...args) => (window.bbT ? window.bbT(key, fallback) : fallback)
    .replace(/\{(\d)\}/g, (m, i) => (args[i] !== undefined ? args[i] : m));
  const form = document.getElementById('seatForm');
  if (!form) {
    return;
  }
  const money = (n) => n.toLocaleString('vi-VN') + 'đ';
  const selectedEl = document.getElementById('selectedSeats');
  const totalEl = document.getElementById('seatTotal');
  const submit = document.getElementById('seatSubmit');
  const live = form.querySelector('.seat-map-live');
  const notice = form.querySelector('[data-role="seat-notice"]');
  const countEl = document.querySelector('[data-role="available-count"]');
  const POLL_MS = 2500;

  function refresh() {
    const chosen = Array.from(form.querySelectorAll('.seat.selected[data-id]'));
    const total = chosen.reduce((sum, s) => sum + Number(s.dataset.price), 0);
    if (selectedEl) {
      selectedEl.textContent = chosen.length ? chosen.map((s) => s.dataset.label).join(', ') : t('noSeat', 'Chưa chọn ghế');
    }
    if (totalEl) {
      totalEl.textContent = money(total);
    }
    if (submit) {
      submit.disabled = chosen.length === 0;
    }
  }

  function checkboxFor(seat) {
    return form.querySelector('input[type="checkbox"][value="' + seat.dataset.id + '"]');
  }

  function setSelected(seat, selected) {
    seat.classList.toggle('selected', selected);
    seat.setAttribute('aria-pressed', selected);
    const box = checkboxFor(seat);
    if (box) {
      box.checked = selected;
    }
  }

  form.addEventListener('click', (e) => {
    const seat = e.target.closest('.seat[data-id]');
    if (!seat || seat.classList.contains('taken')) {
      return;
    }
    e.preventDefault();
    setSelected(seat, !seat.classList.contains('selected'));
    refresh();
  });
  refresh();

  if (!live || !live.dataset.showtimeId || !window.fetch) {
    return;
  }

  function showNotice(labels) {
    if (!notice) {
      return;
    }
    notice.textContent = t('seatLost', 'Ghế {0} vừa được người khác chọn nên đã bị bỏ khỏi lựa chọn của bạn. Vui lòng chọn ghế khác.', labels.join(', '));
    notice.classList.remove('d-none');
  }

  /** Brings one seat button in line with the status from the server. */
  function applyStatus(seat, status) {
    const box = checkboxFor(seat);
    const free = status === 'AVAILABLE';
    const wasTaken = seat.classList.contains('taken');
    const wasHeld = seat.classList.contains('held');
    const held = status === 'HELD';
    if (free === !wasTaken && held === wasHeld) {
      return false;
    }
    const lostSelection = !free && seat.classList.contains('selected');
    if (lostSelection) {
      setSelected(seat, false);
    }
    seat.classList.toggle('taken', !free);
    seat.classList.toggle('held', held);
    seat.disabled = !free;
    if (box) {
      box.disabled = !free;
    }
    const type = seat.title.split(' · ')[1] || '';
    seat.setAttribute('aria-label', t('seatAria', 'Ghế {0}, {1}, {2}', seat.dataset.label, type,
      free ? money(Number(seat.dataset.price)) : held ? t('seatHeld', 'đang có người giữ') : t('seatSold', 'đã bán')));
    seat.classList.remove('just-changed');
    void seat.offsetWidth; // restart the flash animation
    seat.classList.add('just-changed');
    return lostSelection;
  }

  let timer = null;
  let busy = false;

  async function poll() {
    if (busy || document.hidden) {
      return;
    }
    busy = true;
    try {
      const res = await fetch('/api/showtimes/' + live.dataset.showtimeId + '/seats',
        { cache: 'no-store', headers: { Accept: 'application/json' } });
      if (!res.ok) {
        return;
      }
      const map = await res.json();
      const statusById = new Map();
      map.rows.forEach((row) => row.cells.forEach((cell) => {
        if (cell.showtimeSeatId != null) {
          statusById.set(String(cell.showtimeSeatId), cell.status);
        }
      }));
      const lost = [];
      form.querySelectorAll('.seat[data-id]').forEach((seat) => {
        const status = statusById.get(seat.dataset.id);
        if (status && applyStatus(seat, status)) {
          lost.push(seat.dataset.label);
        }
      });
      if (lost.length) {
        showNotice(lost);
      }
      if (countEl && typeof map.availableCount === 'number') {
        countEl.textContent = map.availableCount;
      }
      refresh();
    } catch (e) {
      // Network hiccup: try again on the next tick.
    } finally {
      busy = false;
    }
  }

  function startPolling() {
    if (!timer) {
      timer = setInterval(poll, POLL_MS);
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      poll();
    }
  });
  // Stop polling once the form is sent, so a late update cannot untick seats being submitted.
  form.addEventListener('submit', () => {
    clearInterval(timer);
    timer = null;
  });
  startPolling();
})();
