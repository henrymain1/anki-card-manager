# Anki Card Manager — Proxy

Tiny Vercel Edge proxy used by the Android app. Three endpoints:

- `POST /api/v1beta/<anything>` — Gemini passthrough. The committed default forwards to **Vertex AI** (`aiplatform.googleapis.com`) using a service-account credential; a one-line switch in `api/gemini.js` reverts it to **AI Studio** (`generativelanguage.googleapis.com`) using a plain API key. Routed via `vercel.json` rewrite to `/api/gemini`.
- `GET  /api/tts?q=<text>&tl=yue` — forwards to Google Translate's TTS, returns MP3 bytes.
- `GET  /api/images/search?q=<keywords>&per_page=8&image_type=photo&orientation=horizontal&safesearch=true` — forwards to Pixabay's image search, injecting the `PIXABAY_API_KEY` from env. Returns Pixabay's JSON verbatim. Node runtime, no `maxDuration` (Pixabay is fast). The app decides all search policy and downloads photos straight from Pixabay's CDN, so image bytes don't pass through here.

Every endpoint requires a `Authorization: Bearer <PROXY_TOKEN>` header. The token is a shared secret you set in Vercel env vars and paste into the mobile app's Settings.

## Why

- Keeps your Gemini API key off the phone.
- Routes every Google call through Vercel's network, which sidesteps geo-blocks / VPN requirements on the device.

## Deploy

### 1. Get the code on GitHub

```bash
cd anki-card-manager-proxy
git init
git add .
git commit -m "Initial proxy"
gh repo create anki-card-manager-proxy --private --source=. --push
```

(Or use the GitHub web UI — create a repo and push this folder.)

### 2. Generate a strong proxy token

The token is the only thing keeping randos from using your API key. Use a long random string. Quick options:
- macOS / Linux: `openssl rand -hex 32`
- Windows PowerShell: `[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`
- Or any password manager — generate a 64-char passphrase.

Save this. You'll paste it into Vercel and into the mobile app's Settings.

### 3. Deploy on Vercel

1. Sign in at https://vercel.com (free Hobby plan is fine).
2. **Add New → Project** → import the GitHub repo.
3. Leave all defaults (no framework, no build step).
4. Before clicking Deploy, expand **Environment Variables** and add:
   - `PROXY_TOKEN` = the random string from step 2 (required)
   - `GCP_SA_JSON` = the full JSON of a Google Cloud service account with Vertex AI access — what the committed default backend uses. (Prefer the simpler AI Studio backend? Flip the switch documented in `api/gemini.js` and set `GEMINI_API_KEY` = your [AI Studio key](https://aistudio.google.com/apikey) instead.)
   - `PIXABAY_API_KEY` = your free [Pixabay key](https://pixabay.com/api/docs/) — optional; only the `/api/images/search` route needs it
5. Click **Deploy**.

After ~30 seconds you'll get a URL like `https://anki-card-manager-proxy.vercel.app`.

### 4. Smoke test

```bash
# Should return 401:
curl https://YOUR-PROJECT.vercel.app/api/v1beta/models

# Should return JSON listing available models:
curl -H "Authorization: Bearer YOUR_TOKEN" \
  https://YOUR-PROJECT.vercel.app/api/v1beta/models
```

If both pass, the proxy is live.

### 5. Plug into the mobile app

Open the Anki Card Manager app → **Settings**:
- **Proxy URL**: `https://YOUR-PROJECT.vercel.app`
- **Proxy Token**: the random string

Save. Generation now flows through the proxy.

## Updating

Push to the GitHub repo's main branch — Vercel auto-deploys on every push.

## Cost

Vercel Hobby tier is free for personal use. Each card generation is ~3 function invocations (text + image + TTS). Free tier allows 100,000+ invocations / month. You won't hit it.
