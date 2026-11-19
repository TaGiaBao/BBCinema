// Drag-and-drop seat layout editor for admins (admin/cinemas/room.html).
// The layout is edited in the browser and saved in one request: POST {saveUrl} with every seat
// as {id, row, number, type, active, x, y}. The server checks it again (RoomService.saveLayout).
(function () {
  'use strict';

  const root = document.getElementById('layoutEditor');
  if (!root) {
    return;
  }

  const MAX_COLS = 40;
  const MAX_ROWS = 30;
  const MARGIN = 2;          // empty columns/rows kept around the seats so there is room to drag
  const TYPE_LABEL = { STANDARD: 'Thường', VIP: 'VIP', COUPLE: 'Đôi' };

  const board = root.querySelector('[data-role="board"]');
  const grid = root.querySelector('[data-role="grid"]');
  const labels = root.querySelector('[data-role="labels"]');
  const statusEl = root.querySelector('[data-role="status"]');
  const summaryEl = root.querySelector('[data-role="summary"]');
  const addType = root.querySelector('[data-role="add-type"]');
  const selectionTools = root.querySelectorAll('[data-role="selection-tools"] button');

  let seats = [];            // {key, id, row, number, type, active, x, y}
  let used = new Set();      // seat ids that some showtime uses: cannot be deleted
  let locked = new Set();    // seat ids held or sold for an upcoming showtime: cannot be renamed
  let selected = new Set();  // seat keys
  let history = [];
  let dirty = false;
  let nextKey = 1;
  let cols = 12;
  let rows = 10;

  // ------------------------------------------------------------------ helpers

  const width = (s) => (s.type === 'COUPLE' ? 2 : 1);
  const label = (s) => s.row + s.number;

  function setStatus(text, kind) {
    statusEl.textContent = text || '';
    statusEl.className = 'small mb-2 ' + (kind === 'error' ? 'text-danger' : kind === 'ok' ? 'text-success' : 'text-muted');
  }

  function load(layout) {
    seats = layout.seats.map((s) => ({ key: 'k' + (nextKey++), id: s.id, row: s.row, number: s.number,
      type: s.type, active: s.active, x: s.x, y: s.y }));
    used = new Set(layout.usedSeatIds);
    locked = new Set(layout.lockedSeatIds);
    selected.clear();
    history = [];
    dirty = false;
    render();
  }

  function snapshot() {
    history.push(JSON.stringify(seats));
    if (history.length > 100) {
      history.shift();
    }
    dirty = true;
  }

  function undo() {
    if (!history.length) {
      setStatus('Không còn gì để hoàn tác.');
      return;
    }
    seats = JSON.parse(history.pop());
    const keys = new Set(seats.map((s) => s.key));
    selected = new Set([...selected].filter((k) => keys.has(k)));
    dirty = history.length > 0;
    setStatus('Đã hoàn tác.');
    render();
  }

  /** Cells taken by seats, except the ones in `ignore` (keys). */
  function occupied(ignore) {
    const cells = new Map();
    seats.forEach((s) => {
      if (ignore && ignore.has(s.key)) {
        return;
      }
      for (let dx = 0; dx < width(s); dx++) {
        cells.set((s.x + dx) + ':' + s.y, s);
      }
    });
    return cells;
  }

  /** Checks that the given seats, at their proposed spots, fit and do not overlap the others. */
  function fits(moved, ignore) {
    const cells = occupied(ignore);
    const mine = new Set();
    for (const s of moved) {
      if (s.x < 0 || s.y < 0 || s.x + width(s) > MAX_COLS || s.y >= MAX_ROWS) {
        return 'Ghế ' + label(s) + ' sẽ nằm ngoài sơ đồ.';
      }
      for (let dx = 0; dx < width(s); dx++) {
        const cell = (s.x + dx) + ':' + s.y;
        if (cells.has(cell)) {
          return 'Ghế ' + label(s) + ' sẽ chồng lên ghế ' + label(cells.get(cell)) + '.';
        }
        if (mine.has(cell)) {
          return 'Các ghế đang chọn sẽ chồng lên nhau.';
        }
        mine.add(cell);
      }
    }
    return null;
  }

  function selectedSeats() {
    return seats.filter((s) => selected.has(s.key));
  }

  function lineLabel(y) {
    const s = seats.find((seat) => seat.y === y);
    return s ? s.row : '';
  }

  /** Next row letter not used yet (A, B, ... Z, AA, AB ...). */
  function unusedRowLetter() {
    const taken = new Set(seats.map((s) => s.row));
    for (let i = 0; i < 26 * 27; i++) {
      const name = i < 26 ? String.fromCharCode(65 + i)
        : String.fromCharCode(64 + Math.floor(i / 26)) + String.fromCharCode(65 + (i % 26));
      if (!taken.has(name)) {
        return name;
      }
    }
    return 'Z';
  }

  // ------------------------------------------------------------------ drawing

  function render() {
    const maxX = seats.reduce((m, s) => Math.max(m, s.x + width(s)), 0);
    const maxY = seats.reduce((m, s) => Math.max(m, s.y + 1), 0);
    cols = Math.min(MAX_COLS, Math.max(12, maxX + MARGIN));
    rows = Math.min(MAX_ROWS, Math.max(10, maxY + MARGIN));
    grid.style.gridTemplateColumns = 'repeat(' + cols + ', var(--cell))';
    grid.style.gridTemplateRows = 'repeat(' + rows + ', var(--cell-h))';
    labels.style.gridTemplateRows = 'repeat(' + rows + ', var(--cell-h))';

    const parts = [];
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < cols; x++) {
        parts.push('<div class="layout-cell" data-x="' + x + '" data-y="' + y + '" style="grid-column:' + (x + 1)
          + ';grid-row:' + (y + 1) + '"></div>');
      }
    }
    seats.forEach((s) => {
      const classes = ['seat', s.type];
      if (!s.active) {
        classes.push('inactive-seat');
      }
      if (selected.has(s.key)) {
        classes.push('selected-edit');
      }
      const lock = s.id != null && locked.has(s.id);
      const title = 'Ghế ' + label(s) + ' · ' + TYPE_LABEL[s.type] + (s.active ? '' : ' · lối đi, không bán')
        + (lock ? ' · có khách, không đổi tên được' : '');
      parts.push('<button type="button" class="' + classes.join(' ') + '" data-key="' + s.key + '" style="grid-column:'
        + (s.x + 1) + ' / span ' + width(s) + ';grid-row:' + (s.y + 1) + '" title="' + title + '" aria-label="' + title
        + '" aria-pressed="' + selected.has(s.key) + '">' + s.number + (lock ? '<i class="bi bi-lock-fill"></i>' : '')
        + '</button>');
    });
    grid.innerHTML = parts.join('');

    const labelParts = [];
    for (let y = 0; y < rows; y++) {
      const name = lineLabel(y);
      labelParts.push(name
        ? '<button type="button" class="layout-row-label" data-line="' + y + '" title="Chọn cả hàng ' + name + '">' + name + '</button>'
        : '<span></span>');
    }
    labels.innerHTML = labelParts.join('');

    const sellable = seats.filter((s) => s.active);
    summaryEl.textContent = sellable.length + ' ghế bán được: '
      + sellable.filter((s) => s.type === 'STANDARD').length + ' thường, '
      + sellable.filter((s) => s.type === 'VIP').length + ' VIP, '
      + sellable.filter((s) => s.type === 'COUPLE').length + ' đôi'
      + (dirty ? ' · chưa lưu' : '');
    selectionTools.forEach((b) => { b.disabled = selected.size === 0; });
  }

  // ------------------------------------------------------------------ editing actions

  function moveSelection(dx, dy) {
    const chosen = selectedSeats();
    if (!chosen.length || (dx === 0 && dy === 0)) {
      return false;
    }
    const moved = chosen.map((s) => Object.assign({}, s, { x: s.x + dx, y: s.y + dy }));
    const problem = fits(moved, selected);
    if (problem) {
      setStatus(problem, 'error');
      return false;
    }
    snapshot();
    chosen.forEach((s) => { s.x += dx; s.y += dy; });
    setStatus('');
    render();
    return true;
  }

  function setType(type) {
    const chosen = selectedSeats();
    const changed = chosen.map((s) => Object.assign({}, s, { type: type }));
    const problem = fits(changed, selected);
    if (problem) {
      setStatus(type === 'COUPLE' ? 'Ghế đôi rộng 2 ô: cần chỗ trống bên phải. ' + problem : problem, 'error');
      return;
    }
    snapshot();
    chosen.forEach((s) => { s.type = type; });
    setStatus('Đã đổi ' + chosen.length + ' ghế thành ghế ' + TYPE_LABEL[type].toLowerCase() + '.', 'ok');
    render();
  }

  function toggleAisle() {
    const chosen = selectedSeats();
    const makeActive = chosen.every((s) => !s.active);
    snapshot();
    chosen.forEach((s) => { s.active = makeActive; });
    setStatus(makeActive ? 'Đã mở bán lại ' + chosen.length + ' ghế.' : 'Đã chuyển ' + chosen.length + ' ghế thành lối đi.', 'ok');
    render();
  }

  function deleteSelection() {
    const chosen = selectedSeats();
    if (!chosen.length) {
      return;
    }
    const keep = chosen.filter((s) => s.id != null && used.has(s.id));
    snapshot();
    seats = seats.filter((s) => !selected.has(s.key) || keep.includes(s));
    keep.forEach((s) => { s.active = false; });
    selected = new Set(keep.map((s) => s.key));
    setStatus(keep.length
      ? 'Ghế ' + keep.map(label).join(', ') + ' đã có suất chiếu nên không xoá hẳn được; đã chuyển thành lối đi.'
      : 'Đã xoá ' + chosen.length + ' ghế.', keep.length ? 'error' : 'ok');
    render();
  }

  /** Renames the chosen seats: one row letter, numbered left to right (by line, then column). */
  function renameRow() {
    const chosen = selectedSeats().sort((a, b) => a.y - b.y || a.x - b.x);
    if (!chosen.length) {
      return;
    }
    const lockedOnes = chosen.filter((s) => s.id != null && locked.has(s.id));
    if (lockedOnes.length) {
      setStatus('Ghế ' + lockedOnes.map(label).join(', ') + ' đã có khách cho suất sắp chiếu, không đổi tên được.', 'error');
      return;
    }
    const answer = window.prompt('Tên hàng cho ' + chosen.length + ' ghế đang chọn (1-2 chữ cái, ví dụ: K):', chosen[0].row);
    if (answer == null) {
      return;
    }
    const row = answer.trim().toUpperCase();
    if (!/^[A-Z]{1,2}$/.test(row)) {
      setStatus('Tên hàng chỉ gồm 1-2 chữ cái (A-Z).', 'error');
      return;
    }
    const startAt = window.prompt('Bắt đầu đánh số từ:', '1');
    const first = Number.parseInt(startAt, 10);
    if (!Number.isInteger(first) || first < 1 || first + chosen.length - 1 > 99) {
      setStatus('Số ghế phải từ 1 đến 99.', 'error');
      return;
    }
    snapshot();
    chosen.forEach((s, i) => { s.row = row; s.number = first + i; });
    setStatus('Đã đặt tên hàng ' + row + ' cho ' + chosen.length + ' ghế. Bấm Lưu để kiểm tra trùng tên.', 'ok');
    render();
  }

  /** Letters A, B, C... from the screen backwards, seats numbered from the left. */
  function renumberAll() {
    const lines = [...new Set(seats.map((s) => s.y))].sort((a, b) => a - b);
    const planned = new Map();
    lines.forEach((y, i) => {
      const row = i < 26 ? String.fromCharCode(65 + i) : 'A' + String.fromCharCode(65 + i - 26);
      seats.filter((s) => s.y === y).sort((a, b) => a.x - b.x)
        .forEach((s, n) => planned.set(s.key, { row: row, number: n + 1 }));
    });
    const blocked = seats.filter((s) => s.id != null && locked.has(s.id)
      && (planned.get(s.key).row !== s.row || planned.get(s.key).number !== s.number));
    if (blocked.length) {
      setStatus('Không đánh số lại được: ghế ' + blocked.slice(0, 6).map(label).join(', ')
        + (blocked.length > 6 ? '…' : '') + ' đã có khách cho suất sắp chiếu.', 'error');
      return;
    }
    if (!window.confirm('Đặt lại tên toàn bộ ghế: hàng A, B, C... tính từ màn hình, số ghế từ trái sang phải?')) {
      return;
    }
    snapshot();
    seats.forEach((s) => Object.assign(s, planned.get(s.key)));
    setStatus('Đã đánh số lại ' + seats.length + ' ghế.', 'ok');
    render();
  }

  function addSeatAt(x, y) {
    const type = addType.value;
    const row = lineLabel(y) || unusedRowLetter();
    const number = seats.filter((s) => s.row === row).reduce((m, s) => Math.max(m, s.number), 0) + 1;
    const seat = { key: 'k' + (nextKey++), id: null, row: row, number: number, type: type, active: true, x: x, y: y };
    const problem = number > 99 ? 'Hàng ' + row + ' đã đủ 99 ghế.' : fits([seat], null);
    if (problem) {
      setStatus(problem, 'error');
      return;
    }
    snapshot();
    seats.push(seat);
    selected = new Set([seat.key]);
    setStatus('Đã thêm ghế ' + label(seat) + '.', 'ok');
    render();
  }

  async function save() {
    const body = seats.map((s) => ({ id: s.id, row: s.row, number: s.number, type: s.type, active: s.active, x: s.x, y: s.y }));
    setStatus('Đang lưu...');
    try {
      const res = await fetch(root.dataset.saveUrl, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json', Accept: 'application/json' }, window.bbCsrf()),
        body: JSON.stringify(body)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setStatus(data.message || 'Không lưu được (lỗi ' + res.status + ').', 'error');
        return;
      }
      root.dataset.layout = JSON.stringify(data.layout);
      load(data.layout);
      setStatus(data.message || 'Đã lưu sơ đồ ghế.', 'ok');
    } catch (e) {
      setStatus('Không kết nối được máy chủ, sơ đồ chưa được lưu.', 'error');
    }
  }

  // ------------------------------------------------------------------ mouse, touch, keyboard

  const mode = () => root.querySelector('input[name="mode"]:checked').value;
  let drag = null;

  function cellAt(clientX, clientY) {
    const box = grid.getBoundingClientRect();
    const style = getComputedStyle(grid);
    const cell = parseFloat(style.getPropertyValue('--cell')) || 32;
    const cellH = parseFloat(style.getPropertyValue('--cell-h')) || 30;
    const gap = parseFloat(style.columnGap) || 6;
    return {
      x: Math.floor((clientX - box.left) / (cell + gap)),
      y: Math.floor((clientY - box.top) / (cellH + gap))
    };
  }

  grid.addEventListener('pointerdown', (e) => {
    const seatEl = e.target.closest('.seat[data-key]');
    if (!seatEl || mode() !== 'select' || e.button !== 0) {
      return;
    }
    const key = seatEl.dataset.key;
    if (e.ctrlKey || e.metaKey || e.shiftKey) {
      if (selected.has(key)) {
        selected.delete(key);
      } else {
        selected.add(key);
      }
      render();
      return;
    }
    if (!selected.has(key)) {
      selected = new Set([key]);
      render();
    }
    drag = { start: cellAt(e.clientX, e.clientY), dx: 0, dy: 0, pointer: e.pointerId };
    grid.setPointerCapture(e.pointerId);
    e.preventDefault();
  });

  grid.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.pointer) {
      return;
    }
    const now = cellAt(e.clientX, e.clientY);
    const dx = now.x - drag.start.x;
    const dy = now.y - drag.start.y;
    if (dx === drag.dx && dy === drag.dy) {
      return;
    }
    drag.dx = dx;
    drag.dy = dy;
    const moved = selectedSeats().map((s) => Object.assign({}, s, { x: s.x + dx, y: s.y + dy }));
    const ok = !fits(moved, selected);
    grid.querySelectorAll('.seat.selected-edit').forEach((el) => {
      const s = seats.find((seat) => seat.key === el.dataset.key);
      el.style.gridColumn = (s.x + dx + 1) + ' / span ' + width(s);
      el.style.gridRow = String(s.y + dy + 1);
      el.classList.toggle('drop-blocked', !ok);
    });
  });

  function endDrag(e) {
    if (!drag || e.pointerId !== drag.pointer) {
      return;
    }
    const { dx, dy } = drag;
    drag = null;
    if (!moveSelection(dx, dy)) {
      render();
    }
  }

  grid.addEventListener('pointerup', endDrag);
  grid.addEventListener('pointercancel', endDrag);

  grid.addEventListener('click', (e) => {
    const cell = e.target.closest('.layout-cell');
    if (!cell) {
      return;
    }
    if (mode() === 'add') {
      addSeatAt(Number(cell.dataset.x), Number(cell.dataset.y));
    } else if (selected.size) {
      selected.clear();
      render();
    }
  });

  labels.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-line]');
    if (!btn) {
      return;
    }
    const y = Number(btn.dataset.line);
    const line = seats.filter((s) => s.y === y).map((s) => s.key);
    selected = (e.ctrlKey || e.metaKey || e.shiftKey) ? new Set([...selected, ...line]) : new Set(line);
    render();
    board.focus();
  });

  board.addEventListener('keydown', (e) => {
    const arrows = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (arrows[e.key] && selected.size) {
      e.preventDefault();
      moveSelection(arrows[e.key][0], arrows[e.key][1]);
    } else if ((e.key === 'Delete' || e.key === 'Backspace') && selected.size) {
      e.preventDefault();
      deleteSelection();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
      e.preventDefault();
      undo();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
      e.preventDefault();
      selected = new Set(seats.map((s) => s.key));
      render();
    } else if (e.key === 'Escape') {
      selected.clear();
      render();
    }
  });

  root.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-action]');
    if (!btn) {
      return;
    }
    switch (btn.dataset.action) {
      case 'type': setType(btn.dataset.type); break;
      case 'aisle': toggleAisle(); break;
      case 'rename': renameRow(); break;
      case 'delete': deleteSelection(); break;
      case 'renumber': renumberAll(); break;
      case 'undo': undo(); break;
      case 'save': save(); break;
      case 'reset':
        if (!dirty || window.confirm('Bỏ mọi thay đổi chưa lưu?')) {
          load(JSON.parse(root.dataset.layout));
          setStatus('Đã quay về sơ đồ đã lưu.');
        }
        break;
      default:
    }
  });

  root.querySelectorAll('input[name="mode"]').forEach((input) => input.addEventListener('change', () => {
    root.classList.toggle('adding', mode() === 'add');
    setStatus(mode() === 'add' ? 'Bấm vào ô trống để thêm ghế ' + TYPE_LABEL[addType.value].toLowerCase() + '.' : '');
  }));

  window.addEventListener('beforeunload', (e) => {
    if (dirty) {
      e.preventDefault();
      e.returnValue = '';
    }
  });

  load(JSON.parse(root.dataset.layout));
})();
