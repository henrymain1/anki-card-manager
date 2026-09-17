// Node.js serverless function — proxy for Google Translate's Cantonese TTS endpoint.
//
// Request:  GET  https://<your-proxy>.vercel.app/api/tts?q=<text>&tl=yue
// Response: MP3 audio bytes (audio/mpeg)
//
// Uses the `client=tw-ob` parameter, which doesn't require Google's `tk`
// token — same trick the gTTS Python library uses on desktop.

export const config = { maxDuration: 60 };

const UPSTREAM = 'https://translate.google.com/translate_tts';
const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

export default async function handler(req, res) {
  setCors(res);

  if (req.method === 'OPTIONS') {
    return res.status(204).end();
  }

  if (!checkAuth(req, res)) return;

  const incoming = new URL(req.url, `http://${req.headers.host}`);
  const q = incoming.searchParams.get('q');
  const tl = incoming.searchParams.get('tl') || 'yue';
  if (!q) return res.status(400).send('missing q');

  const upstream = new URL(UPSTREAM);
  upstream.searchParams.set('ie', 'UTF-8');
  upstream.searchParams.set('client', 'tw-ob');
  upstream.searchParams.set('tl', tl);
  upstream.searchParams.set('q', q.slice(0, 200)); // Google's per-request char limit

  const upstreamResp = await fetch(upstream.toString(), {
    headers: {
      'User-Agent': USER_AGENT,
      'Referer': 'https://translate.google.com/',
    },
  });

  if (!upstreamResp.ok) {
    return res.status(upstreamResp.status).send(`upstream ${upstreamResp.status}`);
  }

  res.status(200);
  res.setHeader('Content-Type', 'audio/mpeg');
  res.send(Buffer.from(await upstreamResp.arrayBuffer()));
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
