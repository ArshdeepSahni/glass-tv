package com.gathin.glass.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny JSON-over-HTTPS client. Every call is on a worker thread; failures are exceptions so the caller falls through. */
final class Net {
    private Net() {
    }

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String body) {
            super("HTTP " + code + ": " + (body.length() > 160 ? body.substring(0, 160) : body));
            this.code = code;
        }
    }

    static JSONObject post(String url, String bearer, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("User-Agent", "GlassAssistant/1.0");
            if (url.contains("openrouter.ai")) c.setRequestProperty("X-Title", "Glass Assistant");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : read(in);
            if (code >= 400) throw new HttpError(code, text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** OpenAI-style chat completion (Groq, Cerebras, OpenRouter): returns the assistant text. */
    static String chat(String url, String key, String model, JSONArray messages, int maxTokens) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.4)
                .put("max_tokens", maxTokens);
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low");
        JSONObject r = post(url, key, body, 25000);
        String text = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        if (text.trim().isEmpty()) throw new IOException("empty answer");
        return text.trim();
    }

    /** Cloudflare Workers AI text generation: returns the response text. */
    static String cloudflare(String account, String token, String model, JSONArray messages, int maxTokens, int readTimeoutMs) throws Exception {
        String url = "https://api.cloudflare.com/client/v4/accounts/" + account + "/ai/run/" + model;
        JSONObject body = new JSONObject().put("messages", messages).put("max_tokens", maxTokens).put("temperature", 0.2);
        JSONObject r = post(url, token, body, readTimeoutMs);
        Object res = r.opt("result");
        String text;
        if (res instanceof JSONObject) {
            Object resp = ((JSONObject) res).opt("response");
            text = resp == null ? "" : resp.toString();
        } else {
            text = "";
        }
        if (text.trim().isEmpty()) throw new IOException("empty answer");
        return text.trim();
    }
}
