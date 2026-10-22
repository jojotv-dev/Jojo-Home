import http from 'node:http';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { companionProxy } from './companion-proxy.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public', 'jojohome');
const MOUNT = '/jojohome/';
const value = (name, fallback) => { const index = process.argv.indexOf(name); return index >= 0 ? process.argv[index+1] : fallback; };
const host = value('--host', '127.0.0.1');
const port = Number(value('--port', '8096'));
const proxy = companionProxy(value('--companion', 'http://127.0.0.1:8095'));
if (!host || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Utilisation : node server.mjs --host 127.0.0.1 --port 8096');
const rootReal = await realpath(ROOT);
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpeg': 'image/jpeg', '.jpg': 'image/jpeg', '.ttf': 'font/ttf' };
const isInside = absolute => { const relative = path.relative(rootReal, absolute); return relative !== '' && relative !== '..' && !relative.startsWith('..'+path.sep) && !path.isAbsolute(relative); };

const server = http.createServer(async (request, response) => {
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Referrer-Policy', 'no-referrer');
  const send = (code, message) => { response.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' }); response.end(request.method === 'HEAD' ? undefined : message); };
  let url, pathname;
  try { url = new URL(request.url, 'http://localhost'); pathname = decodeURIComponent(url.pathname); } catch { return send(400, 'Adresse invalide.'); }
  if (proxy(request, response, url)) return;
  if (!['GET', 'HEAD'].includes(request.method)) { response.setHeader('Allow', 'GET, HEAD'); return send(405, 'Méthode non autorisée.'); }
  if (pathname === '/' || pathname === '/jojohome') { response.writeHead(302, { Location: MOUNT + url.search }); return response.end(); }
  if (pathname === MOUNT+'health') { response.writeHead(200, { 'Content-Type': 'application/json' }); return response.end(request.method === 'HEAD' ? undefined : '{"ok":true,"application":"jojohome-web"}'); }
  if (!pathname.startsWith(MOUNT) || pathname.includes('\\') || pathname.includes('\0')) return send(404, 'Page introuvable.');
  const relative = pathname === MOUNT ? 'index.html' : pathname.slice(MOUNT.length);
  const file = path.resolve(ROOT, relative);
  if (!isInside(file)) return send(404, 'Page introuvable.');
  try {
    const actual = await realpath(file);
    if (!isInside(actual)) return send(404, 'Page introuvable.');
    const info = await stat(actual); if (!info.isFile()) return send(404, 'Page introuvable.');
    const mime = types[path.extname(actual).toLowerCase()]; if (!mime) return send(404, 'Page introuvable.');
    const body = await readFile(actual);
    response.writeHead(200, { 'Content-Type': mime, 'Content-Length': body.length, 'Cache-Control': 'no-cache' });
    response.end(request.method === 'HEAD' ? undefined : body);
  } catch (error) {
    if (['ENOENT', 'ENOTDIR'].includes(error.code)) return send(404, 'Page introuvable.');
    console.error('Impossible de lire une ressource JojoHome :', error.code || error.name);
    send(500, 'Ressource indisponible.');
  }
});
server.on('error', error => { console.error('Le serveur JojoHome ne peut pas démarrer :', error.code || error.message); process.exitCode = 1; });
server.listen(port, host, () => console.log(`JojoHome local : http://${host}:${port}${MOUNT}`));
