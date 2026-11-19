// Map of all cinemas on /cinemas (Leaflet + OpenStreetMap tiles). Clicking "Xem trên bản đồ"
// on a cinema card (or a marker) highlights that cinema on both the map and the list.
(function () {
  'use strict';
  const mapEl = document.getElementById('cinemaMap');
  if (!mapEl || !window.L) {
    return;
  }
  const cards = Array.from(document.querySelectorAll('.cinema-card[data-lat][data-lng]'));
  if (!cards.length) {
    mapEl.innerHTML = '<p class="text-muted p-3 mb-0">Các rạp chưa có toạ độ trên bản đồ.</p>';
    return;
  }

  const map = L.map(mapEl, { scrollWheelZoom: false });
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);
  // Leaflet looks for its marker images next to the CSS; point it at the webjar folder.
  L.Icon.Default.imagePath = '/webjars/leaflet/1.9.4/dist/images/';

  const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const markers = new Map();
  const bounds = [];

  function select(id) {
    cards.forEach((card) => card.classList.toggle('active', card.dataset.cinemaId === id));
    const marker = markers.get(id);
    if (marker) {
      map.setView(marker.getLatLng(), 16, { animate: true });
      marker.openPopup();
    }
  }

  cards.forEach((card) => {
    const latLng = [Number(card.dataset.lat), Number(card.dataset.lng)];
    const marker = L.marker(latLng, { title: card.dataset.name, alt: card.dataset.name })
      .addTo(map)
      .bindPopup('<strong>' + escapeHtml(card.dataset.name) + '</strong><br>' + escapeHtml(card.dataset.address));
    marker.on('click', () => select(card.dataset.cinemaId));
    markers.set(card.dataset.cinemaId, marker);
    bounds.push(latLng);
    const button = card.querySelector('[data-role="show-on-map"]');
    if (button) {
      button.addEventListener('click', () => {
        select(card.dataset.cinemaId);
        if (window.innerWidth < 992) {
          mapEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      });
    }
  });

  if (bounds.length === 1) {
    map.setView(bounds[0], 15);
  } else {
    map.fitBounds(bounds, { padding: [40, 40] });
  }
  // Start on the cinema the visitor has chosen, if it has a position.
  const current = document.body.dataset.cinemaId;
  if (current && markers.has(current)) {
    select(current);
  }
})();
