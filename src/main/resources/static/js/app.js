// Shared scripts for the public site: CSRF helper and the "Mua vé" showtime picker.
(function () {
  'use strict';
  // Translated text for the page language (window.bbT from layout/main.html), Vietnamese otherwise.
  const t = (key, fallback, ...args) => (window.bbT ? window.bbT(key, fallback) : fallback)
    .replace(/\{(\d)\}/g, (m, i) => (args[i] !== undefined ? args[i] : m));

  /** CSRF token for fetch() calls that use the login cookie. */
  window.bbCsrf = function () {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    return token && header ? { [header.content]: token.content } : {};
  };

  /** Scrolls each date strip so the chosen day is visible (it may be off-screen on a phone). */
  function revealActiveDate(strip) {
    const active = strip.querySelector('.active');
    if (active && strip.scrollWidth > strip.clientWidth) {
      strip.scrollLeft = active.offsetLeft - strip.offsetLeft - (strip.clientWidth - active.offsetWidth) / 2;
    }
  }
  window.bbRevealActiveDate = revealActiveDate;
  document.querySelectorAll('.date-strip').forEach(revealActiveDate);

  const pad = (n) => String(n).padStart(2, '0');
  const isoDate = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  const dayNames = t('dayNames', 'CN,T2,T3,T4,T5,T6,T7').split(',');

  const modalEl = document.getElementById('showtimeModal');
  if (!modalEl || !window.bootstrap) {
    return;
  }
  const modal = new bootstrap.Modal(modalEl);
  const cinemaId = document.body.dataset.cinemaId;
  const cinemaName = document.body.dataset.cinemaName || '';
  const titleEl = modalEl.querySelector('[data-role="title"]');
  const datesEl = modalEl.querySelector('[data-role="dates"]');
  const timesEl = modalEl.querySelector('[data-role="times"]');
  let movieId = null;

  function renderDates(selected) {
    datesEl.innerHTML = '';
    const today = new Date();
    const days = Number(modalEl.dataset.bookingDays) || 14;
    for (let i = 0; i < days; i++) {
      const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() + i);
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = isoDate(d) === selected ? 'active' : '';
      btn.innerHTML = '<span class="day">' + pad(d.getDate()) + '</span><span class="dow">' +
        (i === 0 ? t('today', 'Hôm nay') : dayNames[d.getDay()]) + ' · ' + pad(d.getMonth() + 1) + '</span>';
      btn.addEventListener('click', () => load(isoDate(d)));
      datesEl.appendChild(btn);
    }
    revealActiveDate(datesEl);
  }
  // The strip has no width until the popup is visible, so centre the chosen day again then.
  modalEl.addEventListener('shown.bs.modal', () => revealActiveDate(datesEl));

  function load(date) {
    renderDates(date);
    timesEl.innerHTML = '<div class="text-muted py-3">' + t('loadingShowtimes', 'Đang tải suất chiếu...') + '</div>';
    fetch('/api/movies/' + movieId + '/showtimes?cinemaId=' + encodeURIComponent(cinemaId) + '&date=' + date)
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((list) => {
        if (list.length === 0) {
          timesEl.innerHTML = '<div class="text-muted py-3">' + t('noShowtimes', 'Không có suất chiếu tại {0} trong ngày này. Hãy chọn ngày khác hoặc đổi rạp ở đầu trang.', cinemaName) + '</div>';
          return;
        }
        const groups = {};
        list.forEach((s) => { const key = s.format + ' ' + (s.language || t('subtitled', 'Phụ đề')); (groups[key] = groups[key] || []).push(s); });
        timesEl.innerHTML = '';
        Object.keys(groups).forEach((format) => {
          const block = document.createElement('div');
          block.className = 'mb-3';
          block.innerHTML = '<div class="fw-bold mb-2">' + format + '</div><div class="d-flex flex-wrap gap-2"></div>';
          const row = block.lastElementChild;
          groups[format].forEach((s) => {
            const a = document.createElement('a');
            a.className = 'time-btn' + (s.special ? ' special' : '');
            a.href = '/booking/showtime/' + s.id;
            a.innerHTML = s.startTime.substring(11, 16) + '<small>' + s.roomName + (s.special ? ' · ' + t('special', 'Đặc biệt') : '') + '</small>';
            row.appendChild(a);
          });
          timesEl.appendChild(block);
        });
      })
      .catch(() => {
        timesEl.innerHTML = '<div class="text-danger py-3">' + t('loadError', 'Không tải được suất chiếu. Vui lòng thử lại.') + '</div>';
      });
  }

  document.addEventListener('click', (e) => {
    const trigger = e.target.closest('[data-buy-movie]');
    if (!trigger) {
      return;
    }
    e.preventDefault();
    if (!cinemaId) {
      alert(t('noCinema', 'Chưa có rạp nào đang hoạt động.'));
      return;
    }
    movieId = trigger.dataset.buyMovie;
    titleEl.textContent = trigger.dataset.movieTitle + ' · ' + cinemaName;
    load(isoDate(new Date()));
    modal.show();
  });
})();
