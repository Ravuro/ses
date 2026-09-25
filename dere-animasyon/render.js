// index.html'deki animasyonu başsız Chromium'da kare kare çizip ffmpeg ile MP4'e dönüştürür.
// Kullanım: npm install && npm run render  [-- çıktı.mp4]
'use strict';

const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { pathToFileURL } = require('url');
const { chromium } = require('playwright');
const ffmpegPath = require('ffmpeg-static');

const OUTPUT = path.resolve(process.argv[2] || path.join(__dirname, 'output', 'kurban-dere-yuzusu.mp4'));

async function main() {
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });

  const browser = await chromium.launch();
  const page = await browser.newPage();
  const url = pathToFileURL(path.join(__dirname, 'index.html')).href + '?render';
  await page.goto(url);
  const { W, H, FPS, TOTAL_FRAMES } = await page.evaluate(() => window.ANIM);

  const ffmpeg = spawn(ffmpegPath, [
    '-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart',
    OUTPUT,
  ], { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((resolve, reject) => {
    ffmpeg.on('error', reject);
    ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg çıkış kodu ${code}`))));
  });

  for (let i = 0; i < TOTAL_FRAMES; i++) {
    const dataUrl = await page.evaluate((f) => {
      window.renderFrame(f);
      return document.getElementById('scene').toDataURL('image/png');
    }, i);
    const png = Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64');
    if (!ffmpeg.stdin.write(png)) await new Promise((r) => ffmpeg.stdin.once('drain', r));
    if (i % FPS === 0 || i === TOTAL_FRAMES - 1) {
      process.stdout.write(`\rKare ${i + 1}/${TOTAL_FRAMES}`);
    }
  }
  ffmpeg.stdin.end();
  await done;
  await browser.close();
  console.log(`\n${W}x${H}, ${FPS} fps → ${OUTPUT}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
