package com.konvar.tvhome;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * A one-page web form on the TV for typing an API key on your phone instead of with the remote. It runs only
 * while its QR code is on screen, answers only on the home network, and only at a random secret path.
 */
final class KeyServer {

    interface Listener {
        void onKey(String value);
    }

    private final String label;
    private final Listener listener;
    private final String secret;
    private ServerSocket socket;
    private volatile boolean running;

    KeyServer(String label, Listener listener) {
        this.label = label;
        this.listener = listener;
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        this.secret = sb.toString();
    }

    /** Starts listening; returns the address to put in the QR code, or null without a network address. */
    String start(Context c) throws Exception {
        String ip = localAddress();
        if (ip == null) return null;
        socket = new ServerSocket(0, 4, InetAddress.getByName(ip));
        running = true;
        Thread t = new Thread(this::serve, "key-server");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + socket.getLocalPort() + "/" + secret;
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    private void serve() {
        while (running) {
            try (Socket s = socket.accept()) {
                s.setSoTimeout(10000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String request = in.readLine();
                if (request == null) continue;
                int length = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                }
                String[] parts = request.split(" ");
                String path = parts.length > 1 ? parts[1] : "";
                if (!path.startsWith("/" + secret)) {
                    reply(s, "404 Not Found", "Not found");
                    continue;
                }
                if (request.startsWith("POST")) {
                    char[] body = new char[Math.min(length, 4096)];
                    int read = 0;
                    while (read < body.length) {
                        int n = in.read(body, read, body.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    String form = new String(body, 0, read);
                    String value = "";
                    for (String kv : form.split("&")) {
                        if (kv.startsWith("key=")) value = URLDecoder.decode(kv.substring(4), "UTF-8").trim();
                    }
                    reply(s, "200 OK", page(value.isEmpty() ? "Nothing was entered." : "Saved. You can close this page."));
                    String entered = value;
                    if (!entered.isEmpty()) Net.MAIN.post(() -> listener.onKey(entered));
                } else {
                    reply(s, "200 OK", page(null));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String page(String message) {
        String body = message != null ? "<p>" + message + "</p>"
                : "<form method='post'><input name='key' autofocus placeholder='Paste the key here' "
                + "autocomplete='off' autocapitalize='off' spellcheck='false'><button>Save on the TV</button></form>";
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Akari</title><style>body{font-family:system-ui,sans-serif;background:#0e0f13;color:#fff;"
                + "margin:0;padding:28px}h1{font-size:22px}input{width:100%;box-sizing:border-box;font-size:18px;"
                + "padding:14px;border-radius:12px;border:1px solid #555;background:#1c1d22;color:#fff}button{margin-top:14px;"
                + "width:100%;font-size:18px;padding:14px;border:0;border-radius:12px;background:#fff;color:#111}</style></head>"
                + "<body><h1>" + label + "</h1>" + body + "</body></html>";
    }

    private static void reply(Socket s, String status, String html) throws Exception {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /** The TV's own address on the home network (Wi-Fi or Ethernet). */
    private static String localAddress() throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        }
        return null;
    }
}
