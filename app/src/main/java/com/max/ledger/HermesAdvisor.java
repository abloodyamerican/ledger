package com.max.ledger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Sends the budget summary to Hermes Agent on Max's own computer, through its
 * OpenAI-compatible API server (POST /v1/chat/completions), over Tailscale or home Wi-Fi.
 * Only private addresses are allowed, so the key and budget never go to the open internet.
 */
final class HermesAdvisor {

    static final String PATH = "/v1/chat/completions";

    /** "" if the address is usable, otherwise why not. */
    static String checkUrl(String url) {
        try {
            URI u = new URI(url == null ? "" : url.trim());
            String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!sch.equals("http") && !sch.equals("https")) return "Start the address with http://";
            String host = u.getHost();
            if (host == null || host.isEmpty()) return "Add the computer's address, like http://100.101.102.103:8642";
            if (!privateHost(host.toLowerCase())) return "Use your computer's Tailscale address (100.x.x.x or a .ts.net name) or a home network address.";
            if (u.getQuery() != null || u.getFragment() != null || u.getUserInfo() != null) return "Leave out anything after the address and port.";
            return "";
        } catch (Exception e) {
            return "That address doesn't look right.";
        }
    }

    /** Tailscale (100.64.0.0/10, *.ts.net) or a home network address. */
    static boolean privateHost(String h) {
        if (h.endsWith(".ts.net")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] n = new int[4];
        try { for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(p[i]); if (n[i] < 0 || n[i] > 255) return false; } }
        catch (NumberFormatException e) { return false; }
        return (n[0] == 100 && n[1] >= 64 && n[1] <= 127)
                || n[0] == 10
                || (n[0] == 192 && n[1] == 168)
                || (n[0] == 172 && n[1] >= 16 && n[1] <= 31);
    }

    /** The full endpoint: the saved address (which may carry a path such as /p/finance-agent) + PATH. */
    static String endpoint(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith(PATH)) return b;
        if (b.endsWith("/v1")) b = b.substring(0, b.length() - 3);
        return b + PATH;
    }

    static ClaudeAdvisor.Result ask(String baseUrl, String key, String budgetJson) {
        String bad = checkUrl(baseUrl);
        if (!bad.isEmpty()) return new ClaudeAdvisor.Result(false, bad);
        return post(endpoint(baseUrl), key, budgetJson);
    }

    /** The request itself; callers check the address first. */
    static ClaudeAdvisor.Result post(String endpoint, String key, String budgetJson) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject()
                    .put("model", "hermes-agent")
                    .put("stream", false)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", ClaudeAdvisor.SYSTEM))
                            .put(new JSONObject().put("role", "user")
                                    .put("content", "Here is my budget. What should I change?\n\n" + budgetJson)));
            c = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(240_000);   // Hermes may use its tools before answering
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401 || code == 403) return new ClaudeAdvisor.Result(false, "Hermes didn't accept the key. Check it matches API_SERVER_KEY on your computer.");
            if (code == 404) return new ClaudeAdvisor.Result(false, "Hermes answered but has no chat endpoint there. Check the address and that the API server is on.");
            if (code == 429) return new ClaudeAdvisor.Result(false, "Hermes is busy with other requests. Try again in a minute.");
            if (code >= 400) return new ClaudeAdvisor.Result(false, "Hermes returned an error (" + code + ").");
            JSONObject r = new JSONObject(text);
            JSONObject choice = r.getJSONArray("choices").getJSONObject(0);
            String finish = choice.optString("finish_reason", "stop");
            String answer = choice.getJSONObject("message").optString("content", "").trim();
            if ("error".equals(finish) || "cancelled".equals(finish))
                return new ClaudeAdvisor.Result(false, answer.isEmpty() ? "Hermes stopped before answering. Try again." : answer);
            if (answer.isEmpty()) return new ClaudeAdvisor.Result(false, "Hermes sent back an empty answer. Try again.");
            return new ClaudeAdvisor.Result(true, answer);
        } catch (ConnectException | UnknownHostException e) {
            return new ClaudeAdvisor.Result(false, "Couldn't reach Hermes. Check the computer is on, Hermes is running and Tailscale is connected on both.");
        } catch (SocketTimeoutException e) {
            return new ClaudeAdvisor.Result(false, "Hermes took too long to answer. Try again.");
        } catch (Exception e) {
            return new ClaudeAdvisor.Result(false, "Something went wrong asking Hermes: " + e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 2_000_000) break; }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private HermesAdvisor() {}
}
