// Bank transfer page (customer and counter): every 3 seconds, ask whether the money has arrived.
// When the booking is paid, open the ticket; when the hold ran out, reload so the page explains it.
(function () {
  const box = document.querySelector('[data-status-url]');
  if (!box) return;
  const statusUrl = box.dataset.statusUrl;
  const ticketUrl = box.dataset.ticketUrl;

  async function check() {
    if (document.hidden) return; // no requests from a tab nobody is looking at
    try {
      const res = await fetch(statusUrl, { headers: { 'Accept': 'application/json' }, cache: 'no-store' });
      if (!res.ok) return;
      const data = await res.json();
      if (data.status === 'PAID') {
        window.location.href = ticketUrl;
      } else if (data.status !== 'PENDING') {
        window.location.reload();
      }
    } catch (e) {
      // Network hiccup: try again on the next tick.
    }
  }

  setInterval(check, 3000);
  document.addEventListener('visibilitychange', check);
})();
