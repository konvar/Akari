package com.konvar.tvhome;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Keeps the PKCE verifier on the TV while the user authorizes in a phone browser. */
final class TraktSignIn {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final long TIMEOUT_MS = 10 * 60_000L;

    private TraktSignIn() {
    }

    static boolean isRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            return BASE64_URL.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void show(Activity activity, Runnable onConnected) {
        String clientId = Trakt.clientId();
        String redirect = Trakt.redirectUri();
        if (clientId.isEmpty() || !isRedirectUri(redirect)) {
            Toast.makeText(activity, R.string.trakt_redirect_hint, Toast.LENGTH_LONG).show();
            return;
        }
        String verifier = randomValue();
        String state = randomValue();
        String title = activity.getString(R.string.trakt_connect);
        QrDialog[] dialog = new QrDialog[1];
        boolean[] exchanging = {false};
        String authorize = Uri.parse(Trakt.AUTH + "/oauth/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", "public offline_access")
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .build().toString();
        KeyServer server = new KeyServer(title, authorize, value -> {
            QrDialog qr = dialog[0];
            if (!qr.dialog.isShowing() || exchanging[0]) return;
            String code = authorizationCode(value, redirect, state);
            if (code == null) {
                qr.status(activity.getString(R.string.trakt_bad_redirect));
                return;
            }
            exchanging[0] = true;
            Net.IO.execute(() -> {
                try {
                    JSONObject tokens = Trakt.exchangeCode(clientId, redirect, code, verifier);
                    Net.MAIN.post(() -> {
                        if (!qr.dialog.isShowing() || activity.isDestroyed()) return;
                        try {
                            if (!clientId.equals(Trakt.clientId())) throw new IllegalStateException();
                            Trakt.store(activity, tokens, redirect);
                        } catch (Exception e) {
                            fail(activity, qr, R.string.trakt_exchange_failed);
                            return;
                        }
                        qr.dialog.dismiss();
                        Toast.makeText(activity, R.string.trakt_connected, Toast.LENGTH_SHORT).show();
                        onConnected.run();
                    });
                } catch (Exception e) {
                    Net.MAIN.post(() -> fail(activity, qr, R.string.trakt_exchange_failed));
                }
            });
        });
        try {
            String address = server.start(activity);
            if (address == null) {
                Toast.makeText(activity, R.string.offline, Toast.LENGTH_SHORT).show();
                return;
            }
            QrDialog qr = new QrDialog(activity, title, activity.getString(R.string.trakt_pkce_hint), address);
            dialog[0] = qr;
            Runnable expire = () -> fail(activity, qr, R.string.trakt_sign_in_expired);
            qr.dialog.setOnDismissListener(ignored -> {
                server.stop();
                Net.MAIN.removeCallbacks(expire);
            });
            qr.show();
            Net.MAIN.postDelayed(expire, TIMEOUT_MS);
        } catch (Exception e) {
            server.stop();
            Toast.makeText(activity, R.string.trakt_exchange_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String authorizationCode(String value, String redirect, String state) {
        String prefix = redirect + (redirect.contains("?") ? "&" : "?");
        if (!value.startsWith(prefix)) return null;
        Uri uri = Uri.parse(value);
        if (uri.getFragment() != null || uri.getQueryParameter("error") != null) return null;
        if (!uri.getQueryParameters("state").equals(Collections.singletonList(state))) return null;
        List<String> codes = uri.getQueryParameters("code");
        return codes.size() == 1 && !codes.get(0).isEmpty() ? codes.get(0) : null;
    }

    private static void fail(Activity activity, QrDialog qr, int message) {
        if (!qr.dialog.isShowing() || activity.isDestroyed()) return;
        qr.dialog.dismiss();
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
