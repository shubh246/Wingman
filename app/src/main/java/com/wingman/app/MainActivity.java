package com.wingman.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings + setup screen. */
public class MainActivity extends Activity {

    static final String PREFS = "wingman";
    static final String[][] MODELS = {
        {"claude-haiku-5-5", "Haiku 5.5 · fastest, cheapest (good for auto mode)"},
        {"claude-sonnet-5-5", "Sonnet 5.5 · best balance"},
        {"claude-opus-5-5", "Opus 5.5 · most thoughtful"}
    };

    private TextView status;
    private EditText apiKey, aboutMe, language, geminiKey, geminiModel;
    private RadioGroup modelGroup, engineGroup, providerGroup;
    private EditText freeKey, freeModel;
    private Switch autoSwitch, allAppsSwitch;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(40));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("✨ Wingman");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFF5F8F);
        col.addView(title);
        col.addView(text("Reads profiles and chats in your dating apps as you scroll, and suggests what to send.", 15, 0xFF666666));

        status = text("", 15, 0xFF000000);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(16);
        col.addView(status, slp);

        Button enable = button("Turn on Wingman (Accessibility)");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(enable);
        col.addView(text("If Android says the setting is “restricted”: open App info below → ⋮ (top right) → “Allow restricted settings”, then try again.", 13, 0xFF888888));
        Button info = button("Open App info");
        info.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        col.addView(info);

        col.addView(header("How should Wingman write suggestions?"));
        engineGroup = new RadioGroup(this);
        String engine = p.getString("engine", p.getString("apiKey", "").isEmpty() ? "app" : "api");
        RadioButton rApp = new RadioButton(this);
        rApp.setId(2000);
        rApp.setText("Claude app · uses your Claude account, no extra cost. The bubble sends what it read to the Claude app.");
        rApp.setChecked(engine.equals("app"));
        RadioButton rApi = new RadioButton(this);
        rApi.setId(2001);
        rApi.setText("API key · suggestions appear right in the bubble, automatically. Needs API credit.");
        rApi.setChecked(engine.equals("api"));
        RadioButton rGem = new RadioButton(this);
        rGem.setId(2002);
        rGem.setText("Gemini (free) · suggestions right in the bubble, automatically. Free key from Google, with daily limits.");
        rGem.setChecked(engine.equals("gemini"));
        RadioButton rFree = new RadioButton(this);
        rFree.setId(2004);
        rFree.setText("Free AI provider (Groq, OpenRouter, Cerebras, Mistral) · suggestions right in the bubble, free key.");
        rFree.setChecked(engine.equals("free"));
        engineGroup.addView(rFree);
        RadioButton rNano = new RadioButton(this);
        rNano.setId(2003);
        rNano.setText("On-phone AI (Gemini Nano) · free, private, nothing leaves your phone. Only on supported phones; tap Test below.");
        rNano.setChecked(engine.equals("nano"));
        engineGroup.addView(rNano);
        engineGroup.addView(rGem);
        engineGroup.addView(rApp);
        engineGroup.addView(rApi);
        col.addView(engineGroup);

        col.addView(header("Free AI provider (only for that mode)"));
        providerGroup = new RadioGroup(this);
        String prov = p.getString("freeProvider", "groq");
        for (int i = 0; i < FreeAI.PROVIDERS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(3000 + i);
            r.setText(FreeAI.PROVIDERS[i][1]);
            r.setChecked(FreeAI.PROVIDERS[i][0].equals(prov));
            providerGroup.addView(r);
        }
        col.addView(providerGroup);
        Button getFree = button("Get a free key for the chosen provider");
        getFree.setOnClickListener(v -> {
            int idx = providerGroup.getCheckedRadioButtonId() - 3000;
            String[] pr = FreeAI.PROVIDERS[(idx >= 0 && idx < FreeAI.PROVIDERS.length) ? idx : 0];
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pr[3])));
        });
        col.addView(getFree);
        col.addView(text("Sign up free → create an API key → copy it here.", 13, 0xFF888888));
        freeKey = edit("Paste your free key", p.getString("freeKey", ""), false);
        freeKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(freeKey);
        freeModel = edit("Model: leave blank to pick automatically", p.getString("freeModel", ""), false);
        col.addView(freeModel);

        Button testNano = button("Test on-phone AI on this phone");
        testNano.setOnClickListener(v -> startActivity(new Intent(this, NanoActivity.class).putExtra("test", true)));
        col.addView(testNano);

        col.addView(header("Gemini key (only for Gemini mode)"));
        geminiKey = edit("AIza…", p.getString("geminiKey", ""), false);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(geminiKey);
        col.addView(text("Free: open aistudio.google.com on your phone → sign in with Google → Get API key → Create API key → copy it here. "
                + "On the free tier Google may use what's sent to improve its products.", 13, 0xFF888888));
        Button getKey = button("Open Google AI Studio");
        getKey.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))));
        col.addView(getKey);
        geminiModel = edit(Gemini.DEFAULT_MODEL, p.getString("geminiModel", Gemini.DEFAULT_MODEL), false);
        col.addView(text("Gemini model (leave as is unless it shows a model error):", 13, 0xFF888888));
        col.addView(geminiModel);

        col.addView(header("Claude API key (only for API mode)"));
        apiKey = edit("sk-ant-…", p.getString("apiKey", ""), false);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(apiKey);
        col.addView(text("Get one at console.anthropic.com. Stored only on this phone.", 13, 0xFF888888));

        col.addView(header("Model"));
        modelGroup = new RadioGroup(this);
        String model = p.getString("model", "claude-haiku-5-5");
        for (int i = 0; i < MODELS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(1000 + i);
            r.setText(MODELS[i][1]);
            r.setChecked(MODELS[i][0].equals(model));
            modelGroup.addView(r);
        }
        col.addView(modelGroup);

        col.addView(header("About me"));
        aboutMe = edit("e.g. 27, engineer in Bangalore, into football and trekking. I text casually, lowercase, few emojis.",
                p.getString("aboutMe", ""), true);
        col.addView(aboutMe);

        col.addView(header("Reply language"));
        language = edit("Blank = match them (e.g. English, Hinglish)", p.getString("language", ""), false);
        col.addView(language);

        autoSwitch = new Switch(this);
        autoSwitch.setText("Auto-suggest when I stop scrolling (Gemini / API mode)");
        autoSwitch.setTextSize(15);
        autoSwitch.setChecked(p.getBoolean("auto", true));
        LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(-1, -2);
        sw.topMargin = dp(20);
        col.addView(autoSwitch, sw);
        col.addView(text("Wingman always reads as you scroll. With this on, it also writes suggestions by itself a moment after you stop. Off = only when you tap Suggest (uses less API credit).", 13, 0xFF888888));

        allAppsSwitch = new Switch(this);
        allAppsSwitch.setText("Show in all apps (e.g. WhatsApp, Instagram)");
        allAppsSwitch.setTextSize(15);
        allAppsSwitch.setChecked(p.getBoolean("allApps", false));
        col.addView(allAppsSwitch, sw);
        col.addView(text("Off = only Tinder, Bumble, Hinge, OkCupid, Aisle, TrulyMadly, Happn and Badoo.", 13, 0xFF888888));

        Button save = button("Save");
        save.setOnClickListener(v -> save());
        col.addView(save);

        col.addView(header("How to use"));
        col.addView(text("1. Turn on Wingman above and save your key.\n"
                + "2. Open your dating app. A ✨ bubble appears (drag it anywhere).\n"
                + "3. Scroll a profile or open a chat. The bubble turns into a number when suggestions are ready.\n"
                + "4. Tap the bubble, pick one, tap Copy or Put in chat box. You always hit send yourself.\n\n"
                + "Privacy: the on-screen text of the dating app is sent to Claude (Anthropic) to write suggestions. Nothing is stored or sent anywhere else.", 14, 0xFF444444));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceOn();
        status.setText(on ? "✅ Wingman is on" : "⚠️ Wingman is off — tap the button below and turn on Wingman");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? 0xFFE6F7EC : 0xFFFFF1E0);
        status.setBackground(bg);
    }

    private void save() {
        String oldProv = getSharedPreferences(PREFS, MODE_PRIVATE).getString("freeProvider", "groq");
        int pIdx = Math.max(0, Math.min(FreeAI.PROVIDERS.length - 1, providerGroup.getCheckedRadioButtonId() - 3000));
        if (!FreeAI.PROVIDERS[pIdx][0].equals(oldProv)) freeModel.setText(""); // new provider: pick its model automatically
        int idx = modelGroup.getCheckedRadioButtonId() - 1000;
        String model = (idx >= 0 && idx < MODELS.length) ? MODELS[idx][0] : MODELS[0][0];
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("apiKey", apiKey.getText().toString().trim())
                .putString("model", model)
                .putString("engine", engineGroup.getCheckedRadioButtonId() == 2001 ? "api"
                        : engineGroup.getCheckedRadioButtonId() == 2002 ? "gemini"
                        : engineGroup.getCheckedRadioButtonId() == 2003 ? "nano"
                        : engineGroup.getCheckedRadioButtonId() == 2004 ? "free" : "app")
                .putString("freeProvider", FreeAI.PROVIDERS[Math.max(0, Math.min(FreeAI.PROVIDERS.length - 1,
                        providerGroup.getCheckedRadioButtonId() - 3000))][0])
                .putString("freeKey", freeKey.getText().toString().trim())
                .putString("freeModel", freeModel.getText().toString().trim())
                .putString("geminiKey", geminiKey.getText().toString().trim())
                .putString("geminiModel", geminiModel.getText().toString().trim())
                .putString("aboutMe", aboutMe.getText().toString().trim())
                .putString("language", language.getText().toString().trim())
                .putBoolean("auto", autoSwitch.isChecked())
                .putBoolean("allApps", allAppsSwitch.isChecked())
                .apply();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private boolean isServiceOn() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String me = new ComponentName(this, WingmanService.class).flattenToString();
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me)) return true;
        return false;
    }

    // ---- small view helpers ----
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 16, 0xFF111111);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private EditText edit(String hint, String value, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF5F8F, 0xFFFF8A5C});
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setVisibility(View.VISIBLE);
        return b;
    }
}
