<?php
/**
 * Telsiz rölesi — paylaşımlı hosting (cPanel/PHP) sürümü.
 *
 * Node.js sürümü (server.js) WebSocket kullanıyor ve sürekli çalışan bir
 * süreç istiyor; paylaşımlı hosting'de ikisi de yok. Burada aynı işi HTTP ile
 * yapıyoruz: gönderen ses paketlerini POST ediyor, dinleyenler uzun bekleyen
 * bir GET tutuyor ve yeni veri gelir gelmez cevap alıyor.
 *
 * Bunun bedeli gecikme: paketler ~200 ms'lik gruplar halinde taşınıyor, yani
 * araya yarım saniyeye yakın bir gecikme giriyor. Telsizde katlanılır.
 *
 * Sesi çözmez, saklamaz: paketler istemcide üretildiği gibi geçer. Kanal
 * dosyası 1 MB'ı geçince sıfırlanır — canlı ses için geçmiş gereksiz.
 *
 * KURULUM: bu dosyayı FTP ile herhangi bir klasöre at. Uygulamadaki Relay
 * alanına tam adresini yaz, örnek:
 *     https://siteniz.com/telsiz/relay.php
 */

declare(strict_types=1);

const DATA_DIR      = __DIR__ . '/telsiz-data';
const MAX_FILE      = 1048576;   // 1 MB: aşınca kanal dosyası sıfırlanır
const POLL_SECONDS  = 15;        // uzun bekleyen GET süresi
/**
 * Aynı anda kaç istek uzun bekleyebilir.
 *
 * Bu sınır rölenin değil, siteyi ayakta tutmanın meselesi. Uzun bekleyen
 * her istek 15 saniye boyunca bir PHP işçisini tutuyor; paylaşımlı hosting
 * hesabında eşzamanlı işçi sayısı çoğu zaman 10-30 arasında. Sınır
 * konmazsa on kişilik bir telsiz kanalı, aynı hesapta duran web sitesini
 * de birlikte götürür.
 *
 * Sınıra takılan istek hata almıyor, yalnızca kısa bekleyip dönüyor:
 * istemci saniyede bir soruyor, gecikme artıyor ama ses akmaya devam
 * ediyor ve site ayakta kalıyor.
 */
const MAX_LISTENERS = 8;
/**
 * Sınıra takılan isteğin bekleyeceği süre.
 *
 * Bir saniyeydi ve bu, korumayı tam tersine çeviriyordu: mekanizma
 * çalışmadığında her istek bu yola düşüyor ve istemci saniyede bir
 * soruyordu — 15 saniyelik uzun beklemeye göre on beş kat DAHA ÇOK istek.
 * Sunucuyu korumak için konan şey sunucuyu dövüyordu. Sahada ölçüldü:
 * 57 dakikada 227 yerine 3381 sorgu.
 */
const SHORT_POLL_SECONDS = 4;
/** Bu kadar süredir dokunulmayan kanal dosyası siliniyor. */
const CHANNEL_TTL   = 86400;
const POLL_SLEEP_US = 40000;     // 40 ms
const MAX_RESPONSE  = 65536;
const MAX_POST      = 65536;

@ini_set('zlib.output_compression', '0');
@set_time_limit(POLL_SECONDS + 15);
ignore_user_abort(false);

/** Kanal numarası dışında bir şey kabul etmiyoruz. */
function channel_file(): string {
    $ch = isset($_GET['ch']) ? preg_replace('/[^0-9]/', '', (string) $_GET['ch']) : '1';
    if ($ch === '' || strlen($ch) > 3) {
        $ch = '1';
    }
    if (!is_dir(DATA_DIR)) {
        @mkdir(DATA_DIR, 0700, true);
        @file_put_contents(DATA_DIR . '/.htaccess', "Deny from all\n");
    }
    return DATA_DIR . '/ch' . $ch . '.bin';
}

/** Gönderenin kendi paketlerini geri almaması için 32 bitlik kimlik. */
function sender_hash(): int {
    $id = isset($_GET['id']) ? substr((string) $_GET['id'], 0, 32) : '';
    return (int) (crc32($id) & 0xFFFFFFFF);
}

/**
 * Uzun bekleme için yer kapar; yer yoksa null döner.
 *
 * Sayaç yerine kilit dosyası kullanılıyor: PHP süreci nasıl biterse bitsin
 * (zaman aşımı, ölüm, istemcinin kopması) işletim sistemi kilidi
 * kendiliğinden bırakıyor. Sayaç artırmak olsaydı, düşen her istek
 * sayacı kalıcı olarak şişirirdi.
 */
function take_listen_slot() {
    $opened = 0;
    for ($i = 0; $i < MAX_LISTENERS; $i++) {
        $f = @fopen(DATA_DIR . '/slot' . $i . '.lock', 'c');
        if ($f === false) {
            // Bu yeri açamadık; diğerlerini denemeye devam. Eskiden burada
            // pes ediliyordu, yani tek bir izin sorunu bütün istekleri kısa
            // beklemeye düşürüyordu.
            continue;
        }
        $opened++;
        if (flock($f, LOCK_EX | LOCK_NB)) {
            return $f;
        }
        fclose($f);
    }
    // Hiçbir kilit dosyası açılamadıysa mekanizma çalışmıyor demektir
    // (bazı paylaşımlı hostinglerde flock ya da yazma izni yok). Bu durumda
    // kısıtlamak yanlış: koruma diye istemciyi saniyede bir sorduramayız.
    // Açık tarafa düşüyoruz — eski, sınırsız davranış.
    return $opened === 0 ? false : null;
}

/**
 * Eskimiş kanal dosyalarını siler.
 *
 * Her kanal numarası için bir dosya açılıyor ve hiç kapanmıyordu; bir kez
 * kullanılan kanal, hosting hesabında sonsuza kadar yer tutuyordu. Sık
 * çalıştırmaya gerek yok, POST'ların yüzde birinde yapılıyor.
 */
function sweep_old_channels() {
    $now = time();
    foreach ((array) @glob(DATA_DIR . '/ch*.bin') as $old) {
        $t = @filemtime($old);
        if ($t !== false && $now - $t > CHANNEL_TTL) {
            @unlink($old);
        }
    }
}

$file = channel_file();
$me   = sender_hash();

// ---- kendi kendini test eden sayfa ----
// Durum sayfasi yalnizca PHP'nin calistigini gosteriyor. Asil merak edilen
// POST ve uzun bekleyen GET'in bu sunucunun Apache/PHP ayarlarindan gecip
// gecmedigi; onu ancak gercek bir istekle anlariz. Bu sayfa tarayicidan
// tam olarak uygulamanin yaptigi seyi yapiyor.
if (isset($_GET['test'])) {
    header('Content-Type: text/html; charset=utf-8');
    ?><!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Telsiz rölesi — test</title>
<style>
  :root{--bg:#0B0E14;--card:#141922;--card2:#1B2230;--line:#232B39;
        --tx:#EAEEF5;--mut:#7C8798;--ok:#3DDC84;--bad:#F87171;--blue:#3B82F6}
  *{box-sizing:border-box}
  body{background:var(--bg);color:var(--tx);margin:0;padding:24px 18px 40px;
       font:16px/1.55 -apple-system,system-ui,"Segoe UI",Roboto,sans-serif;
       max-width:620px;margin-inline:auto;-webkit-text-size-adjust:100%}
  .badge{display:inline-block;font-size:11px;font-weight:700;letter-spacing:.16em;
         color:var(--mut);border:1px solid var(--line);border-radius:999px;
         padding:5px 12px;margin-bottom:14px}
  h1{font-size:24px;line-height:1.25;margin:0 0 6px;letter-spacing:-.01em}
  p.sub{color:var(--mut);font-size:14px;margin:0 0 22px}
  button{background:var(--blue);color:#fff;border:0;border-radius:14px;
         padding:17px 22px;font-size:16px;font-weight:700;width:100%;
         letter-spacing:.04em;cursor:pointer;transition:opacity .15s,transform .1s}
  button:active{transform:scale(.985)}
  button:disabled{opacity:.45}
  #log{margin-top:18px;background:var(--card);border:1px solid var(--line);
       border-radius:16px;padding:18px;min-height:64px;
       font:13.5px/1.7 ui-monospace,SFMono-Regular,Menlo,monospace;
       white-space:pre-wrap;word-break:break-word}
  .ok{color:var(--ok)}.bad{color:var(--bad)}.dim{color:var(--mut)}
  .big{display:block;font-size:17px;font-weight:700;letter-spacing:.04em;
       margin-top:14px;font-family:-apple-system,system-ui,sans-serif}
  .spin{display:inline-block;width:11px;height:11px;border-radius:50%;
        border:2px solid var(--line);border-top-color:var(--blue);
        animation:r .7s linear infinite;vertical-align:-1px;margin-right:6px}
  @keyframes r{to{transform:rotate(360deg)}}
</style></head><body>
<div class="badge">TELSİZ</div>
<h1>Röle testi</h1>
<p class="sub">Uygulamanın yaptığı işin aynısını yapar: ses paketi gönderir, karşı taraftan geri alır, gecikmeyi ölçer.</p>
<button id="go">TESTİ BAŞLAT</button>
<div id="log" class="dim">Hazır.</div>
<script>
const url = location.pathname;
const log = document.getElementById('log');
const go  = document.getElementById('go');
let out = '';
function say(t, cls){ out += (cls?`<span class="${cls}">${t}</span>`:t) + '\n'; log.innerHTML = out; }

go.onclick = async () => {
  go.disabled = true; out = ''; log.innerHTML = '';
  go.textContent = 'ÇALIŞIYOR…';
  const ch = 900 + Math.floor(Math.random()*99);
  const A = 'test-a-' + Math.random().toString(36).slice(2,8);
  const B = 'test-b-' + Math.random().toString(36).slice(2,8);
  const marker = new Uint8Array(16);
  crypto.getRandomValues(marker);

  try {
    // 1) Durum
    say('1) Sunucu yanıt veriyor mu...');
    const st = await fetch(url, {cache:'no-store'});
    const txt = await st.text();
    if (!st.ok) throw new Error('durum sayfası HTTP ' + st.status);
    if (txt.indexOf('calisiyor') < 0) throw new Error('beklenmeyen cevap: ' + txt.slice(0,80));
    say('   PHP çalışıyor.', 'ok');
    if (txt.indexOf('yazilabilir: evet') < 0) {
      say('   UYARI: veri klasörü yazılabilir değil. Klasör iznini 755 yap.', 'bad');
    } else { say('   Veri klasörü yazılabilir.', 'ok'); }

    // 2) Dinleyiciyi ac (uzun bekleyen GET), sonra gonder
    say('2) Dinleyici açılıyor, paket gönderiliyor...');
    const t0 = performance.now();
    const listen = fetch(url + '?ch=' + ch + '&id=' + B, {cache:'no-store'})
                     .then(r => r.ok ? r.arrayBuffer() : Promise.reject(new Error('GET HTTP ' + r.status)));
    await new Promise(r => setTimeout(r, 700));

    const body = new Uint8Array(2 + marker.length);
    body[0] = 0; body[1] = marker.length;
    body.set(marker, 2);
    const post = await fetch(url + '?ch=' + ch + '&id=' + A,
        {method:'POST', body:body, headers:{'Content-Type':'application/octet-stream'}, cache:'no-store'});
    if (!post.ok) throw new Error('POST HTTP ' + post.status + ' — sunucu göndermeyi reddetti');
    say('   Gönderme kabul edildi.', 'ok');

    // 3) Geri geldi mi
    say('3) Paket karşı tarafa ulaşıyor mu...');
    const timeout = new Promise((_,rej) => setTimeout(() => rej(new Error('20 sn içinde gelmedi')), 20000));
    const buf = new Uint8Array(await Promise.race([listen, timeout]));
    const ms = Math.round(performance.now() - t0 - 700);

    if (buf.length <= 8) throw new Error('cevap boş geldi — uzun bekleme çalışmıyor olabilir');
    let found = -1;
    for (let i = 8; i + marker.length <= buf.length; i++) {
      let m = true;
      for (let j = 0; j < marker.length; j++) if (buf[i+j] !== marker[j]) { m = false; break; }
      if (m) { found = i; break; }
    }
    if (found < 0) throw new Error('veri geldi ama paket bozuk');
    say('   Paket birebir ulaştı. Gecikme ~' + ms + ' ms.', 'ok');
    say('<span class="big">RÖLE ÇALIŞIYOR ✓</span>', 'ok');
    say('Uygulamadaki Relay alanına şunu yaz:\n' + location.origin + url, 'dim');
  } catch (e) {
    say('<span class="big">BAŞARISIZ</span>' + e.message, 'bad');
    say('\nSık görülen sebepler:\n' +
        '• POST HTTP 403 → mod_security ikili gönderiyi engelliyor,\n' +
        '  hosting desteğinden bu dosya için kapatmalarını iste.\n' +
        '• Cevap boş / zaman aşımı → sunucu uzun bekleyen isteği kesiyor.\n' +
        '• yazilabilir: HAYIR → klasör iznini 755 yap.', 'dim');
  }
  go.disabled = false;
  go.textContent = 'TEKRAR TEST ET';
};
</script></body></html><?php
    exit;
}

// ---- durum sayfası ----
if (!isset($_GET['ch'])) {
    header('Content-Type: text/plain; charset=utf-8');
    echo "telsiz-relay (PHP) calisiyor\n";
    echo 'PHP ' . PHP_VERSION . "\n";
    echo 'veri klasoru yazilabilir: ' . (is_dir(DATA_DIR) && is_writable(DATA_DIR) ? 'evet' : 'HAYIR') . "\n";
    exit;
}

// ---- gönderim ----
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $body = file_get_contents('php://input', false, null, 0, MAX_POST);
    if ($body === false || $body === '') {
        http_response_code(204);
        exit;
    }
    $f = @fopen($file, 'cb');
    if ($f === false) {
        http_response_code(500);
        echo 'kanal dosyasi acilamadi';
        exit;
    }
    flock($f, LOCK_EX);
    fseek($f, 0, SEEK_END);
    if (ftell($f) > MAX_FILE) {
        ftruncate($f, 0);
        fseek($f, 0, SEEK_SET);
    }
    // kayit: [4 bayt uzunluk][4 bayt gonderen][veri]
    fwrite($f, pack('VV', strlen($body), $me) . $body);
    fflush($f);
    flock($f, LOCK_UN);
    fclose($f);
    if (random_int(1, 100) === 1) {
        sweep_old_channels();
    }
    http_response_code(204);
    exit;
}

// ---- dinleme (uzun bekleyen GET) ----
$cursor = isset($_GET['cur']) ? (int) $_GET['cur'] : -1;

clearstatcache(true, $file);
$size = is_file($file) ? (int) filesize($file) : 0;
// Imlec yoksa bastan degil sondan basla: eski sesi tekrar calmanin anlami yok.
if ($cursor < 0 || $cursor > $size) {
    $cursor = $size;
}

// Yer varsa uzun bekle; gerçekten kalabalıksa kısa. Mekanizma hiç
// çalışmıyorsa (false) uzun beklemeye devam: kısıtlama, kısıtlamadığından
// daha çok yük üretmemeli.
$slot     = take_listen_slot();
$crowded  = ($slot === null);
$deadline = microtime(true) + ($crowded ? SHORT_POLL_SECONDS : POLL_SECONDS);
while (true) {
    clearstatcache(true, $file);
    $size = is_file($file) ? (int) filesize($file) : 0;
    if ($size < $cursor) {
        $cursor = 0;   // dosya sifirlanmis
    }
    if ($size > $cursor || microtime(true) >= $deadline) {
        break;
    }
    usleep(POLL_SLEEP_US);
}

if (is_resource($slot)) {
    flock($slot, LOCK_UN);
    fclose($slot);
}

$out = '';
if ($size > $cursor) {
    $f = @fopen($file, 'rb');
    if ($f !== false) {
        flock($f, LOCK_SH);
        fseek($f, $cursor);
        while ($cursor < $size && strlen($out) < MAX_RESPONSE) {
            $head = fread($f, 8);
            if ($head === false || strlen($head) < 8) {
                break;
            }
            $u = unpack('Vlen/Vid', $head);
            $len = $u['len'];
            if ($len < 0 || $len > MAX_POST) {
                break;   // bozuk kayit: bastan basla
            }
            $data = $len > 0 ? fread($f, $len) : '';
            if ($data === false || strlen($data) < $len) {
                break;   // yazim yarida kalmis
            }
            $cursor += 8 + $len;
            if ($u['id'] !== $me) {
                $out .= $data;
            }
        }
        flock($f, LOCK_UN);
        fclose($f);
    }
}

header('Content-Type: application/octet-stream');
header('Cache-Control: no-store, no-cache');
// Tanı için: sunucu kaç saniye beklemeye hazırdı ve neden.
// "uzun" normal, "kalabalik" sınıra takıldı, "kilitsiz" mekanizma yok.
header('X-Telsiz-Bekleme: ' . ($crowded ? 'kalabalik' : ($slot === false ? 'kilitsiz' : 'uzun')));
header('Content-Length: ' . (8 + strlen($out)));
// cevap: [8 bayt yeni imlec][paketler]
echo pack('P', $cursor) . $out;
