import express from 'express';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = process.env.PORT || 3000;

const KEYCLOAK_URL = process.env.KEYCLOAK_URL;
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM;
const MANAGEMENT_SERVICE_URL = process.env.MANAGEMENT_SERVICE_URL;

app.use(express.urlencoded({ extended: true }));
app.use(express.raw({ type: '*/*', limit: '200mb' }));
app.use(express.static(join(__dirname, 'public')));

app.get('/config', (req, res) => {
  res.json({
    defaultClientId: process.env.DEFAULT_CLIENT_ID,
    defaultClientSecret: process.env.DEFAULT_CLIENT_SECRET,
  });
});

app.post('/proxy/token', async (req, res) => {
  const { client_id, client_secret } = req.body;
  const tokenUrl = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`;

  try {
    const upstream = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'client_credentials', client_id, client_secret }),
    });
    res.status(upstream.status).json(await upstream.json());
  } catch (e) {
    res.status(502).json({ error: 'upstream_error', error_description: e.message });
  }
});

app.all('/proxy/api/*', async (req, res) => {
  const path = req.path.replace('/proxy/api', '/api');
  const url = `${MANAGEMENT_SERVICE_URL}${path}${req.url.includes('?') ? '?' + req.url.split('?')[1] : ''}`;

  const headers = {};
  for (const [k, v] of Object.entries(req.headers)) {
    if (['host', 'connection', 'transfer-encoding'].includes(k)) continue;
    headers[k] = v;
  }

  try {
    const upstream = await fetch(url, {
      method: req.method,
      headers,
      body: ['GET', 'HEAD', 'DELETE'].includes(req.method) ? undefined : req.body,
    });

    res.status(upstream.status);
    const ct = upstream.headers.get('content-type');
    if (ct) res.setHeader('content-type', ct);

    const text = await upstream.text();
    res.send(text);
  } catch (e) {
    res.status(502).json({ error: 'upstream_error', error_description: e.message });
  }
});

app.listen(PORT, () => {
  console.log(`Test client running on http://localhost:${PORT}`);
});
