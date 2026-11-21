package com.wingman.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Google Gemini API client (free key from aistudio.google.com). Call from a background thread. */
public final class Gemini {

    static final String DEFAULT_MODEL = "gemini-flash-latest";

    public static List<Claude.Suggestion> suggest(String apiKey, String model, String aboutMe, String language,
                                                  String mode, String tone, String app, String screenText) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Open the Wingman app and add your free Gemini key.");
        if (model == null || model.trim().isEmpty()) model = DEFAULT_MODEL;

        String user = Claude.buildPrompt(aboutMe, language, mode, tone, app, screenText);

        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", Claude.SYSTEM))));
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts",
                new JSONArray().put(new JSONObject().put("text", user)))));
        body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model.trim(), "UTF-8") + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-goog-api-key", apiKey.trim());
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = Claude.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            String msg = "Gemini error " + code;
            try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            if (code == 429) msg = "Gemini free limit reached for now. Wait a minute (or until tomorrow) and try again.";
            throw new Exception(msg);
        }

        JSONArray cands = new JSONObject(resp).optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("Gemini didn't answer (it may have blocked this). Try another vibe.");
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (!p.optBoolean("thought")) text.append(p.optString("text"));
        }
        return Claude.parseSuggestions(text.toString());
    }
}
