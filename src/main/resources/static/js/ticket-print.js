// Staff ticket page: "In vé" logs the print on the server, then opens the browser's print dialog.
// The ticket is printed even when logging fails (no network, session expired...).
(function () {
  'use strict';
  const button = document.querySelector('[data-role="print-ticket"]');
  if (!button) {
    return;
  }
  const log = document.querySelector('[data-role="print-log"]');
  let busy = false;

  button.addEventListener('click', function () {
    if (busy) {
      return; // a double click is one print
    }
    busy = true;
    button.disabled = true;
    const headers = Object.assign({ Accept: 'application/json' }, window.bbCsrf ? window.bbCsrf() : {});
    fetch(button.dataset.printUrl, { method: 'POST', headers: headers, credentials: 'same-origin' })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (data) {
        if (data && log) {
          log.textContent = 'Đã in ' + data.printCount + ' lần · lần cuối ' + data.lastPrintedAt;
          log.classList.remove('d-none');
        }
      })
      .catch(function () { /* print anyway */ })
      .finally(function () {
        busy = false;
        button.disabled = false;
        window.print();
      });
  });
})();
