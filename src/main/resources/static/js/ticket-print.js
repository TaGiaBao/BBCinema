// Staff ticket page: "In vé" logs the print on the server, then opens the browser's print dialog.
// The ticket is printed exactly once per click, even when logging fails (closed shift, expired session,
// no network...); the failure is then shown next to the button so staff know the print was not counted.
(function () {
  'use strict';
  const button = document.querySelector('[data-role="print-ticket"]');
  if (!button) {
    return;
  }
  const log = document.querySelector('[data-role="print-log"]');
  const warning = document.querySelector('[data-role="print-warning"]');
  const DEFAULT_WARNING = 'Không ghi nhận được lần in này (hết ca hoặc phiên đăng nhập). Vé vẫn được in.';
  const TIMEOUT_MS = 3000;
  let busy = false;

  function showWarning(text) {
    if (warning) {
      warning.textContent = text || DEFAULT_WARNING;
      warning.classList.remove('d-none');
    }
  }

  function isJson(response) {
    return (response.headers.get('Content-Type') || '').indexOf('application/json') !== -1;
  }

  button.addEventListener('click', function () {
    if (busy) {
      return; // a double click is one print
    }
    busy = true;
    button.disabled = true;
    if (warning) {
      warning.classList.add('d-none');
    }
    const controller = new AbortController();
    const timer = setTimeout(function () { controller.abort(); }, TIMEOUT_MS);
    const headers = Object.assign({ Accept: 'application/json' }, window.bbCsrf ? window.bbCsrf() : {});

    // redirect: 'manual' turns a redirect (closed shift, login page) into a failure instead of a 200 HTML page.
    fetch(button.dataset.printUrl, {
      method: 'POST', headers: headers, credentials: 'same-origin', redirect: 'manual', signal: controller.signal
    })
      .then(function (response) {
        if (response.ok && isJson(response)) {
          return response.json().then(function (data) {
            if (log) {
              log.textContent = 'Đã in ' + data.printCount + ' lần · lần cuối ' + data.lastPrintedAt;
              log.classList.remove('d-none');
            }
          });
        }
        if (response.status === 409 && isJson(response)) {
          return response.json().then(function (data) { showWarning(data && data.message); });
        }
        showWarning();
      })
      .catch(function () { showWarning(); })
      .finally(function () {
        clearTimeout(timer);
        busy = false;
        button.disabled = false;
        window.print();
      });
  });
})();
