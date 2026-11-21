package com.wingman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

import java.util.ArrayList;
import java.util.List;

/**
 * On-phone AI (Gemini Nano). Google only runs it for the app on screen, so this is a small
 * see-through panel that opens over the dating app when you tap the bubble.
 */
public class NanoActivity extends Activity {

    // FeatureStatus values from ML Kit GenAI.
    static final int UNAVAILABLE = 0, DOWNLOADABLE = 1, DOWNLOADING = 2, AVAILABLE = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GenerativeModelFutures model;
    private String mode, text, app, tone;
    private boolean test;
    private TextView status;
    private LinearLayout list, chips;
    private boolean busy = false;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        test = i.getBooleanExtra("test", false);
        mode = nz(i.getStringExtra("mode"), "profile");
        text = nz(i.getStringExtra("text"), "");
        app = nz(i.getStringExtra("app"), "");
        tone = nz(i.getStringExtra("tone"), "natural");
        buildUi();
        try {
            model = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
        } catch (Throwable t) {
            setStatus("On-phone AI isn't available: " + t.getMessage(), true);
            return;
        }
        run();
    }

    private static String nz(String s, String d) { return s == null ? d : s; }

    // ---------------------------------------------------------------- AI

    private void run() {
        if (busy) return;
        busy = true;
        list.removeAllViews();
        setStatus("Checking on-phone AI…", false);
        new Thread(() -> {
            try {
                int st = model.checkStatus().get();
                if (st == UNAVAILABLE) {
                    ui(() -> setStatus("❌ Your phone doesn't support Google's on-phone AI (Gemini Nano) for apps yet.\n\n"
                            + "Open Wingman settings and pick “Gemini (free)” or “Claude app” instead. "
                            + "(If you just updated your phone, restart it and try again later.)", true));
                    return;
                }
                if (st == DOWNLOADING) {
                    ui(() -> setStatus("⏳ Your phone is downloading the AI model. Keep Wi-Fi on and try again in a few minutes.", false));
                    return;
                }
                if (st == DOWNLOADABLE) {
                    ui(() -> setStatus("⬇️ Downloading the on-phone AI model (one time). Keep this open on Wi-Fi…", false));
                    model.download(new DownloadCallback() {
                        long total = 0;
                        @Override public void onDownloadStarted(long bytes) { total = bytes; }
                        @Override public void onDownloadProgress(long done) {
                            if (total > 0) ui(() -> setStatus("⬇️ Downloading the on-phone AI model… "
                                    + (done * 100 / total) + "%", false));
                        }
                        @Override public void onDownloadCompleted() { }
                        @Override public void onDownloadFailed(GenAiException e) { }
                    }).get();
                }
                if (test) {
                    ui(() -> setStatus("✅ On-phone AI works on this phone. Writing a test message…", false));
                    GenerateContentResponse r = model.generateContent("Write one short, friendly opening line for a dating app. Just the line.").get();
                    String out = r.getCandidates().get(0).getText();
                    ui(() -> { setStatus("✅ On-phone AI works! Test answer:", false); list.addView(note(out)); });
                    return;
                }
                if (text.trim().length() < 5) {
                    ui(() -> setStatus("Nothing read yet. Scroll the profile or open a chat, then tap the bubble.", true));
                    return;
                }
                ui(() -> setStatus("✨ Writing on your phone…", false));
                GenerateContentResponse r = model.generateContent(prompt()).get();
                String out = r.getCandidates().get(0).getText();
                List<String> msgs = parse(out);
                ui(() -> {
                    if (msgs.isEmpty()) { setStatus("No suggestions came back. Tap ✨ Again.", true); return; }
                    setStatus(mode.equals("chat") ? "Reply ideas (made on your phone):" : "Openers (made on your phone):", false);
                    for (String m : msgs) list.addView(card(m));
                });
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                ui(() -> setStatus("On-phone AI error: " + msg
                        + "\n\nIf it keeps failing, pick “Gemini (free)” in Wingman settings.", true));
            } finally {
                busy = false;
            }
        }).start();
    }

    /** Short prompt: the on-phone model is small, so keep it simple. */
    private String prompt() {
        String about = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("aboutMe", "");
        String lang = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("language", "");
        String screen = text.length() > 2500 ? text.substring(text.length() - 2500) : text;
        StringBuilder p = new StringBuilder();
        p.append("You help me write dating-app messages. Sound like a real person texting: casual, 1-2 short sentences, ")
         .append("specific to the details below, respectful, no cheesy pickup lines. Ignore app button labels.\n");
        p.append("Style: ").append(Claude.toneText(tone)).append('\n');
        if (!about.isEmpty()) p.append("About me: ").append(about).append('\n');
        if (!lang.isEmpty()) p.append("Write in: ").append(lang).append('\n');
        if (mode.equals("chat")) {
            p.append("\nOur chat (Me = me, Them = my match):\n").append(screen)
             .append("\n\nWrite 4 different options for my next message.");
        } else {
            p.append("\nTheir profile:\n").append(screen)
             .append("\n\nWrite 4 different opening messages, each about a different detail of their profile.");
        }
        p.append(" Put each message on its own line, numbered 1. to 4. Nothing else.");
        return p.toString();
    }

    static List<String> parse(String out) {
        List<String> res = new ArrayList<>();
        if (out == null) return res;
        for (String line : out.split("\n")) {
            String s = line.trim().replaceFirst("^(\\d+[.)]|[-*•])\\s*", "").trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1).trim();
            if (s.startsWith("**") && s.endsWith("**") && s.length() > 4) s = s.substring(2, s.length() - 2).trim();
            if (s.length() >= 3 && !s.endsWith(":") && res.size() < 4) res.add(s);
        }
        return res;
    }

    private void ui(Runnable r) { main.post(() -> { if (!isFinishing()) r.run(); }); }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        root.setOnClickListener(v -> finish()); // tap above the panel to close

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(18));
        sheet.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(22));
        sheet.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(test ? "✨ Wingman · on-phone AI test" : "✨ Wingman · on-phone AI");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFF5F8F);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = pill("✕", false);
        close.setOnClickListener(v -> finish());
        head.addView(close);
        sheet.addView(head);

        if (!test) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            chips = new LinearLayout(this);
            hs.addView(chips);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.topMargin = dp(10);
            sheet.addView(hs, hl);
            renderChips();
        }

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(10), 0, dp(6));
        sheet.addView(status);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.42);
        sheet.addView(sc, new LinearLayout.LayoutParams(-1, h));

        TextView again = pill("✨ Again", true);
        again.setOnClickListener(v -> run());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, dp(46));
        al.topMargin = dp(8);
        sheet.addView(again, al);

        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sl.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(sheet, sl);
        setContentView(root);
    }

    private void renderChips() {
        chips.removeAllViews();
        for (String[] t : Claude.TONES) {
            if (mode.equals("profile") && (t[0].equals("date") || t[0].equals("exit"))) continue;
            boolean on = t[0].equals(tone);
            TextView c = pill(t[1], on);
            c.setTextSize(13);
            final String key = t[0];
            c.setOnClickListener(v -> { if (busy) return; tone = key; renderChips(); run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(0, 0, dp(6), 0);
            chips.addView(c, lp);
        }
    }

    private void setStatus(String s, boolean err) {
        status.setText(s);
        status.setTextColor(err ? 0xFFD70015 : 0xFF6E6E73);
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(0xFF1D1D1F);
        return t;
    }

    private View card(String msg) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0xFFEEE4E8);
        g.setColor(0xFFFFFBFC);
        c.setBackground(g);
        TextView t = note(msg);
        t.setTextSize(16);
        c.addView(t);
        LinearLayout row = new LinearLayout(this);
        TextView copy = pill("📋 Copy", false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Wingman", msg));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMargins(0, dp(6), dp(6), 0);
        row.addView(copy, lp);
        TextView ins = pill("✍️ Put in chat box", false);
        ins.setOnClickListener(v -> {
            finish();
            WingmanService.insertLater(msg);
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, dp(36));
        lp2.setMargins(0, dp(6), 0, 0);
        row.addView(ins, lp2);
        c.addView(row);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.bottomMargin = dp(8);
        c.setLayoutParams(cl);
        return c;
    }

    private TextView pill(String s, boolean on) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable g = on
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF5F8F, 0xFFFF8A5C})
                : new GradientDrawable();
        if (!on) g.setColor(0xFFF3F1F3);
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        b.setTextColor(on ? Color.WHITE : 0xFF1D1D1F);
        return b;
    }
}
