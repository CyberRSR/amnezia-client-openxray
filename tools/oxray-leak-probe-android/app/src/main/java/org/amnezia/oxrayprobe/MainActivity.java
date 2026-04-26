package org.amnezia.oxrayprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class MainActivity extends Activity {
    private static final String TAG = "OxrayLeakProbe";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int LOOP_DELAY_MS = 5_000;
    private static final int DEFAULT_ROUNDS = 120;

    private final SecureRandom random = new SecureRandom();
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("OXray leak probe is running. Read logcat tag OxrayLeakProbe.");
        view.setTextSize(16);
        view.setPadding(32, 32, 32, 32);
        setContentView(view);

        new Thread(this::runProbeLoop, "oxray-leak-probe").start();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        super.onDestroy();
    }

    private void runProbeLoop() {
        int rounds = getIntent().getIntExtra("rounds", DEFAULT_ROUNDS);
        String dnsSuffix = getIntent().getStringExtra("dns_suffix");
        String probeBaseUrl = getIntent().getStringExtra("probe_base_url");
        String testUrl = getIntent().getStringExtra("test_url");
        int testParallel = Math.max(1, getIntent().getIntExtra("test_parallel", 1));
        String dohUrl = getIntent().getStringExtra("doh_url");
        if (dohUrl == null || dohUrl.isBlank()) {
            dohUrl = "https://cloudflare-dns.com/dns-query";
        }
        String dotHost = getIntent().getStringExtra("dot_host");
        int dotPort = getIntent().getIntExtra("dot_port", 853);

        Log.i(TAG, "probe started rounds=" + rounds
            + " dns_suffix=" + safeValue(dnsSuffix)
            + " probe_base_url=" + safeValue(probeBaseUrl)
            + " test_url=" + safeValue(testUrl)
            + " test_parallel=" + testParallel
            + " doh_url=" + safeValue(dohUrl)
            + " dot_host=" + safeValue(dotHost)
            + " dot_port=" + dotPort);

        for (int round = 1; !stopped && round <= rounds; round++) {
            String nonce = makeNonce(round);
            Log.i(TAG, "round=" + round + " nonce=" + nonce + " begin");
            probeHttp("ipify4", "https://api.ipify.org?format=json");
            probeHttp("ipify6", "https://api6.ipify.org?format=json");
            probeHttp("ipify64", "https://api64.ipify.org?format=json");
            if (probeBaseUrl != null && !probeBaseUrl.isBlank()) {
                probeHttp("vps-http", appendQuery(probeBaseUrl, "nonce=" + nonce + "&kind=http"));
            }
            if (testUrl != null && !testUrl.isBlank()) {
                probeHttp("test-url", testUrl);
                probeParallelHttp("test-url-parallel", testUrl, testParallel);
            }
            String randomDomain = randomDomain(nonce, dnsSuffix);
            if (randomDomain != null) {
                probeSystemDns(randomDomain);
                probeDoH(dohUrl, randomDomain);
                if (dotHost != null && !dotHost.isBlank()) {
                    probeDoT(dotHost, dotPort, randomDomain);
                } else {
                    Log.i(TAG, "dot skipped: provide --es dot_host <host>");
                }
            } else {
                Log.i(TAG, "dns probes skipped: provide --es dns_suffix <domain>");
            }
            sleep(LOOP_DELAY_MS);
        }
        Log.i(TAG, "probe finished");
    }

    private void probeHttp(String label, String url) {
        HttpsURLConnection connection = null;
        long started = System.currentTimeMillis();
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "OxrayLeakProbe/1.0");
            int code = connection.getResponseCode();
            String body = readLimited(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            Log.i(TAG, label + " ok code=" + code
                + " ms=" + elapsed(started)
                + " body=" + oneLine(body));
        } catch (Exception e) {
            Log.w(TAG, label + " failed ms=" + elapsed(started) + " error=" + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void probeParallelHttp(String label, String url, int count) {
        if (count <= 1) {
            return;
        }
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            final int index = i + 1;
            threads[i] = new Thread(() -> probeHttp(label + "-" + index, url), label + "-" + index);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                return;
            }
        }
    }

    private void probeSystemDns(String host) {
        long started = System.currentTimeMillis();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            StringBuilder builder = new StringBuilder();
            for (InetAddress address : addresses) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(address.getHostAddress());
            }
            Log.i(TAG, "system-dns ok host=" + host + " ms=" + elapsed(started) + " addresses=" + builder);
        } catch (Exception e) {
            Log.w(TAG, "system-dns failed host=" + host + " ms=" + elapsed(started)
                + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void probeDoH(String dohUrl, String host) {
        String separator = dohUrl.contains("?") ? "&" : "?";
        String url = dohUrl + separator + "name=" + host + "&type=A";
        HttpsURLConnection connection = null;
        long started = System.currentTimeMillis();
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/dns-json");
            int code = connection.getResponseCode();
            String body = readLimited(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            Log.i(TAG, "doh ok host=" + host + " code=" + code
                + " ms=" + elapsed(started)
                + " body=" + oneLine(body));
        } catch (Exception e) {
            Log.w(TAG, "doh failed host=" + host + " ms=" + elapsed(started)
                + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void probeDoT(String dotHost, int dotPort, String host) {
        long started = System.currentTimeMillis();
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket(dotHost, dotPort)) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.startHandshake();
                byte[] query = buildDnsQuery(host);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();

                DataInputStream input = new DataInputStream(socket.getInputStream());
                int length = input.readUnsignedShort();
                byte[] response = new byte[Math.min(length, 512)];
                input.readFully(response);
                Log.i(TAG, "dot ok host=" + host + " server=" + dotHost + ":" + dotPort
                    + " response_len=" + length + " ms=" + elapsed(started));
            }
        } catch (Exception e) {
            Log.w(TAG, "dot failed host=" + host + " server=" + dotHost + ":" + dotPort
                + " ms=" + elapsed(started)
                + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private byte[] buildDnsQuery(String host) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = random.nextInt(0xffff);
        out.write((id >> 8) & 0xff);
        out.write(id & 0xff);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        for (String label : host.split("\\.")) {
            byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            if (labelBytes.length == 0 || labelBytes.length > 63) {
                throw new IllegalArgumentException("invalid DNS label in " + host);
            }
            out.write(labelBytes.length);
            out.write(labelBytes);
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        return out.toByteArray();
    }

    private String readLimited(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        byte[] buffer = new byte[1024];
        int read = input.read(buffer);
        if (read <= 0) {
            return "";
        }
        return new String(buffer, 0, read, StandardCharsets.UTF_8);
    }

    private String randomDomain(String nonce, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        return nonce + "." + suffix.replaceAll("^\\.+|\\.+$", "");
    }

    private String makeNonce(int round) {
        return "oxray-" + System.currentTimeMillis() + "-" + round + "-" + random.nextInt(100_000);
    }

    private String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "<unset>" : value;
    }

    private long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }
}
