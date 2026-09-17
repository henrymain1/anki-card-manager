// Node.js serverless function — proxy for the Gemini API via Vertex AI.
//
// Handles every request whose URL begins with /api/v1beta/ (routed here via
// the rewrite rule in vercel.json). The original path is preserved on
// `req.url`, so we strip our prefix and forward the rest upstream.
//
// The mobile client never sees the API credentials. Auth is gated on a shared
// bearer token (PROXY_TOKEN env var).
//
// Backend: Vertex AI (Google Cloud). The service-account JSON is stored in
// the GCP_SA_JSON env var. AI Studio code is commented out below — search for
// "AI STUDIO fallback" to find all the pieces needed to revert.

import { GoogleAuth } from 'google-auth-library';

export const config = { maxDuration: 60 };

// --- Vertex AI backend (active) ---
const sa = JSON.parse(process.env.GCP_SA_JSON || '{}');
const auth = new GoogleAuth({
  credentials: sa,
  scopes: ['https://www.googleapis.com/auth/cloud-platform'],
});

async function getVertexAuth() {
  const client = await auth.getClient();
  const { token } = await client.getAccessToken();
  return `Bearer ${token}`;
}

function buildVertexUrl(modelAndMethod) {
  return `https://aiplatform.googleapis.com/v1/projects/${sa.project_id}` +
         `/locations/global/publishers/google/models/${modelAndMethod}`;
}

// --- AI STUDIO fallback (disabled) ---
// To revert ALL traffic to AI Studio:
//   1. Uncomment the UPSTREAM_BASE line below
//   2. In handler(), uncomment the "AI STUDIO" block and comment out the "VERTEX" block
//   3. Set GEMINI_API_KEY in your env vars
//
// const UPSTREAM_BASE = 'https://generativelanguage.googleapis.com';

export default async function handler(req, res) {
  setCors(res);

  if (req.method === 'OPTIONS') {
    return res.status(204).end();
  }

  if (!checkAuth(req, res)) return;

  // Build upstream URL. Strip our /api/ prefix so /api/v1beta/foo → /v1beta/foo.
  const incoming = new URL(req.url, `http://${req.headers.host}`);
  const upstreamPath = incoming.pathname.replace(/^\/api\//, '/');

  // --- VERTEX: extract model+method from /v1beta/models/{model}:{method} ---
  const match = upstreamPath.match(/\/v1beta\/models\/(.+)$/);
  if (!match) {
    return res.status(400).send(`Cannot parse model from path: ${upstreamPath}`);
  }
  const modelAndMethod = match[1];
  const upstream = new URL(buildVertexUrl(modelAndMethod));

  // Forward query params (e.g., alt=sse), but strip Vercel-injected ones.
  for (const [k, v] of incoming.searchParams) {
    if (k === 'path' || k.startsWith('nxtP')) continue;
    upstream.searchParams.set(k, v);
  }

  const authHeader = await getVertexAuth();

  // --- AI STUDIO fallback (disabled) ---
  // const upstream = new URL(UPSTREAM_BASE + upstreamPath);
  // for (const [k, v] of incoming.searchParams) {
  //   if (k === 'path' || k.startsWith('nxtP')) continue;
  //   upstream.searchParams.set(k, v);
  // }
  // upstream.searchParams.set('key', process.env.GEMINI_API_KEY);

  const init = {
    method: req.method,
    headers: {
      'Content-Type': req.headers['content-type'] || 'application/json',
      'Authorization': authHeader,  // Vertex: Bearer token; AI Studio would use ?key= instead
    },
  };
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    init.body = await readBody(req);
  }

  // Vertex requires an explicit role on every content item; AI Studio defaulted
  // missing roles to "user". Patch the body so the app doesn't need to change.
  if (init.body) {
    try {
      const parsed = JSON.parse(init.body);
      let patched = false;
      for (const c of parsed.contents || []) {
        if (!c.role) { c.role = 'user'; patched = true; }
      }
      if (patched) init.body = JSON.stringify(parsed);
    } catch {}
  }

  console.log(`[req] ▶ ${req.method} ${upstream.toString()}`);

  const upstreamResp = await fetch(upstream.toString(), init);

  const contentType = upstreamResp.headers.get('content-type') || 'application/json';
  const transferEncoding = upstreamResp.headers.get('transfer-encoding') || '(none)';
  console.log(`[req] · upstream ${upstreamResp.status} · content-type=${contentType} · transfer-encoding=${transferEncoding}`);
  res.status(upstreamResp.status);
  res.setHeader('Content-Type', contentType);

  const isStream = contentType.includes('text/event-stream');
  if (isStream) {
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');
    if (typeof res.flushHeaders === 'function') res.flushHeaders();
  }

  if (!upstreamResp.body) {
    res.end();
    return;
  }

  if (isStream) {
    const reqId = Math.random().toString(36).slice(2, 8);
    const t0 = Date.now();
    let chunkCount = 0;
    let totalBytes = 0;
    console.log(`[stream ${reqId}] ▶ begin · upstream status ${upstreamResp.status}`);

    const reader = upstreamResp.body.getReader();
    (async () => {
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          chunkCount++;
          totalBytes += value.byteLength;
          const elapsed = Date.now() - t0;
          const preview = Buffer.from(value).toString('utf8').slice(0, 80).replace(/\n/g, '\\n');
          console.log(`[stream ${reqId}] · chunk #${chunkCount} · +${elapsed}ms · ${value.byteLength}B · "${preview}"`);
          res.write(value);
          if (typeof res.flush === 'function') res.flush();
        }
        console.log(`[stream ${reqId}] ✓ done · ${chunkCount} chunks · ${totalBytes}B · ${Date.now() - t0}ms`);
        res.end();
      } catch (err) {
        console.log(`[stream ${reqId}] ✗ error · ${Date.now() - t0}ms · ${err?.message || err}`);
        try { res.end(); } catch {}
      }
    })();
    return;
  }

  const buf = Buffer.from(await upstreamResp.arrayBuffer());
  res.send(buf);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function checkAuth(req, res) {
  const token = process.env.PROXY_TOKEN;
  if (!token) {
    res.status(500).send('Server misconfigured: PROXY_TOKEN not set');
    return false;
  }
  if (req.headers['authorization'] !== `Bearer ${token}`) {
    res.status(401).send('Unauthorized');
    return false;
  }
  return true;
}

function setCors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
}
