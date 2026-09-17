// Node.js serverless function — proxy for Pixabay image search.
//
// Request:  GET /api/images/search?q=<keywords>&per_page=8&image_type=photo
//               &orientation=horizontal&safesearch=true
// Response: Pixabay's JSON verbatim (status + body passthrough).
//
// A dumb passthrough: forwards the app's query params and injects the
// server-side Pixabay key so it never touches the phone. All search policy
// (photo-only, horizontal, safesearch) is decided app-side; this route just
// relays whatever it's given. The app downloads webformatURL straight from
// Pixabay's CDN, so image bytes never flow through this function.
//
// No `maxDuration` on purpose: Pixabay answers in a few hundred ms, unlike the
// Gemini image routes that need the long-timeout config.

export default async function handler(req, res) {
  setCors(res);

  if (req.method === 'OPTIONS') {
    return res.status(204).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  if (!checkAuth(req, res)) return;

  if (!process.env.PIXABAY_API_KEY) {
    return res.status(500).send('Server misconfigured: PIXABAY_API_KEY not set');
  }

  const incoming = new URL(req.url, `http://${req.headers.host}`);
  const q = incoming.searchParams.get('q');
  if (!q) return res.status(400).json({ error: 'Missing q' });

  const upstream = new URL('https://pixabay.com/api/');
  upstream.searchParams.set('key', process.env.PIXABAY_API_KEY);
  upstream.searchParams.set('q', q);
  // per_page must stay in Pixabay's 3–200 range or it 400s. The app sends 8.
  upstream.searchParams.set('per_page', incoming.searchParams.get('per_page') || '8');
  upstream.searchParams.set('image_type', incoming.searchParams.get('image_type') || 'photo');
  upstream.searchParams.set('orientation', incoming.searchParams.get('orientation') || 'horizontal');
  upstream.searchParams.set('safesearch', incoming.searchParams.get('safesearch') || 'true');

  console.log(`[req] ▶ GET pixabay q="${q}"`);

  const upstreamResp = await fetch(upstream.toString());
  const body = await upstreamResp.text();

  console.log(`[req] · upstream ${upstreamResp.status} · ${body.length}B`);

  res.status(upstreamResp.status);
  res.setHeader('Content-Type', 'application/json');
  res.send(body);
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
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
}
