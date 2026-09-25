// index.html'deki animasyonu başsız Chromium'da kare kare çizer, sesi Web Audio
// OfflineAudioContext ile üretir ve ikisini ffmpeg ile tek bir MP4'te birleştirir.
//
// Kullanım:
//   npm install
//   npm run render                          → output/zipzip-dere-macerasi.mp4 (1920x1080)
//   node render.js --size 720 cikti.mp4     → 1280x720
//   node render.js --still 12.5             → output/still-12.5.jpg (tek kare önizleme)
'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const { pathToFileURL } = require('url');
const { chromium } = require('playwright');
const ffmpegPath = require('ffmpeg-static');

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(name);
  if (i < 0) return def;
  const v = args[i + 1];
  args.splice(i, 2);
  return v;
};
const size = Number(opt('--size', '1080'));
const still = opt('--still', null);
const scale = size / 720;
const OUTPUT = path.resolve(args[0] || path.join(__dirname, 'output', 'zipzip-dere-macerasi.mp4'));

function run(bin, argv, input) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, argv, { stdio: [input ? 'pipe' : 'ignore', 'inherit', 'inherit'] });
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg çıkış kodu ${code}`))));
    if (input) input(p.stdin);
  });
}

async function openPage() {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  // Google Fonts isteklerini Node üzerinden getir (bazı ağlarda tarayıcı sertifikası sorun çıkarabilir).
  await page.route(/fonts\.(googleapis|gstatic)\.com/, async (route) => {
    try {
      const res = await fetch(route.request().url(), { headers: { 'user-agent': route.request().headers()['user-agent'] || '' } });
      const body = Buffer.from(await res.arrayBuffer());
      await route.fulfill({ status: res.status, body, headers: { 'content-type': res.headers.get('content-type') || 'application/octet-stream', 'access-control-allow-origin': '*' } });
    } catch (e) {
      await route.abort();
    }
  });
  page.on('pageerror', (e) => console.error('Sayfa hatası:', e.message));
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href + '?render');
  const info = await page.evaluate((s) => window.KIDS.init(s).then(() => ({
    FPS: window.KIDS.FPS, TOTAL_FRAMES: window.KIDS.TOTAL_FRAMES, fontOk: document.fonts.check("700 40px 'Fredoka'"),
  })), scale);
  if (!info.fontOk) console.warn('Uyarı: Fredoka yazı tipi yüklenemedi, yedek yazı tipi kullanılacak.');
  return { browser, page, ...info };
}

async function renderStill(sec) {
  const { browser, page, FPS } = await openPage();
  const dataUrl = await page.evaluate((f) => window.KIDS.frame(f, 0.92), Math.round(Number(sec) * FPS));
  const out = path.join(__dirname, 'output', `still-${sec}.jpg`);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64'));
  await browser.close();
  console.log(out);
}

async function renderVideo() {
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zipzip-'));
  const videoTmp = path.join(tmp, 'video.mp4');
  const audioTmp = path.join(tmp, 'audio.wav');
  const { browser, page, FPS, TOTAL_FRAMES } = await openPage();

  // 1) Görüntü: kareler sırayla simüle edilip ffmpeg'e JPEG olarak aktarılır.
  let frameErr = null;
  const video = run(ffmpegPath, [
    '-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p',
    videoTmp,
  ], async (stdin) => {
    try {
      for (let i = 0; i < TOTAL_FRAMES; i++) {
        const dataUrl = await page.evaluate((f) => window.KIDS.frame(f, 0.93), i);
        const jpg = Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64');
        if (!stdin.write(jpg)) await new Promise((r) => stdin.once('drain', r));
        if (i % FPS === 0 || i === TOTAL_FRAMES - 1) process.stdout.write(`\rKare ${i + 1}/${TOTAL_FRAMES}`);
      }
    } catch (e) {
      frameErr = e;
    }
    stdin.end();
  });
  await video;
  if (frameErr) throw frameErr;
  process.stdout.write('\n');

  // 2) Ses: simülasyon sırasında kaydedilen efektler + müzik çevrimdışı işlenir.
  console.log('Ses işleniyor...');
  const wav = await page.evaluate(() => window.KIDS.audio());
  fs.writeFileSync(audioTmp, Buffer.from(wav, 'base64'));
  await browser.close();

  // 3) Birleştir.
  await run(ffmpegPath, [
    '-y', '-loglevel', 'error',
    '-i', videoTmp, '-i', audioTmp,
    '-c:v', 'copy', '-af', 'loudnorm=I=-18:TP=-1.5:LRA=11', '-ar', '44100', '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart',
    OUTPUT,
  ]);
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(`Hazır: ${OUTPUT}`);
}

(still ? renderStill(still) : renderVideo()).catch((err) => {
  console.error(err);
  process.exit(1);
});
