import http from 'node:http';

// The destination is set at startup, never taken from a browser request.
export function companionProxy(origin) {
  const upstream = new URL(origin);
  if (upstream.protocol !== 'http:' || !['127.0.0.1', '192.168.1.32'].includes(upstream.hostname) ||
      upstream.username || upstream.password || upstream.pathname !== '/' || upstream.search || upstream.hash) {
    throw new Error('Adresse du Companion invalide.');
  }
  return (request, response, url) => {
    const dashboard = url.pathname === '/jojohome/companion/';
    if (!dashboard && !url.pathname.startsWith('/api/')) return false;
    if (dashboard && !['GET', 'HEAD'].includes(request.method)) {
      response.writeHead(405); response.end(); return true;
    }
    if (request.headers.origin && request.headers.origin !== 'http://' + request.headers.host) {
      response.writeHead(403); response.end('Origine non autorisée.'); return true;
    }
    const headers = {};
    for (const key of ['authorization', 'content-type', 'content-length', 'range', 'if-range', 'if-none-match', 'accept']) {
      if (request.headers[key]) headers[key] = request.headers[key];
    }
    const outgoing = http.request(upstream, {
      method: request.method, path: dashboard ? '/' : url.pathname + url.search, headers
    }, incoming => {
      const selected = {};
      for (const key of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control', 'etag', 'last-modified']) {
        if (incoming.headers[key]) selected[key] = incoming.headers[key];
      }
      selected['cache-control'] = dashboard ? 'no-store' : selected['cache-control'] || 'no-store';
      response.writeHead(incoming.statusCode, selected);
      incoming.pipe(response);
      response.on('close', () => incoming.destroy());
    });
    outgoing.setTimeout(60_000, () => outgoing.destroy());
    outgoing.on('error', () => {
      if (response.headersSent) return response.destroy();
      response.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      response.end('{"error":"Le Companion NAS est momentanément indisponible."}');
    });
    request.on('aborted', () => outgoing.destroy());
    response.on('close', () => { if (!response.writableFinished) outgoing.destroy(); });
    request.pipe(outgoing);
    return true;
  };
}
