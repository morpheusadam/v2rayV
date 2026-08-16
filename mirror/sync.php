<?php
/**
 * Pulls the subscription lists into this directory so Apache can serve them as plain files.
 *
 * Run from cron, never from a web request. That is the whole design: a proxy script would
 * put PHP on the path of every fetch, which on shared hosting means the execution limits,
 * the memory limits and the concurrency limits of the cheapest plan sit between the user and
 * their server list. Static files sidestep all of it — Apache serves them at full speed, the
 * mirror keeps working if PHP is disabled or the account hits a process cap, and there is no
 * request-time code to have a bug in.
 *
 * The cost is that only a known list of files is mirrored rather than anything under the
 * repository. For this repository that is not a cost at all: the list below is all of it.
 *
 * Deploy:
 *   1. Put this file OUTSIDE public_html, or it can be run from a browser.
 *   2. Cron, every 30 minutes:
 *        /usr/bin/php /home/USER/cron/sync.php >> /home/USER/cron/sync.log 2>&1
 *
 * It writes atomically — download to a temporary file, then rename — so a fetch that arrives
 * mid-sync gets either the old file or the new one, never half of either.
 */

declare(strict_types=1);

/** Where Apache serves from. */
const TARGET_DIR = '/home/u523965318/domains/lavzen.com/public_html/cdn';

const UPSTREAM = 'https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/';

/**
 * Paths relative to the repository root, mirrored to the same relative path here. Keeping the
 * layout identical means the app's mirror URL is the upstream URL with the host swapped, so
 * there is one rewrite rule in the client rather than a mapping table to keep in step.
 */
const FILES = [
    'subs/bundles/best.txt',
    'subs/all.txt',
    'proxies/all.txt',
    'app/notice.json',
];

/** A file that fails to download is left alone. A stale list beats no list. */
const TIMEOUT_SECONDS = 60;

/** Anything smaller than this is treated as an error page rather than a list. */
const MIN_PLAUSIBLE_BYTES = 32;

function log_line(string $message): void
{
    echo '[' . gmdate('Y-m-d H:i:s') . 'Z] ' . $message . PHP_EOL;
}

function fetch(string $url): ?string
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 3,
        CURLOPT_TIMEOUT => TIMEOUT_SECONDS,
        CURLOPT_CONNECTTIMEOUT => 20,
        CURLOPT_USERAGENT => 'v2rayV-mirror-sync',
        CURLOPT_FAILONERROR => true,
    ]);
    $body = curl_exec($ch);
    $error = curl_error($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    if ($body === false || $body === '') {
        log_line("FAIL $url — " . ($error !== '' ? $error : "HTTP $status"));
        return null;
    }
    if (strlen($body) < MIN_PLAUSIBLE_BYTES) {
        log_line('FAIL ' . $url . ' — only ' . strlen($body) . ' bytes, treating as an error page');
        return null;
    }
    return $body;
}

$failures = 0;

foreach (FILES as $relative) {
    $destination = TARGET_DIR . '/' . $relative;
    $directory = dirname($destination);

    if (!is_dir($directory) && !mkdir($directory, 0755, true) && !is_dir($directory)) {
        log_line("FAIL $relative — could not create $directory");
        $failures++;
        continue;
    }

    $body = fetch(UPSTREAM . $relative);
    if ($body === null) {
        $failures++;
        continue;
    }

    // Unchanged content is not rewritten, so the file's mtime keeps meaning "when this last
    // actually changed" rather than "when cron last ran".
    if (is_file($destination) && hash('sha256', $body) === hash_file('sha256', $destination)) {
        log_line("same $relative (" . strlen($body) . ' bytes)');
        continue;
    }

    $temporary = $destination . '.tmp.' . getmypid();
    if (file_put_contents($temporary, $body) === false) {
        log_line("FAIL $relative — could not write $temporary");
        $failures++;
        continue;
    }
    // Atomic on the same filesystem: a reader sees the old file or the new one, never a
    // partial write. This is why it is not written to the destination directly.
    if (!rename($temporary, $destination)) {
        log_line("FAIL $relative — could not move into place");
        @unlink($temporary);
        $failures++;
        continue;
    }
    @chmod($destination, 0644);
    log_line("updated $relative (" . strlen($body) . ' bytes)');
}

log_line($failures === 0 ? 'done' : "done with $failures failure(s)");
exit($failures === 0 ? 0 : 1);
