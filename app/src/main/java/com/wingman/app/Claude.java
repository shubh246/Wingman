package com.wingman.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal Claude Messages API client (no libraries). Call from a background thread. */
public final class Claude {

    public static final class Suggestion {
        public final String text, why;
        Suggestion(String text, String why) { this.text = text; this.why = why; }
    }

    static final String[][] TONES = {
        {"natural", "😊 Natural", "Natural and easygoing, sounds like a real person texting."},
        {"playful", "😜 Playful", "Playful and light, with gentle teasing."},
        {"flirty", "😏 Flirty", "Flirty but tasteful and respectful. Read the room and don't escalate beyond where things already are."},
        {"witty", "🧠 Witty", "Witty and clever, a bit of humour."},
        {"curious", "❓ Question", "Curious: a genuine, specific question that's easy and fun to answer."},
        {"deep", "💬 Thoughtful", "Thoughtful and warm, a little more depth."},
        {"date", "☕ Ask out", "Move toward meeting up: suggest a specific, low-pressure plan."},
        {"exit", "🙏 Wind down", "Kindly and clearly wind things down: honest, no ghosting, no drama."}
    };

    private static final String SYSTEM =
        "You are a dating-app wingman. You write messages the user can send.\n"
      + "Rules:\n"
      + "- Write as the user, in first person. Sound like a real person texting: casual, usually 1-2 short sentences, no essays, no cliches, no canned pickup lines.\n"
      + "- Be specific: reference concrete details (a prompt answer, interest, photo caption, place, something they said).\n"
      + "- Match the language (including Hinglish etc.), formality and emoji use of the chat or profile.\n"
      + "- Each option takes a clearly different angle.\n"
      + "- Don't claim facts about the user that aren't in 'About me'; if a message needs one, use a [placeholder] in square brackets.\n"
      + "- Be honest and respectful: nothing manipulative, negging, guilt-tripping, sexual or pushy. If they seem uninterested or said no, respect it.\n"
      + "- The screen text is captured from an app and includes some UI noise (button labels, ages, distances). Ignore the noise.\n"
      + "Output ONLY a JSON array: [{\"text\": \"the message\", \"why\": \"5-10 word reason\"}]";

    static String toneText(String key) {
        for (String[] t : TONES) if (t[0].equals(key)) return t[2];
        return TONES[0][2];
    }

    /** mode = "profile" or "chat". */
    public static List<Suggestion> suggest(String apiKey, String model, String aboutMe, String language,
                                           String mode, String tone, String app, String screenText) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Open the Wingman app and add your Claude API key.");

        String user = buildPrompt(aboutMe, language, mode, tone, app, screenText);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 900);
        body.put("system", SYSTEM);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey.trim());
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            String msg = "Claude API error " + code;
            try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) {}
            throw new Exception(msg);
        }

        JSONArray content = new JSONObject(resp).getJSONArray("content");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.getJSONObject(i);
            if ("text".equals(b.optString("type"))) text.append(b.optString("text"));
        }
        String t = text.toString();
        int s = t.indexOf('['), e = t.lastIndexOf(']');
        if (s < 0 || e <= s) throw new Exception("Unexpected answer, tap Suggest again.");
        JSONArray arr = new JSONArray(t.substring(s, e + 1));
        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < 6; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("text").isEmpty()) out.add(new Suggestion(o.optString("text"), o.optString("why")));
        }
        return out;
    }

    static String buildPrompt(String aboutMe, String language, String mode, String tone, String app, String screenText) {
        StringBuilder u = new StringBuilder();
        u.append("App: ").append(app).append('\n');
        u.append("Vibe: ").append(toneText(tone)).append('\n');
        if (!aboutMe.isEmpty()) u.append("About me: ").append(aboutMe).append('\n');
        if (!language.isEmpty()) u.append("Write in: ").append(language).append('\n');
        if ("chat".equals(mode)) {
            u.append("\nThis is a chat. Lines start with Me: (the user), Them: (the match) or ?: (unclear, infer it). Oldest first.\n")
             .append("If the last message is from Me, write a natural follow-up or new thread.\n\n")
             .append(screenText)
             .append("\n\nWrite 4 options for my next message.");
        } else {
            u.append("\nThis is someone's dating profile, captured as I scrolled:\n\n")
             .append(screenText)
             .append("\n\nWrite 4 opening messages for them. Each should react to a different specific detail of the profile ")
             .append("(like replying to a prompt answer or photo), so it's easy for them to respond.");
        }
        return u.toString();
    }

    /** Prompt to paste into the Claude app: same rules, plain-text answer that's easy to copy. */
    static String appPrompt(String aboutMe, String language, String mode, String tone, String app, String screenText) {
        String rules = SYSTEM.substring(0, SYSTEM.indexOf("Output ONLY"));
        return rules
            + "Answer with just the 4 messages, numbered 1-4, each on its own line, no quotes and no extra commentary.\n\n"
            + buildPrompt(aboutMe, language, mode, tone, app, screenText);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }
}
