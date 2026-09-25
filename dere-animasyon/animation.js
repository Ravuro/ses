// Bir kurbanın derenin bir ucundan diğer ucuna yüzüşü — kuşbakışı canvas animasyonu.
// Her kare yalnızca zamana (t) bağlıdır; bu sayede render.js kareleri tek tek alıp MP4'e çevirebilir.
(function () {
  'use strict';

  const W = 1280, H = 720, FPS = 30, DURATION = 14;
  const TOTAL_FRAMES = FPS * DURATION;
  const T_START = 1.2, T_END = 12.4;   // yüzüşün başladığı / bittiği an (sn)
  const X_START = 105, X_END = 1172;    // yüzücünün başlangıç / varış x konumu
  const ROPE_START = 32, ROPE_END = 1238;
  const CREEK_METERS = 45;             // derenin temsil ettiği uzunluk
  const SCALE = 1.45;                  // yüzücü boyutu

  const canvas = document.getElementById('scene');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');

  // ---------- yardımcılar ----------
  function rng(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (t) => t * t * (3 - 2 * t);
  const TAU = Math.PI * 2;

  // ---------- dere geometrisi ----------
  const centerY = (x) => 360 + 58 * Math.sin(x * 0.0055 + 0.6) + 20 * Math.sin(x * 0.016 + 2.0);
  const halfW = (x) => 90 + 16 * Math.sin(x * 0.009 + 1.3);
  const slope = (x) => (centerY(x + 1) - centerY(x - 1)) / 2;

  function creekPath(c, offset) {
    c.beginPath();
    for (let x = -30; x <= W + 30; x += 6) c.lineTo(x, centerY(x) - halfW(x) - offset);
    for (let x = W + 30; x >= -30; x -= 6) c.lineTo(x, centerY(x) + halfW(x) + offset);
    c.closePath();
  }

  function edgeLine(c, side, offset) {
    c.beginPath();
    for (let x = -30; x <= W + 30; x += 6) c.lineTo(x, centerY(x) + side * (halfW(x) + offset));
  }

  // ---------- statik arka plan (bir kez çizilir) ----------
  const bg = document.createElement('canvas');
  bg.width = W;
  bg.height = H;

  function drawRock(c, x, y, rx, ry, rot) {
    c.save();
    c.translate(x, y);
    c.rotate(rot);
    c.fillStyle = 'rgba(0,0,0,0.28)';
    c.beginPath(); c.ellipse(3, 3, rx, ry, 0, 0, TAU); c.fill();
    const g = c.createRadialGradient(-rx * 0.35, -ry * 0.4, 1, 0, 0, rx);
    g.addColorStop(0, '#b9b4a8');
    g.addColorStop(1, '#6d6a62');
    c.fillStyle = g;
    c.beginPath(); c.ellipse(0, 0, rx, ry, 0, 0, TAU); c.fill();
    c.restore();
  }

  function drawTree(c, x, y, R, r) {
    c.fillStyle = 'rgba(15,35,10,0.35)';
    c.beginPath(); c.ellipse(x + R * 0.35, y + R * 0.3, R * 1.05, R * 0.95, 0, 0, TAU); c.fill();
    const blobs = 7;
    for (let i = 0; i < blobs; i++) {
      const a = (i / blobs) * TAU + r() * 0.5;
      const d = R * 0.45;
      const bx = x + Math.cos(a) * d, by = y + Math.sin(a) * d;
      const br = R * (0.5 + r() * 0.2);
      const g = c.createRadialGradient(bx - br * 0.3, by - br * 0.3, 1, bx, by, br);
      g.addColorStop(0, '#6fae45');
      g.addColorStop(1, '#2d5c1d');
      c.fillStyle = g;
      c.beginPath(); c.arc(bx, by, br, 0, TAU); c.fill();
    }
    const g = c.createRadialGradient(x - R * 0.3, y - R * 0.3, 1, x, y, R * 0.7);
    g.addColorStop(0, '#80c050');
    g.addColorStop(1, '#3b7326');
    c.fillStyle = g;
    c.beginPath(); c.arc(x, y, R * 0.62, 0, TAU); c.fill();
  }

  (function buildBackground() {
    const c = bg.getContext('2d');
    const r = rng(7);

    // çimen
    const g = c.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, '#6a9a3f');
    g.addColorStop(1, '#4b7c2d');
    c.fillStyle = g;
    c.fillRect(0, 0, W, H);
    for (let i = 0; i < 70; i++) {
      const x = r() * W, y = r() * H, rad = 40 + r() * 130;
      const pg = c.createRadialGradient(x, y, 0, x, y, rad);
      pg.addColorStop(0, r() < 0.5 ? 'rgba(40,70,20,0.25)' : 'rgba(160,195,85,0.18)');
      pg.addColorStop(1, 'rgba(0,0,0,0)');
      c.fillStyle = pg;
      c.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    }
    c.lineWidth = 1;
    for (let i = 0; i < 16000; i++) {
      const x = r() * W, y = r() * H;
      const h = 3 + r() * 6, a = -Math.PI / 2 + (r() - 0.5) * 0.9;
      c.strokeStyle = `hsla(${80 + r() * 35},${38 + r() * 25}%,${30 + r() * 32}%,0.55)`;
      c.beginPath(); c.moveTo(x, y); c.lineTo(x + Math.cos(a) * h, y + Math.sin(a) * h); c.stroke();
    }
    for (let i = 0; i < 120; i++) {
      const x = r() * W, y = r() * H;
      if (Math.abs(y - centerY(x)) < halfW(x) + 30) continue;
      c.fillStyle = ['#f4e04d', '#ffffff', '#e98ad6'][Math.floor(r() * 3)];
      c.beginPath(); c.arc(x, y, 1.6, 0, TAU); c.fill();
    }

    // çamurlu / kumlu kıyı
    c.save();
    c.filter = 'blur(6px)';
    creekPath(c, 24);
    c.fillStyle = '#6f5a3c';
    c.fill();
    c.restore();
    creekPath(c, 13);
    c.fillStyle = '#9b8360';
    c.fill();
    c.save();
    creekPath(c, 16);
    c.clip();
    for (let i = 0; i < 5000; i++) {
      const x = r() * W, v = r() < 0.5 ? -1 : 1;
      const y = centerY(x) + v * (halfW(x) + r() * 16);
      c.fillStyle = r() < 0.5 ? 'rgba(70,55,35,0.35)' : 'rgba(200,185,150,0.35)';
      c.fillRect(x, y, 1.5, 1.5);
    }
    c.restore();

    // su tabanı
    creekPath(c, 0);
    c.fillStyle = '#3f8ea6';
    c.fill();
    c.save();
    creekPath(c, 0);
    c.clip();
    for (let i = 0; i < 700; i++) {
      const x = r() * W, v = r() * 2 - 1;
      const y = centerY(x) + v * halfW(x);
      c.fillStyle = r() < 0.5 ? 'rgba(20,50,60,0.18)' : 'rgba(170,200,190,0.14)';
      c.beginPath(); c.ellipse(x, y, 2 + r() * 4, 1.5 + r() * 3, r() * 3, 0, TAU); c.fill();
    }
    c.filter = 'blur(16px)';
    c.beginPath();
    for (let x = -30; x <= W + 30; x += 6) c.lineTo(x, centerY(x) - halfW(x) * 0.5);
    for (let x = W + 30; x >= -30; x -= 6) c.lineTo(x, centerY(x) + halfW(x) * 0.5);
    c.closePath();
    c.fillStyle = 'rgba(18,70,98,0.75)';
    c.fill();
    c.filter = 'blur(5px)';
    c.lineWidth = 16;
    c.strokeStyle = 'rgba(140,200,200,0.45)';
    edgeLine(c, -1, 0); c.stroke();
    edgeLine(c, 1, 0); c.stroke();
    c.restore();
    c.lineWidth = 2;
    c.strokeStyle = 'rgba(55,42,28,0.55)';
    edgeLine(c, -1, 0); c.stroke();
    edgeLine(c, 1, 0); c.stroke();

    // kıyı taşları
    for (let i = 0; i < 46; i++) {
      const x = r() * W, side = r() < 0.5 ? -1 : 1;
      const y = centerY(x) + side * (halfW(x) + 2 + r() * 18);
      drawRock(c, x, y, 4 + r() * 9, 3 + r() * 6, r() * 3);
    }

    // sazlıklar
    for (let i = 0; i < 20; i++) {
      const x = 120 + r() * (W - 240), side = r() < 0.5 ? -1 : 1;
      const by = centerY(x) + side * (halfW(x) + 6);
      for (let k = 0; k < 16; k++) {
        const a = side * Math.PI / 2 + (r() - 0.5) * 2.2; // sudan dışarı doğru yayılır
        const len = 10 + r() * 16;
        const x0 = x + (r() - 0.5) * 10;
        c.strokeStyle = `hsl(${85 + r() * 20},45%,${22 + r() * 18}%)`;
        c.lineWidth = 1.6;
        c.beginPath();
        c.moveTo(x0, by);
        c.lineTo(x0 + Math.cos(a) * len, by + Math.sin(a) * len);
        c.stroke();
      }
    }

    // ağaçlar
    let placed = 0;
    for (let tries = 0; tries < 400 && placed < 34; tries++) {
      const x = r() * W, y = r() * H, R = 26 + r() * 26;
      if (Math.abs(y - centerY(x)) < halfW(x) + R + 30) continue;
      drawTree(c, x, y, R, r);
      placed++;
    }
  })();

  // ---------- akan su detayları ----------
  const streaks = [];
  const sparkles = [];
  (function buildWaterFx() {
    const r = rng(21);
    for (let i = 0; i < 170; i++) {
      const v = (r() * 2 - 1) * 0.88;
      streaks.push({ v, x0: r() * (W + 200), sp: (38 + r() * 40) * (1 - 0.45 * v * v), len: 18 + r() * 42, a: 0.07 + r() * 0.17, ph: r() * TAU });
    }
    for (let i = 0; i < 110; i++) {
      sparkles.push({ v: (r() * 2 - 1) * 0.9, x0: r() * (W + 200), sp: 30 + r() * 40, ph: r() * TAU, f: 1.5 + r() * 2.5 });
    }
  })();

  function drawWater(t) {
    ctx.save();
    creekPath(ctx, 0);
    ctx.clip();
    ctx.lineCap = 'round';
    ctx.lineWidth = 1.6;
    for (const s of streaks) {
      const xh = ((s.x0 + s.sp * t) % (W + 200)) - 100;
      ctx.strokeStyle = `rgba(230,250,255,${s.a})`;
      ctx.beginPath();
      for (let k = 0; k <= 6; k++) {
        const x = xh - (s.len * k) / 6;
        ctx.lineTo(x, centerY(x) + s.v * halfW(x) + Math.sin(t * 2 + s.ph + k * 0.5) * 1.4);
      }
      ctx.stroke();
    }
    for (const s of sparkles) {
      const x = ((s.x0 + s.sp * t) % (W + 200)) - 100;
      const y = centerY(x) + s.v * halfW(x);
      const a = Math.pow(Math.max(0, Math.sin(t * s.f + s.ph)), 10) * 0.9;
      if (a < 0.02) continue;
      ctx.fillStyle = `rgba(255,255,255,${a})`;
      ctx.fillRect(x - 2, y - 0.5, 4, 1);
      ctx.fillRect(x - 0.5, y - 2, 1, 4);
    }
    ctx.restore();
  }

  // ---------- yüzücü hareketi ----------
  const swimU = (t) => clamp((t - T_START) / (T_END - T_START), 0, 1);
  const ease = (u) => 0.45 * u + 0.55 * (0.5 - 0.5 * Math.cos(Math.PI * u));
  const effort = (t) =>
    smooth(clamp((t - T_START + 0.3) / 0.6, 0, 1)) * smooth(clamp((T_END + 0.3 - t) / 0.6, 0, 1));
  const strokePhase = (t) => TAU * 0.85 * (t - T_START + 0.3);

  function swimmerState(t) {
    const k = effort(t);
    const x = lerp(X_START, X_END, ease(swimU(t)));
    const drift = 13 * Math.sin(x * 0.011 + 0.4) * smooth(swimU(t) < 0.5 ? swimU(t) * 2 : (1 - swimU(t)) * 2);
    const bob = Math.sin(t * 2.3) * 1.6 * (1 - k);
    const y = centerY(x) + drift + bob;
    const driftSlope = 13 * 0.011 * Math.cos(x * 0.011 + 0.4) * 0.6;
    const ang = Math.atan(slope(x) + driftSlope) + Math.sin(strokePhase(t)) * 0.07 * k + Math.sin(t * 1.3) * 0.05 * (1 - k);
    return { x, y, ang, k };
  }

  function drawWake(t) {
    ctx.save();
    creekPath(ctx, 0);
    ctx.clip();
    ctx.lineCap = 'round';

    // yayılan halkalar
    const every = 0.4, life = 2.6;
    const last = Math.floor(t / every) * every;
    for (let n = 0; n < 8; n++) {
      const emit = last - n * every;
      if (emit < 0) break;
      const age = t - emit;
      if (age > life) break;
      const s = swimmerState(emit);
      const rr = 10 * SCALE + age * 26;
      ctx.strokeStyle = `rgba(225,245,250,${0.32 * (1 - age / life)})`;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.ellipse(s.x + age * 18, s.y, rr * 1.15, rr * 0.9, s.ang, 0, TAU);
      ctx.stroke();
    }

    // V şeklindeki dalga izi
    const span = 2.2, step = 0.06;
    for (const side of [-1, 1]) {
      let prev = null;
      for (let a = 0; a <= span; a += step) {
        const tt = t - a;
        if (tt < 0) break;
        const s = swimmerState(tt);
        const spread = 9 * SCALE + a * 24;
        const nx = -Math.sin(s.ang), ny = Math.cos(s.ang);
        const p = { x: s.x + 18 * SCALE * Math.cos(s.ang) + nx * side * spread + a * 14, y: s.y + 18 * SCALE * Math.sin(s.ang) + ny * side * spread };
        if (prev) {
          ctx.strokeStyle = `rgba(240,250,255,${0.5 * (1 - a / span) * s.k})`;
          ctx.lineWidth = 2.2 * (1 - a / span) + 0.6;
          ctx.beginPath(); ctx.moveTo(prev.x, prev.y); ctx.lineTo(p.x, p.y); ctx.stroke();
        }
        prev = p;
      }
    }

    // arkada kalan köpük
    for (let a = 0.05; a < 1.6; a += 0.05) {
      const tt = t - a;
      if (tt < 0) break;
      const s = swimmerState(tt);
      const r = rng(Math.floor(tt * 40) + 999);
      const alpha = 0.35 * (1 - a / 1.6) * s.k;
      ctx.fillStyle = `rgba(255,255,255,${alpha})`;
      for (let i = 0; i < 3; i++) {
        const bx = s.x - 40 * SCALE * Math.cos(s.ang) + (r() - 0.5) * (10 + a * 20) + a * 14;
        const by = s.y - 40 * SCALE * Math.sin(s.ang) + (r() - 0.5) * (10 + a * 20);
        ctx.beginPath(); ctx.arc(bx, by, 1 + r() * 2, 0, TAU); ctx.fill();
      }
    }
    ctx.restore();
  }

  // ---------- yüzücü çizimi ----------
  const SKIN = '#e1ad83', SHIRT = '#b8322a', PANTS = '#3d5aa0', HAIR = '#35251a';

  function armPose(t, side, k) {
    const ph = strokePhase(t) + (side > 0 ? 0 : Math.PI);
    const phi = ((ph % TAU) + TAU) % TAU;
    const L = 27, sx = 10, sy = side * 9;
    let hx, hy, under;
    if (phi < Math.PI) {           // çekiş: su altında, önden arkaya
      hx = sx + L * Math.cos(phi);
      hy = side * (9 + 7 * Math.sin(phi));
      under = true;
    } else {                        // geri getiriş: su üstünde, arkadan öne yay çizerek
      const a = phi - Math.PI;
      hx = sx - L * Math.cos(a);
      hy = side * (9 + 15 * Math.sin(a));
      under = false;
    }
    // su üstünde durma (kulaç yok) pozu
    const tx = 16 + 7 * Math.sin(t * 3.2 + side), ty = side * (20 + 4 * Math.cos(t * 3.2 + side));
    return {
      sx, sy,
      hx: lerp(tx, hx, k), hy: lerp(ty, hy, k),
      under: k < 0.5 ? true : under,
      phi,
    };
  }

  function drawArm(p, side) {
    const mx = (p.sx + p.hx) / 2, my = (p.sy + p.hy) / 2 + side * (p.under ? 2 : 6);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = SKIN;
    ctx.lineWidth = 6;
    ctx.beginPath(); ctx.moveTo(p.sx, p.sy); ctx.quadraticCurveTo(mx, my, p.hx, p.hy); ctx.stroke();
    ctx.strokeStyle = SHIRT;
    ctx.lineWidth = 7.5;
    ctx.beginPath(); ctx.moveTo(p.sx, p.sy); ctx.lineTo(lerp(p.sx, mx, 0.55), lerp(p.sy, my, 0.55)); ctx.stroke();
    ctx.fillStyle = '#d99c72';
    ctx.beginPath(); ctx.arc(p.hx, p.hy, 3.6, 0, TAU); ctx.fill();
  }

  function drawSplash(x, y, seed, strength) {
    if (strength <= 0.02) return;
    const r = rng(seed);
    for (let i = 0; i < 10; i++) {
      const a = r() * TAU, d = (3 + r() * 10) * (1.2 - strength * 0.4);
      ctx.fillStyle = `rgba(255,255,255,${0.75 * strength * (0.5 + r() * 0.5)})`;
      ctx.beginPath(); ctx.arc(x + Math.cos(a) * d, y + Math.sin(a) * d, 1 + r() * 2.2, 0, TAU); ctx.fill();
    }
  }

  function drawSwimmer(t, frame) {
    const s = swimmerState(t);
    const k = s.k;
    const ph = strokePhase(t);
    const arms = [armPose(t, 1, k), armPose(t, -1, k)];

    ctx.save();
    ctx.translate(s.x, s.y);
    ctx.rotate(s.ang);
    ctx.scale(SCALE, SCALE);

    // suyun altındaki gölge
    ctx.fillStyle = 'rgba(8,35,50,0.35)';
    ctx.beginPath(); ctx.ellipse(-4, 7, 46, 15, 0, 0, TAU); ctx.fill();

    // bacaklar (suyun altında)
    ctx.globalAlpha = 0.75;
    ctx.lineCap = 'round';
    for (const side of [1, -1]) {
      const kick = Math.sin(ph * 3 + (side > 0 ? 0 : Math.PI)) * k + Math.sin(t * 4 + side) * 0.6 * (1 - k);
      const hipX = -16, hipY = side * 4.5;
      const fx = -43 + (1 - k) * 7, fy = side * (5 + (1 - k) * 6) + kick * 4;
      ctx.strokeStyle = PANTS;
      ctx.lineWidth = 8;
      ctx.beginPath(); ctx.moveTo(hipX, hipY); ctx.quadraticCurveTo(-30, hipY + kick * 2, fx, fy); ctx.stroke();
      ctx.fillStyle = SKIN;
      ctx.beginPath(); ctx.ellipse(fx - 3, fy, 4.5, 3, 0, 0, TAU); ctx.fill();
    }
    ctx.globalAlpha = 1;
    ctx.fillStyle = 'rgba(63,142,166,0.25)';
    ctx.beginPath(); ctx.ellipse(-30, 0, 20, 13, 0, 0, TAU); ctx.fill();

    // su altındaki kollar
    ctx.globalAlpha = 0.55;
    arms.forEach((p, i) => { if (p.under) drawArm(p, i === 0 ? 1 : -1); });
    ctx.globalAlpha = 1;

    // gövde
    const tg = ctx.createLinearGradient(0, -11, 0, 11);
    tg.addColorStop(0, '#d0463b');
    tg.addColorStop(1, '#8e231d');
    ctx.fillStyle = tg;
    ctx.beginPath(); ctx.ellipse(-3, 0, 20, 10.5, 0, 0, TAU); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,0.25)';
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(-18, 0); ctx.lineTo(10, 0); ctx.stroke();
    // gövdenin arka kısmı suya gömülü
    const wg = ctx.createLinearGradient(-24, 0, 4, 0);
    wg.addColorStop(0, 'rgba(63,142,166,0.65)');
    wg.addColorStop(1, 'rgba(63,142,166,0)');
    ctx.fillStyle = wg;
    ctx.beginPath(); ctx.ellipse(-3, 0, 20.5, 11, 0, 0, TAU); ctx.fill();

    // baş
    ctx.fillStyle = SKIN;
    ctx.beginPath(); ctx.ellipse(18, 0, 4, 4.5, 0, 0, TAU); ctx.fill();
    const breathe = Math.max(0, Math.sin(ph * 0.5)) ** 6 * k; // nefes alırken başı yana çevirir
    ctx.save();
    ctx.translate(23, 0);
    ctx.rotate(breathe * 0.5);
    const hg = ctx.createRadialGradient(-2, -3, 1, 0, 0, 9);
    hg.addColorStop(0, '#5a4130');
    hg.addColorStop(1, HAIR);
    ctx.fillStyle = hg;
    ctx.beginPath(); ctx.ellipse(0, 0, 9, 8, 0, 0, TAU); ctx.fill();
    if (breathe > 0.05) {
      ctx.fillStyle = SKIN;
      ctx.globalAlpha = breathe;
      ctx.beginPath(); ctx.ellipse(3, 5.5, 5, 3, 0.3, 0, TAU); ctx.fill();
      ctx.globalAlpha = 1;
    }
    ctx.restore();

    // su üstündeki kollar
    arms.forEach((p, i) => { if (!p.under) drawArm(p, i === 0 ? 1 : -1); });

    // vücudu saran köpük
    ctx.strokeStyle = 'rgba(255,255,255,0.4)';
    ctx.lineWidth = 2;
    ctx.setLineDash([4, 5]);
    ctx.lineDashOffset = -t * 30;
    ctx.beginPath(); ctx.ellipse(-2, 0, 26, 14, 0, 0, TAU); ctx.stroke();
    ctx.setLineDash([]);

    // el suya girerken sıçrama
    arms.forEach((p, i) => {
      const side = i === 0 ? 1 : -1;
      const strength = p.phi < 0.7 ? (1 - p.phi / 0.7) * k : 0;
      const cycle = Math.floor((ph + (side > 0 ? 0 : Math.PI)) / TAU);
      drawSplash(p.hx, p.hy, cycle * 2 + (side > 0 ? 1 : 0) + 17, strength);
    });
    // ayak vuruşu köpüğü
    const r = rng(frame * 7 + 3);
    for (let i = 0; i < 9; i++) {
      ctx.fillStyle = `rgba(255,255,255,${(0.3 + r() * 0.4) * k})`;
      ctx.beginPath(); ctx.arc(-46 - r() * 10, (r() - 0.5) * 16, 1 + r() * 2.2, 0, TAU); ctx.fill();
    }

    ctx.restore();
  }

  // ---------- başlangıç / varış halatları ----------
  function drawRope(x, t, label, color) {
    const y0 = centerY(x) - halfW(x) - 10, y1 = centerY(x) + halfW(x) + 10;
    ctx.strokeStyle = 'rgba(0,0,0,0.25)';
    ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(x + 3, y0 + 3); ctx.lineTo(x + 3, y1 + 3); ctx.stroke();
    ctx.strokeStyle = '#e9e1cf';
    ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(x, y0); ctx.lineTo(x, y1); ctx.stroke();
    const n = 11;
    for (let i = 1; i < n; i++) {
      const y = lerp(y0, y1, i / n);
      const dx = Math.sin(t * 2 + i * 0.9) * 1.5;
      ctx.fillStyle = 'rgba(0,0,0,0.25)';
      ctx.beginPath(); ctx.arc(x + dx + 2, y + 2, 5.5, 0, TAU); ctx.fill();
      ctx.fillStyle = i % 2 ? color : '#ffffff';
      ctx.beginPath(); ctx.arc(x + dx, y, 5.5, 0, TAU); ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,0.6)';
      ctx.beginPath(); ctx.arc(x + dx - 1.8, y - 1.8, 1.6, 0, TAU); ctx.fill();
    }
    for (const y of [y0, y1]) {
      ctx.fillStyle = '#6b4a2b';
      ctx.beginPath(); ctx.arc(x, y, 5, 0, TAU); ctx.fill();
    }
    ctx.font = 'bold 15px "DejaVu Sans", Arial, sans-serif';
    const tw = ctx.measureText(label).width;
    const bx = clamp(x - tw / 2 - 10, 8, W - tw - 28), by = y0 - 40;
    ctx.fillStyle = 'rgba(20,24,28,0.72)';
    roundRect(bx, by, tw + 20, 26, 6);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.fillRect(bx, by, 4, 26);
    ctx.fillStyle = '#ffffff';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, bx + 11, by + 13.5);
  }

  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  // ---------- bilgi paneli ----------
  function drawHud(t) {
    const u = swimU(t);
    const meters = ease(u) * CREEK_METERS;
    const elapsed = clamp(t - T_START, 0, T_END - T_START);
    const status = t < T_START ? 'Suya girdi' : t < T_END ? 'Yüzüyor' : 'Karşı uca ulaştı';

    ctx.font = 'bold 22px "DejaVu Sans", Arial, sans-serif';
    ctx.textBaseline = 'alphabetic';
    const title = 'Kurbanın Dere Boyunca Yüzüşü';
    ctx.fillStyle = 'rgba(20,24,28,0.62)';
    roundRect(20, 18, ctx.measureText(title).width + 28, 44, 8);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.fillText(title, 34, 48);

    const px = 20, py = H - 74, pw = 420, ph = 54;
    ctx.fillStyle = 'rgba(20,24,28,0.62)';
    roundRect(px, py, pw, ph, 8);
    ctx.fill();
    ctx.font = '15px "DejaVu Sans", Arial, sans-serif';
    ctx.fillStyle = '#ffffff';
    const sec = elapsed.toFixed(1).padStart(4, '0');
    ctx.fillText(`Süre ${sec} sn   ·   ${meters.toFixed(1)} / ${CREEK_METERS} m   ·   ${status}`, px + 14, py + 22);
    ctx.fillStyle = 'rgba(255,255,255,0.2)';
    roundRect(px + 14, py + 34, pw - 28, 8, 4);
    ctx.fill();
    ctx.fillStyle = '#5fd0e6';
    roundRect(px + 14, py + 34, Math.max(8, (pw - 28) * ease(u)), 8, 4);
    ctx.fill();
  }

  // ---------- kare ----------
  function draw(t, frame) {
    ctx.drawImage(bg, 0, 0);
    drawWater(t);
    drawRope(ROPE_START, t, 'BAŞLANGIÇ', '#e2553f');
    drawRope(ROPE_END, t, 'VARIŞ', '#35b464');
    drawWake(t);
    drawSwimmer(t, frame);
    drawHud(t);

    const fade = Math.max(clamp(1 - t / 0.6, 0, 1), clamp((t - (DURATION - 0.7)) / 0.7, 0, 1));
    if (fade > 0) {
      ctx.fillStyle = `rgba(0,0,0,${fade})`;
      ctx.fillRect(0, 0, W, H);
    }
  }

  window.ANIM = { W, H, FPS, DURATION, TOTAL_FRAMES };
  window.renderFrame = function (frame) {
    draw(frame / FPS, frame);
  };

  // Tarayıcıda açıldığında canlı önizleme; render.js ?render ile açar ve kareleri kendisi ister.
  if (!/[?&]render\b/.test(location.search)) {
    const start = performance.now();
    (function loop(now) {
      const frame = Math.floor((((now - start) / 1000) % DURATION) * FPS);
      window.renderFrame(frame);
      requestAnimationFrame(loop);
    })(start);
  }
})();
