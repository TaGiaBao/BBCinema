// Counts down the seat hold time; when it reaches zero the page reloads to show the expired state.
(function () {
  'use strict';
  document.querySelectorAll('[data-expires-at]').forEach((el) => {
    const deadline = Number(el.dataset.expiresAt);
    function tick() {
      const left = Math.max(0, Math.floor((deadline - Date.now()) / 1000));
      el.textContent = String(Math.floor(left / 60)).padStart(2, '0') + ':' + String(left % 60).padStart(2, '0');
      if (left === 0) {
        el.textContent = 'Hết giờ giữ ghế';
        document.querySelectorAll('[data-disable-on-expire]').forEach((b) => { b.disabled = true; });
        return;
      }
      setTimeout(tick, 1000);
    }
    tick();
  });
})();
