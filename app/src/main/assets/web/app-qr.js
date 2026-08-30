// Open Alpha2 — client logic (app-qr.js)
// 2026-08 v2 新增: 離線 QR code 產生器 — 機械人冇 internet, 唔可以 CDN,
// 所以自帶一個精簡 QR encoder。固定 version 2 / EC level L / byte mode,
// 容量 32 bytes — 機械人 UUID (17 字元) 綽綽有餘。
// 演算法跟 QR standard (ISO/IEC 18004): Reed-Solomon EC over GF(256),
// mask 0-7 全試 + penalty 評分揀最好。

function qrGfMul(a, b) {
  let r = 0;
  while (b) {
    if (b & 1) r ^= a;
    a <<= 1;
    if (a & 0x100) a ^= 0x11d;
    b >>= 1;
  }
  return r;
}

function qrGfPow(base, p) {
  let r = 1;
  for (let k = 0; k < p; k++) r = qrGfMul(r, base);
  return r;
}

/** RS generator polynomial (descending coeffs), degree n, roots a^0..a^(n-1). */
function qrRsGenerator(n) {
  let gen = [1];
  for (let i = 0; i < n; i++) {
    const factor = qrGfPow(2, i);
    const ng = new Array(gen.length + 1).fill(0);
    for (let j = 0; j < gen.length; j++) {
      ng[j] ^= gen[j];
      ng[j + 1] ^= qrGfMul(gen[j], factor);
    }
    gen = ng;
  }
  return gen;
}

/** data codewords + eccLen → data.concat(ecc) */
function qrRsAppendEcc(data, eccLen) {
  const gen = qrRsGenerator(eccLen);
  const msg = data.slice().concat(new Array(eccLen).fill(0));
  for (let i = 0; i < data.length; i++) {
    const coef = msg[i];
    if (coef !== 0) {
      for (let j = 0; j < gen.length; j++) {
        msg[i + j] ^= qrGfMul(gen[j], coef);
      }
    }
  }
  return data.concat(msg.slice(data.length));
}

/** Mask predicate functions (row r, col c). */
const QR_MASKS = [
  (r, c) => (r + c) % 2 === 0,
  (r) => r % 2 === 0,
  (c) => c % 3 === 0,
  (r, c) => (r + c) % 3 === 0,
  (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
  (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
  (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
  (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
];

/** Build the raw (unmasked) function-pattern + reserved map for version 2 (25x25). */
function qrBuildBase(size) {
  const m = Array.from({ length: size }, () => new Array(size).fill(null));

  function setFinder(r0, c0) {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const r = r0 + dr, c = c0 + dc;
        if (r < 0 || r >= size || c < 0 || c >= size) continue;
        const dark =
          (dr >= 0 && dr <= 6 && (dc === 0 || dc === 6)) ||
          (dc >= 0 && dc <= 6 && (dr === 0 || dr === 6)) ||
          (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4);
        m[r][c] = dark;
      }
    }
  }
  setFinder(0, 0);
  setFinder(0, size - 7);
  setFinder(size - 7, 0);

  for (let i = 8; i < size - 8; i++) {
    m[6][i] = i % 2 === 0;
    m[i][6] = i % 2 === 0;
  }

  // alignment pattern, version 2 → center (18,18)
  for (let dr = -2; dr <= 2; dr++) {
    for (let dc = -2; dc <= 2; dc++) {
      m[18 + dr][18 + dc] = Math.max(Math.abs(dr), Math.abs(dc)) !== 1;
    }
  }

  // reserve format info cells (set to false placeholder, overwritten later)
  for (let i = 0; i <= 8; i++) {
    if (i !== 6) m[i][8] = false;
    if (i !== 6) m[8][i] = false;
  }
  for (let i = 0; i < 8; i++) {
    m[size - 1 - i][8] = false;
    m[8][size - 1 - i] = false;
  }

  m[size - 8][8] = true; // dark module
  return m;
}

/** BCH(15,5) format bits: (ecLevelBits<<3 | mask) → 15-bit sequence. */
function qrFormatBits(mask) {
  const data = (1 << 3) | mask; // EC level L = 0b01
  let rem = data;
  for (let i = 0; i < 10; i++) {
    rem = (rem << 1) ^ ((rem >> 9) * 0x537);
  }
  return ((data << 10) | rem) ^ 0x5412;
}

/** Penalty score (4 rules, ISO 18004 §8.8.2). Lower = better. */
function qrPenalty(m) {
  const size = m.length;
  let pen = 0;
  // N1: runs of same colour >= 5 (rows + cols)
  for (let axis = 0; axis < 2; axis++) {
    for (let i = 0; i < size; i++) {
      let run = 1;
      let prev = null;
      for (let j = 0; j < size; j++) {
        const v = axis === 0 ? m[i][j] : m[j][i];
        if (v === prev) {
          run++;
          if (j === size - 1 && run >= 5) pen += 3 + (run - 5);
        } else {
          if (prev !== null && run >= 5) pen += 3 + (run - 5);
          prev = v;
          run = 1;
        }
      }
    }
  }
  // N2: 2x2 blocks of same colour
  for (let r = 0; r < size - 1; r++) {
    for (let c = 0; c < size - 1; c++) {
      const v = m[r][c];
      if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) pen += 3;
    }
  }
  // N3: finder-like pattern 1011101 with 4 light on either side
  const pat1 = [true, false, true, true, true, false, true, false, false, false, false];
  const pat2 = pat1.slice().reverse();
  function matchAt(get, i, pat) {
    for (let k = 0; k < pat.length; k++) {
      if (get(i + k) !== pat[k]) return false;
    }
    return true;
  }
  for (let axis = 0; axis < 2; axis++) {
    for (let i = 0; i < size; i++) {
      for (let j = 0; j <= size - 11; j++) {
        const get = axis === 0 ? (k) => m[i][j + k] : (k) => m[j + k][i];
        if (matchAt(get, 0, pat1) || matchAt(get, 0, pat2)) pen += 40;
      }
    }
  }
  // N4: dark ratio
  let dark = 0;
  for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) if (m[r][c]) dark++;
  const pct = (dark * 100) / (size * size);
  pen += Math.floor(Math.abs(pct - 50) / 5) * 10;
  return pen;
}

/**
 * 產生 QR matrix (boolean[][]) for the given ASCII text.
 * 固定 version 2 (25x25), EC level L, byte mode — 最多 32 bytes。
 */
function qrEncode(text) {
  const bytes = [];
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    bytes.push(c > 255 ? 0x3f : c); // 非 ASCII → '?'
  }
  if (bytes.length > 32) throw new Error("too long for QR v2-L (max 32 bytes)");

  const size = 25;
  const dataCodewords = 34;
  const eccLen = 10;

  // bit stream
  const bits = [];
  const push = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >> i) & 1);
  };
  push(0b0100, 4);
  push(bytes.length, 8);
  bytes.forEach((b) => push(b, 8));
  push(0, Math.min(4, dataCodewords * 8 - bits.length));
  while (bits.length % 8 !== 0) bits.push(0);
  let data = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    data.push(b);
  }
  const pads = [0xec, 0x11];
  for (let i = 0; data.length < dataCodewords; i++) data.push(pads[i % 2]);
  const all = qrRsAppendEcc(data, eccLen);

  let best = null;
  let bestPen = Infinity;
  let bestMask = 0;
  for (let mask = 0; mask < 8; mask++) {
    const m = qrBuildBase(size);
    // place data zigzag
    let bitIdx = 0;
    const totalBits = all.length * 8;
    let upward = true;
    for (let right = size - 1; right >= 1; right -= 2) {
      let cRight = right;
      if (cRight === 6) cRight = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const c = cRight - j;
          const r = upward ? size - 1 - vert : vert;
          if (m[r][c] === null) {
            let dark = false;
            if (bitIdx < totalBits) {
              dark = ((all[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1) !== 0;
            }
            if (QR_MASKS[mask](r, c)) dark = !dark;
            m[r][c] = dark;
            bitIdx++;
          }
        }
      }
      upward = !upward;
    }
    // format info (two copies)
    const fb = qrFormatBits(mask);
    const bit = (i) => ((fb >> i) & 1) !== 0;
    for (let i = 0; i <= 5; i++) m[i][8] = bit(i);
    m[7][8] = bit(6);
    m[8][8] = bit(7);
    m[8][7] = bit(8);
    for (let i = 9; i < 15; i++) m[8][14 - i] = bit(i);
    for (let i = 0; i < 8; i++) m[size - 1 - i][8] = bit(i);
    for (let i = 8; i < 15; i++) m[size - 15 + i][8] = bit(i);

    const pen = qrPenalty(m);
    if (pen < bestPen) {
      bestPen = pen;
      best = m;
      bestMask = mask;
    }
  }
  return { matrix: best, mask: bestMask, size: size };
}

/** 畫 QR 落 canvas — quiet zone 4 modules。 */
function qrDrawToCanvas(canvas, text, darkColor) {
  const q = qrEncode(text);
  const quiet = 4;
  const n = q.size + quiet * 2;
  const cell = Math.floor(canvas.width / n);
  const offset = Math.floor((canvas.width - cell * n) / 2);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = darkColor || "#000000";
  for (let r = 0; r < q.size; r++) {
    for (let c = 0; c < q.size; c++) {
      if (q.matrix[r][c]) {
        ctx.fillRect(offset + c * cell, offset + r * cell, cell, cell);
      }
    }
  }
}
