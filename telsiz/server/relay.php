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

$file = channel_file();
$me   = sender_hash();

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

$deadline = microtime(true) + POLL_SECONDS;
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
header('Content-Length: ' . (8 + strlen($out)));
// cevap: [8 bayt yeni imlec][paketler]
echo pack('P', $cursor) . $out;
