// 轻量下载器：跟随重定向、断点续传、进度输出。仅用 Node 内置模块，避免 PowerShell 的 TLS 问题。
// 用法: node scripts/download.mjs <url> <output-path>
import fs from "node:fs";
import https from "node:https";
import path from "node:path";

const [url, out] = process.argv.slice(2);
if (!url || !out) {
  console.error("usage: node download.mjs <url> <output>");
  process.exit(2);
}

fs.mkdirSync(path.dirname(out), { recursive: true });
const part = out + ".part";

function open(target, redirects = 8) {
  return new Promise((resolve, reject) => {
    const req = https.get(target, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode)) {
        res.resume();
        if (redirects <= 0) return reject(new Error("too many redirects"));
        if (!res.headers.location) return reject(new Error("redirect without location"));
        resolve(open(new URL(res.headers.location, target).toString(), redirects - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${target}`));
      }
      resolve(res);
    });
    req.on("error", reject);
    req.setTimeout(60000, () => req.destroy(new Error("timeout")));
  });
}

async function main() {
  const existing = fs.existsSync(part) ? fs.statSync(part).size : 0;
  const headers = existing > 0 ? { Range: `bytes=${existing}-` } : {};
  const res = await openWithHeaders(url, headers);
  if (res.statusCode === 416) {
    fs.renameSync(part, out);
    console.log("already complete:", out);
    return;
  }
  const total = Number(res.headers["content-length"] || 0) + (res.statusCode === 206 ? existing : 0);
  const stream = fs.createWriteStream(part, { flags: existing > 0 ? "a" : "w" });
  let received = res.statusCode === 206 ? existing : 0;
  let lastLog = 0;
  const started = Date.now();

  res.on("data", (chunk) => {
    received += chunk.length;
    const mb = received / 1048576;
    if (mb - lastLog >= 25) {
      lastLog = mb;
      const sec = Math.max(1, (Date.now() - started) / 1000);
      const pct = total ? ((received / total) * 100).toFixed(1) : "?";
      console.log(`  ${mb.toFixed(0)} MB / ${total ? (total / 1048576).toFixed(0) : "?"} MB (${pct}%) ${(mb / sec).toFixed(1)} MB/s`);
    }
  });
  res.pipe(stream);
  await new Promise((resolve, reject) => {
    stream.on("finish", resolve);
    stream.on("error", reject);
    res.on("error", reject);
  });
  fs.renameSync(part, out);
  console.log(`done: ${out} (${(fs.statSync(out).size / 1048576).toFixed(1)} MB, ${((Date.now() - started) / 1000).toFixed(0)}s)`);
}

function openWithHeaders(target, headers, redirects = 8) {
  return new Promise((resolve, reject) => {
    const req = https.get(target, { headers }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode)) {
        res.resume();
        if (redirects <= 0) return reject(new Error("too many redirects"));
        if (!res.headers.location) return reject(new Error("redirect without location"));
        resolve(openWithHeaders(new URL(res.headers.location, target).toString(), headers, redirects - 1));
        return;
      }
      resolve(res);
    });
    req.on("error", reject);
    req.setTimeout(60000, () => req.destroy(new Error("timeout")));
  });
}

main().catch((e) => {
  console.error("FAILED:", e.message);
  process.exit(1);
});
