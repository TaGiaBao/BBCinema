// First-visit cinema chooser. Loaded only while the visitor has no BB_CINEMA cookie. Picking a
// cinema follows the normal /select-cinema link, which sets the cookie. "Dùng vị trí của tôi"
// asks the browser for the position and picks the nearest cinema that has coordinates.
(function () {
  'use strict';
  const modalEl = document.getElementById('cinemaChooser');
  if (!modalEl || !window.bootstrap) {
    return;
  }
  const DISMISS_KEY = 'bbCinemaChooserClosed';
  const locateBtn = modalEl.querySelector('[data-role="locate"]');
  const statusEl = modalEl.querySelector('[data-role="locate-status"]');
  const choices = Array.from(modalEl.querySelectorAll('.cinema-choices [data-lat][data-lng]'));
  const modal = new bootstrap.Modal(modalEl);

  function wasDismissed() {
    try {
      return sessionStorage.getItem(DISMISS_KEY) === '1';
    } catch (e) {
      return false;
    }
  }

  // Closing without choosing keeps the default cinema; don't ask again on every page this visit.
  modalEl.addEventListener('hidden.bs.modal', () => {
    try {
      sessionStorage.setItem(DISMISS_KEY, '1');
    } catch (e) { /* storage blocked: the dialog will simply show again */ }
  });

  function setStatus(text, isError) {
    statusEl.textContent = text;
    statusEl.className = 'small mb-3 ' + (isError ? 'text-danger' : 'text-muted');
  }

  /** Great-circle distance in kilometres (haversine formula). */
  function distanceKm(lat1, lng1, lat2, lng2) {
    const rad = (d) => (d * Math.PI) / 180;
    const dLat = rad(lat2 - lat1);
    const dLng = rad(lng2 - lng1);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2;
    return 6371 * 2 * Math.asin(Math.sqrt(a));
  }

  const formatKm = (km) => (km < 1 ? Math.round(km * 1000) + ' m' : km.toLocaleString('vi-VN', { maximumFractionDigits: 1 }) + ' km');

  function onPosition(pos) {
    const { latitude, longitude } = pos.coords;
    const ranked = choices
      .map((el) => ({ el, km: distanceKm(latitude, longitude, Number(el.dataset.lat), Number(el.dataset.lng)) }))
      .sort((a, b) => a.km - b.km);
    const list = modalEl.querySelector('.cinema-choices');
    ranked.forEach(({ el, km }) => {
      const badge = el.querySelector('[data-role="distance"]');
      badge.textContent = formatKm(km);
      badge.classList.remove('d-none');
      list.appendChild(el); // nearest first
    });
    const nearest = ranked[0];
    nearest.el.classList.add('nearest');
    setStatus('Rạp gần bạn nhất: ' + nearest.el.querySelector('strong').textContent
      + ' (' + formatKm(nearest.km) + '). Đang chọn rạp này...');
    window.location.href = nearest.el.href;
  }

  function onError(err) {
    locateBtn.disabled = false;
    const denied = err && err.code === 1;
    setStatus(denied
      ? 'Bạn chưa cho phép truy cập vị trí. Vui lòng chọn rạp trong danh sách bên dưới.'
      : 'Không lấy được vị trí của bạn. Vui lòng chọn rạp trong danh sách bên dưới.', true);
  }

  locateBtn.addEventListener('click', () => {
    if (!navigator.geolocation || !window.isSecureContext) {
      setStatus('Trình duyệt không hỗ trợ lấy vị trí ở trang này. Vui lòng chọn rạp bên dưới.', true);
      return;
    }
    if (choices.length === 0) {
      setStatus('Các rạp chưa có toạ độ trên bản đồ. Vui lòng chọn rạp bên dưới.', true);
      return;
    }
    locateBtn.disabled = true;
    setStatus('Đang xác định vị trí của bạn...');
    navigator.geolocation.getCurrentPosition(onPosition, onError, { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 });
  });

  if (!wasDismissed()) {
    modal.show();
  }
})();
