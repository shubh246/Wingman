package com.wingman.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Free AI providers with OpenAI-style APIs (Groq, OpenRouter, Cerebras, Mistral). */
public final class FreeAI {

    // id, label, base URL, page to get a free key
    static final String[][] PROVIDERS = {
        {"groq", "Groq (fast, 1,000 free requests/day)", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"},
        {"openrouter", "OpenRouter (free models)", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"},
        {"cerebras", "Cerebras (free tier)", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai"},
        {"mistral", "Mistral (free daily quota)", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys"}
    };

    static String[] provider(String id) {
        for (String[] p : PROVIDERS) if (p[0].equals(id)) return p;
        return PROVIDERS[0];
    }

    /** Runs on a background thread. Picks a model automatically when none is set or the saved one stops working. */
    public static List<Claude.Suggestion> suggest(SharedPreferences prefs, String mode, String tone, String app,
                                                  String screenText) throws Exception {
        String[] p = provider(prefs.getString("freeProvider", "groq"));
        String key = prefs.getString("freeKey", "").trim();
        if (key.isEmpty()) throw new Exception("Open the Wingman app and paste your free " + p[0] + " key.");
        String user = Claude.buildPrompt(prefs.getString("aboutMe", ""), prefs.getString("language", ""),
                mode, tone, app, screenText);

        String model = prefs.getString("freeModel", "").trim();
        if (model.isEmpty()) {
            model = pickModel(p, key);
            prefs.edit().putString("freeModel", model).apply();
        }
        try {
            return Claude.parseSuggestions(chat(p[2], key, model, user));
        } catch (ModelError e) {
            String fresh = pickModel(p, key);
            if (fresh.equals(model)) throw new Exception(e.getMessage());
            prefs.edit().putString("freeModel", fresh).apply();
            return Claude.parseSuggestions(chat(p[2], key, fresh, user));
        }
    }

    static final class ModelError extends Exception { ModelError(String m) { super(m); } }

    private static String chat(String base, String key, String model, String user) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 900);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", Claude.SYSTEM));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);

        HttpURLConnection c = open(base + "/chat/completions", key);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = Claude.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            String msg = "Error " + code;
            try {
                JSONObject err = new JSONObject(resp);
                JSONObject e = err.optJSONObject("error");
                msg = e != null ? e.optString("message", msg) : err.optString("message", msg);
            } catch (Exception ignored) { }
            if (code == 429) throw new Exception("Free limit reached for now. Wait a minute and try again.");
            if (code == 401 || code == 403) throw new Exception("The free key was refused. Check you pasted it fully. (" + msg + ")");
            if (code == 404 || ((code == 400 || code == 422) && msg.toLowerCase().contains("model")))
                throw new ModelError("Model problem: " + msg);
            throw new Exception(msg);
        }
        JSONArray choices = new JSONObject(resp).getJSONArray("choices");
        return choices.getJSONObject(0).getJSONObject("message").optString("content");
    }

    /** Asks the provider which models exist and picks a good free chat model. */
    static String pickModel(String[] p, String key) throws Exception {
        HttpURLConnection c = open(p[2] + "/models", key);
        int code = c.getResponseCode();
        String resp = Claude.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code == 401 || code == 403) throw new Exception("The free key was refused. Check you pasted it fully.");
        if (code >= 400) throw new Exception("Couldn't list models (error " + code + ").");
        JSONArray data = new JSONObject(resp).getJSONArray("data");
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            String id = m.optString("id");
            int s = score(p[0], id.toLowerCase());
            if (s > bestScore) { bestScore = s; best = id; }
        }
        if (best == null || bestScore < 0) throw new Exception("No free chat model found. Type a model name in Wingman settings.");
        return best;
    }

    private static int score(String provider, String id) {
        String[] skip = {"whisper", "tts", "speech", "audio", "embed", "guard", "moderation", "vision", "image",
                "ocr", "rerank", "transcribe", "realtime", "search", "safety", "prompt-guard", "codestral", "coder"};
        for (String s : skip) if (id.contains(s)) return -100;
        if (provider.equals("openrouter") && !id.endsWith(":free")) return -100;
        int s = 0;
        if (id.contains("llama")) s += 20;
        if (id.contains("70b") || id.contains("large") || id.contains("medium")) s += 15;
        if (id.contains("instruct") || id.contains("versatile") || id.contains("chat")) s += 10;
        if (id.contains("gemma") || id.contains("mistral") || id.contains("qwen") || id.contains("gpt-oss")) s += 8;
        if (id.contains("latest")) s += 5;
        if (id.contains("preview") || id.contains("8b") || id.contains("mini") || id.contains("tiny") || id.contains("1b")) s -= 5;
        return s;
    }

    private static HttpURLConnection open(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("HTTP-Referer", "https://github.com/shubh246/Wingman");
        c.setRequestProperty("X-Title", "Wingman");
        return c;
    }
}
