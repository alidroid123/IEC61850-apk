// Signs an RS256 JWT for the Firebase service account (env FIREBASE_SERVICE_ACCOUNT, the full
// JSON key as a string), exchanges it for an OAuth2 access token, and sends a data-only FCM push
// to the "app_updates" topic announcing the release in env RELEASE_TAG. Data-only (no
// "notification" block) so the app's AppFcmService always handles display itself instead of the
// OS auto-showing a bare system notification. Uses only Node's built-in `crypto`/`https` - no
// npm dependencies - since GitHub's hosted runners already have a recent Node available.
const crypto = require('crypto');
const https = require('https');

function base64url(input) {
    return Buffer.from(input).toString('base64')
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/, '');
}

function httpsPost(url, headers, body) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const req = https.request({
            hostname: u.hostname,
            path: u.pathname + u.search,
            method: 'POST',
            headers,
        }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => resolve({ status: res.statusCode, body: data }));
        });
        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

// FCM data-message values must be flat strings, and system notification trays clip very long
// text anyway - so the changelog is capped rather than sent in full. The in-app notification
// feed (AppNotifications, populated from the same push in AppFcmService) doesn't have this
// limit - only the push's own visible text is trimmed here.
const BODY_MAX_LENGTH = 300;

function buildBody(tag, releaseBody) {
    const trimmed = (releaseBody || '').trim();
    if (!trimmed) {
        return `Versi ${tag} sudah tersedia. Buka aplikasi untuk update.`;
    }
    if (trimmed.length <= BODY_MAX_LENGTH) return trimmed;
    return trimmed.slice(0, BODY_MAX_LENGTH - 1).trimEnd() + '…';
}

async function main() {
    const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
    const tag = process.env.RELEASE_TAG || 'terbaru';
    const version = tag.startsWith('v') ? tag.slice(1) : tag;
    const body = buildBody(tag, process.env.RELEASE_BODY);

    const now = Math.floor(Date.now() / 1000);
    const header = { alg: 'RS256', typ: 'JWT' };
    const claims = {
        iss: sa.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging',
        aud: sa.token_uri,
        iat: now,
        exp: now + 3600,
    };
    const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
    const signer = crypto.createSign('RSA-SHA256');
    signer.update(signingInput);
    const signature = signer.sign(sa.private_key);
    const jwt = `${signingInput}.${base64url(signature)}`;

    const tokenBody = `grant_type=${encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer')}&assertion=${encodeURIComponent(jwt)}`;
    const tokenResp = await httpsPost(sa.token_uri, { 'Content-Type': 'application/x-www-form-urlencoded' }, tokenBody);
    if (tokenResp.status < 200 || tokenResp.status >= 300) {
        throw new Error(`FCM token exchange failed (${tokenResp.status}): ${tokenResp.body}`);
    }
    const { access_token } = JSON.parse(tokenResp.body);

    const payload = JSON.stringify({
        message: {
            topic: 'app_updates',
            data: {
                title: 'Update Tersedia',
                body,
                version,
            },
        },
    });
    const sendResp = await httpsPost(
        `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
        { 'Content-Type': 'application/json', Authorization: `Bearer ${access_token}` },
        payload
    );
    console.log(`FCM send response (${sendResp.status}): ${sendResp.body}`);
    if (sendResp.status < 200 || sendResp.status >= 300) {
        process.exit(1);
    }
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
