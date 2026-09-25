// index.html'deki klibi başsız Chromium'da kare kare çizer ve ffmpeg ile MP4'e çevirir.
//
// Kullanım:
//   npm install
//   npm run render                                   → output/yarim-klip.mp4 (1920x1080, demo müzikle)
//   node render.js --audio sarki.mp3 --timing zamanlama.json   → kendi şarkınla
//   node render.js --size 720 cikti.mp4              → 1280x720
//   node render.js --range 55-95                     → sadece bu saniyeler (önizleme)
//   node render.js --still 12.5,60.4                 → tek kare(ler) JPEG olarak
//   --sfx 0.6    (kendi şarkınla birlikte kâğıt/kalem efektlerinin seviyesi; 0 = kapalı)
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
const stills = opt('--still', null);
const songPath = opt('--audio', null);
const timingPath = opt('--timing', null);
const rangeArg = opt('--range', null);
const sfxGain = Number(opt('--sfx', songPath ? '0.6' : '1'));
const OUTPUT = path.resolve(args[0] || path.join(__dirname, 'output', 'yarim-klip.mp4'));

function run(argv, feed) {
  return new Promise((resolve, reject) => {
    const p = spawn(ffmpegPath, argv, { stdio: [feed ? 'pipe' : 'ignore', 'inherit', 'inherit'] });
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg çıkış kodu ${code}`))));
    if (feed) feed(p.stdin);
  });
}

async function open() {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  // Google Fonts isteklerini Node üzerinden getir (bazı ağlarda tarayıcı sertifikası sorun çıkarabiliyor).
  await page.route(/fonts\.(googleapis|gstatic)\.com/, async (route) => {
    try {
      const res = await fetch(route.request().url(), { headers: { 'user-agent': route.request().headers()['user-agent'] || '' } });
      await route.fulfill({ status: res.status, body: Buffer.from(await res.arrayBuffer()), headers: { 'content-type': res.headers.get('content-type') || 'application/octet-stream', 'access-control-allow-origin': '*' } });
    } catch (e) {
      await route.abort();
    }
  });
  page.on('pageerror', (e) => console.error('Sayfa hatası:', e.message));
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href + '?render');
  const cues = timingPath ? JSON.parse(fs.readFileSync(timingPath, 'utf8')) : null;
  const info = await page.evaluate((o) => window.KLIP.init(o), { scale: size / 1080 * (1920 / 1920), cues });
  if (!info.font) console.warn('Uyarı: Caveat yazı tipi yüklenemedi, yedek el yazısı kullanılacak.');
  return { browser, page, info };
}

async function main() {
  fs.mkdirSync(path.join(__dirname, 'output'), { recursive: true });
  const { browser, page, info } = await open();
  const fps = info.fps;

  if (stills) {
    for (const s of stills.split(',')) {
      const data = await page.evaluate((f) => window.KLIP.frame(f, 0.9), Math.round(Number(s) * fps));
      const out = path.join(__dirname, 'output', `still-${s}.jpg`);
      fs.writeFileSync(out, Buffer.from(data.slice(data.indexOf(',') + 1), 'base64'));
      console.log(out);
    }
    await browser.close();
    return;
  }

  let [from, to] = [0, info.duration];
  if (rangeArg) [from, to] = rangeArg.split('-').map(Number);
  const f0 = Math.floor(from * fps), f1 = Math.min(info.frames, Math.ceil(to * fps));
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'yarim-'));
  const videoTmp = path.join(tmp, 'video.mp4');
  const audioTmp = path.join(tmp, 'audio.wav');

  let err = null;
  await run(['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '29', '-pix_fmt', 'yuv420p', videoTmp], async (stdin) => {
    try {
      for (let i = f0; i < f1; i++) {
        const data = await page.evaluate((f) => window.KLIP.frame(f, 0.93), i);
        if (!stdin.write(Buffer.from(data.slice(data.indexOf(',') + 1), 'base64'))) await new Promise((r) => stdin.once('drain', r));
        if ((i - f0) % fps === 0 || i === f1 - 1) process.stdout.write(`\rKare ${i - f0 + 1}/${f1 - f0}`);
      }
    } catch (e) { err = e; }
    stdin.end();
  });
  if (err) throw err;
  process.stdout.write('\n');

  console.log('Ses işleniyor...');
  const wav = await page.evaluate((o) => window.KLIP.audio(o), songPath ? { music: false, sfx: sfxGain > 0, sfxGain } : { sfxGain });
  fs.writeFileSync(audioTmp, Buffer.from(wav, 'base64'));
  await browser.close();

  const a = ['-y', '-loglevel', 'error', '-i', videoTmp, '-ss', String(from), '-t', String((f1 - f0) / fps), '-i', audioTmp];
  if (songPath) {
    a.push('-ss', String(from), '-t', String((f1 - f0) / fps), '-i', path.resolve(songPath),
      '-filter_complex', '[2:a][1:a]amix=inputs=2:duration=first:normalize=0,loudnorm=I=-15:TP=-1.2:LRA=11[a]', '-map', '0:v', '-map', '[a]');
  } else {
    a.push('-af', 'loudnorm=I=-16:TP=-1.5:LRA=11');
  }
  a.push('-ar', '44100', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart', OUTPUT);
  await run(a);
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(`Hazır: ${OUTPUT}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
