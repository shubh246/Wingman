package com.wingman.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the dating app's screen as you scroll (via Accessibility), keeps a running copy of the
 * profile or chat, and shows a floating bubble with suggested messages.
 */
public class WingmanService extends AccessibilityService {

    private static final Set<String> DATING_APPS = new HashSet<>(Arrays.asList(
            "com.tinder", "com.bumble.app", "co.hinge.app", "com.okcupid.okcupid",
            "com.aisle.app", "com.trulymadly.android.app", "com.ftw_and_co.happn", "com.badoo.mobile"));

    private static final Set<String> UI_WORDS = new HashSet<>(Arrays.asList(
            "like", "likes", "send", "message", "messages", "pass", "nope", "super like", "superlike", "boost",
            "back", "more", "report", "block", "unmatch", "settings", "home", "matches", "chats", "chat",
            "profile", "discover", "likes you", "standouts", "type a message", "send a message", "gif", "gifs",
            "photo", "undo", "rewind", "close", "menu", "notifications", "explore", "search", "share", "options",
            "add a comment", "send like", "skip", "next", "previous", "see more", "show more", "edit", "done",
            "camera", "emoji", "sticker", "voice message", "video call", "call", "spotlight", "premium", "upgrade"));

    private static final Pattern NOISE = Pattern.compile(
            "^(\\d{1,2}[:.]\\d{2}\\s?(am|pm)?|today|yesterday|now|just now|sent|seen|read|delivered|typing\\.*|"
          + "\\d+\\s?(s|m|h|d|w|min|mins|hr|hrs|km|mi|miles)( away| ago)?|\\d+|[^\\p{L}\\p{N}]+)$",
            Pattern.CASE_INSENSITIVE);

    private static final class Item {
        final String text; final Rect r; final boolean clickable; final boolean important;
        Item(String t, Rect r, boolean c, boolean imp) { text = t; this.r = r; clickable = c; important = imp; }
    }

    // View ids that are app chrome (tabs, toolbars, buttons…) vs. profile/chat content.
    private static final Pattern CHROME_ID = Pattern.compile(
            "(^|[_/.:])(tab|tabs|nav|navigation|toolbar|bottom_?bar|app_?bar|action_?bar|menu|badge|counter|count|button|btn|fab|"
          + "icon|timestamp|time|status|banner|ad|ads|promo|upsell|boost|premium|gold|plus|paywall|tooltip|snackbar|header_title)([_/.:]|$)");
    private static final Pattern CONTENT_ID = Pattern.compile(
            "(bio|prompt|answer|about|message|msg|body|bubble|name|age|job|work|title|school|education|interest|passion|"
          + "hobby|location|hometown|city|caption|description|desc|text_content|content|question|vital|lifestyle|basics|height|looking)");

    // Learned per app: short lines that show up on most profiles = part of the app, not the person.
    private final java.util.Map<String, Integer> lineCounts = new java.util.HashMap<>();
    private int profilesSeen = 0;
    private String learnedPkg = "";

    private static WingmanService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;

    // screen state
    private String currentPkg = "";
    private String mode = "profile";
    private final LinkedHashSet<String> profileLines = new LinkedHashSet<>();
    private Set<String> lastScreen = new HashSet<>();
    private int profileId = 0;
    private String chatText = "";
    private String contentKey = "";
    private long lastReadAt = 0;

    // suggestion state
    private String tone = "natural";
    private List<Claude.Suggestion> suggestions = new ArrayList<>();
    private boolean loading = false;
    private String error = null;
    private int requestId = 0;
    private int sugProfileId = -1;
    private int sugProfileLen = 0;
    private String sugChatTail = null;
    private boolean showRead = false;

    // views
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView panelTitle, suggestBtn;
    private LinearLayout chipRow, listBox;
    private ScrollView listScroll;
    private boolean panelShown = false, bubbleShown = false;

    private final Runnable readTask = this::readScreen;
    private final Runnable autoTask = this::maybeAutoSuggest;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        buildBubble();
        buildPanel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || isIgnored(pkg)) return;
        if (!isTarget(pkg) && !bubbleShown) return;
        main.removeCallbacks(readTask);
        long since = SystemClock.uptimeMillis() - lastReadAt;
        // read when scrolling pauses, but at least every 2.5 s while it keeps moving
        main.postDelayed(readTask, since > 2500 ? 0 : 700);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        hidePanel();
        hideBubble();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ reading

    private boolean isIgnored(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.contains("honeyboard") || pkg.contains("swiftkey") || pkg.contains("touchtype");
    }

    private boolean isTarget(String pkg) {
        if (DATING_APPS.contains(pkg)) return true;
        if (!prefs.getBoolean("allApps", false)) return false;
        return !pkg.contains("launcher") && !pkg.equals("com.android.settings");
    }

    private void readScreen() {
        lastReadAt = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        if (pkg.equals(getPackageName()) || isIgnored(pkg)) return;
        if (!isTarget(pkg)) {
            currentPkg = "";
            hidePanel();
            hideBubble();
            return;
        }
        if (!pkg.equals(currentPkg)) {
            currentPkg = pkg;
            resetContent();
            loadLearned(pkg);
        }
        showBubble();

        List<Item> items = new ArrayList<>();
        boolean[] hasInput = {false};
        collect(root, items, hasInput, 0, new HashSet<>());
        Collections.sort(items, (a, b) -> a.r.top != b.r.top ? Integer.compare(a.r.top, b.r.top) : Integer.compare(a.r.left, b.r.left));

        int w = getResources().getDisplayMetrics().widthPixels;
        List<String[]> sided = new ArrayList<>();
        int sidedCount = 0;
        for (Item it : items) {
            String who = side(it.r, w);
            if (!who.equals("?")) sidedCount++;
            sided.add(new String[]{who, it.text});
        }

        if (hasInput[0] && sidedCount >= 1) {
            mode = "chat";
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (String[] s : sided) {
                if (s[0].equals("?") && isLearnedChrome(s[1])) continue;
                String label = s[0].equals("me") ? "Me" : s[0].equals("them") ? "Them" : "?";
                if (label.equals(prev)) sb.append(" / ").append(s[1]);
                else { if (sb.length() > 0) sb.append('\n'); sb.append(label).append(": ").append(s[1]); }
                prev = label;
            }
            chatText = sb.toString();
            if (chatText.length() > 5000) chatText = chatText.substring(chatText.length() - 5000);
        } else {
            mode = "profile";
            int h = getResources().getDisplayMetrics().heightPixels;
            List<String> cur = new ArrayList<>();
            for (Item it : items) {
                if (!it.important) {
                    if (it.clickable && it.text.length() < 14) continue;          // buttons
                    if (it.r.bottom < h * 0.08 || it.r.top > h * 0.90) continue;  // top bar / bottom tabs
                }
                if (isLearnedChrome(it.text)) continue;
                cur.add(it.text);
            }
            int overlap = 0;
            for (String s : cur) if (lastScreen.contains(s)) overlap++;
            // Mostly new text on screen = you moved to a different profile.
            if (!lastScreen.isEmpty() && cur.size() >= 3 && overlap < cur.size() * 0.3) {
                learnFromProfile();
                profileLines.clear();
                profileId++;
            }
            for (String s : cur) if (profileLines.size() < 200) profileLines.add(s);
            lastScreen = new HashSet<>(cur);
        }

        String key = mode + ":" + currentText().hashCode();
        if (!key.equals(contentKey)) {
            contentKey = key;
            updateBubble();
            if (panelShown) renderPanel();
            main.removeCallbacks(autoTask);
            String en = engine();
            if ((en.equals("api") || en.equals("gemini") || en.equals("free")) && prefs.getBoolean("auto", true)) main.postDelayed(autoTask, 1800);
        }
    }

    private void collect(AccessibilityNodeInfo n, List<Item> out, boolean[] hasInput, int depth, Set<String> seen) {
        if (n == null || depth > 60 || !n.isVisibleToUser()) return;
        if (n.isEditable()) { hasInput[0] = true; return; }
        CharSequence cs = n.getText();
        if (cs == null || cs.toString().trim().isEmpty()) cs = n.getContentDescription();
        if (cs != null) {
            String t = cs.toString().replaceAll("\\s+", " ").trim();
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase();
            String idName = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
            boolean content = !idName.isEmpty() && CONTENT_ID.matcher(idName).find();
            boolean chromeId = !content && !idName.isEmpty() && CHROME_ID.matcher(idName).find();
            if (!chromeId && t.length() >= 2 && !NOISE.matcher(t).matches() && !UI_WORDS.contains(t.toLowerCase()) && seen.add(t)) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) out.add(new Item(t, r, n.isClickable(), content));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, hasInput, depth + 1, seen);
    }

    /** Right-aligned bubbles are yours, left-aligned are theirs. */
    private static String side(Rect r, int w) {
        int leftGap = r.left, rightGap = w - r.right;
        if (rightGap < w * 0.12 && leftGap > w * 0.25) return "me";
        if (leftGap < w * 0.2 && rightGap > w * 0.25) return "them";
        return "?";
    }

    // ------------------------------------------------------------------ learning each app

    private boolean isLearnedChrome(String line) {
        if (line.length() > 60 || profilesSeen < 4) return false;
        Integer c = lineCounts.get(line);
        return c != null && c >= 4 && c >= profilesSeen * 0.6;
    }

    /** Called when you move to the next profile: count which short lines repeat across profiles. */
    private void learnFromProfile() {
        if (profileLines.size() < 3) return;
        profilesSeen++;
        for (String l : profileLines) if (l.length() <= 60) {
            Integer c = lineCounts.get(l);
            lineCounts.put(l, c == null ? 1 : c + 1);
        }
        // keep memory small: forget lines seen only once after a while
        if (lineCounts.size() > 400) {
            java.util.Iterator<java.util.Map.Entry<String, Integer>> it = lineCounts.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() <= 1) it.remove();
        }
        saveLearned();
    }

    private void loadLearned(String pkg) {
        learnedPkg = pkg;
        lineCounts.clear();
        profilesSeen = prefs.getInt("seen:" + pkg, 0);
        String raw = prefs.getString("lines:" + pkg, "");
        for (String row : raw.split("\n")) {
            int tab = row.lastIndexOf('\t');
            if (tab <= 0) continue;
            try { lineCounts.put(row.substring(0, tab), Integer.parseInt(row.substring(tab + 1))); } catch (Exception ignored) { }
        }
    }

    private void saveLearned() {
        if (learnedPkg.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : lineCounts.entrySet())
            if (e.getValue() >= 2) sb.append(e.getKey().replace('\n', ' ').replace('\t', ' ')).append('\t').append(e.getValue()).append('\n');
        prefs.edit().putInt("seen:" + learnedPkg, profilesSeen).putString("lines:" + learnedPkg, sb.toString()).apply();
    }

    private void resetContent() {
        profileLines.clear();
        lastScreen = new HashSet<>();
        profileId++;
        chatText = "";
        contentKey = "";
        suggestions = new ArrayList<>();
        error = null;
        loading = false;
        requestId++;
    }

    private String currentText() {
        if (mode.equals("chat")) return chatText;
        StringBuilder sb = new StringBuilder();
        for (String s : profileLines) { if (sb.length() > 6000) break; sb.append(s).append('\n'); }
        return sb.toString().trim();
    }

    private String chatTail() {
        int i = chatText.lastIndexOf('\n');
        return i >= 0 ? chatText.substring(i + 1) : chatText;
    }

    // ------------------------------------------------------------------ suggestions

    /** "app" = send to the Claude app (no API key needed); "api" = suggestions right in the bubble. */
    private String engine() {
        String def = prefs.getString("apiKey", "").isEmpty() ? "app" : "api";
        return prefs.getString("engine", def);
    }

    private boolean useApp() { return engine().equals("app"); }

    /** Called by the on-phone AI panel after it closes, so the dating app's box is on screen again. */
    static void insertLater(String text) {
        WingmanService s = instance;
        if (s != null) s.main.postDelayed(() -> s.insert(text), 450);
    }

    private void openNano() {
        Intent i = new Intent(this, NanoActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("mode", mode);
        i.putExtra("text", currentText());
        i.putExtra("app", currentPkg);
        i.putExtra("tone", tone);
        try { startActivity(i); } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the on-phone AI panel.", Toast.LENGTH_SHORT).show();
        }
    }

    private void askClaudeApp() {
        String text = currentText();
        if (text.length() < 5) {
            Toast.makeText(this, "Nothing read yet. Scroll the profile or open a chat first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = Claude.appPrompt(prefs.getString("aboutMe", ""), prefs.getString("language", ""),
                mode, tone, currentPkg, text);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Wingman", prompt));
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, prompt);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        hidePanel();
        boolean split = prefs.getBoolean("split", true);
        if (split) {
            i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            // Put the dating app into split screen first (unless it already is), then open Claude in the other half.
            if (!inSplitScreen()) performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
            main.postDelayed(() -> launchClaude(i), 900);
            Toast.makeText(this, "Tap send in Claude below, then copy a reply into the chat above. Drag the middle bar to close.", Toast.LENGTH_LONG).show();
        } else {
            launchClaude(i);
            Toast.makeText(this, "Send it in Claude, copy a reply, then come back and paste.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchClaude(Intent i) {
        try {
            i.setPackage("com.anthropic.claude");
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setPackage(null);
            Intent chooser = Intent.createChooser(i, "Send to Claude");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            try { startActivity(chooser); } catch (Exception ignored) { }
        }
    }

    /** True when two app windows are already side by side. */
    private boolean inSplitScreen() {
        try {
            int apps = 0;
            for (AccessibilityWindowInfo w : getWindows())
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) apps++;
            return apps >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    private void maybeAutoSuggest() {
        if (loading || currentText().length() < 15) return;
        boolean stale;
        if (mode.equals("chat")) stale = !chatTail().equals(sugChatTail);
        else {
            int len = currentText().length();
            stale = profileId != sugProfileId || len > sugProfileLen * 1.4 + 50;
        }
        if (stale) generate();
    }

    private void generate() {
        final String text = currentText();
        final String m = mode;
        if (text.length() < 5) {
            error = "Nothing read yet. Scroll the profile or open a chat.";
            loading = false;
            updateBubble();
            if (panelShown) renderPanel();
            return;
        }
        final int id = ++requestId;
        final int pid = profileId;
        final String tail = chatTail();
        final String app = currentPkg;
        final boolean gemini = prefs.getString("engine", "").equals("gemini");
        final boolean free = prefs.getString("engine", "").equals("free");
        final String apiKey = gemini ? prefs.getString("geminiKey", "") : prefs.getString("apiKey", "");
        final String model = gemini ? prefs.getString("geminiModel", Gemini.DEFAULT_MODEL) : prefs.getString("model", "claude-haiku-5-5");
        final String about = prefs.getString("aboutMe", "");
        final String lang = prefs.getString("language", "");
        final String t = tone;
        loading = true;
        error = null;
        updateBubble();
        if (panelShown) renderPanel();

        new Thread(() -> {
            List<Claude.Suggestion> res = null;
            String err = null;
            try {
                res = free ? FreeAI.suggest(prefs, m, t, app, text)
                    : gemini ? Gemini.suggest(apiKey, model, about, lang, m, t, app, text)
                             : Claude.suggest(apiKey, model, about, lang, m, t, app, text);
            }
            catch (Exception ex) { err = ex.getMessage() == null ? "Something went wrong." : ex.getMessage(); }
            final List<Claude.Suggestion> fr = res;
            final String fe = err;
            main.post(() -> {
                if (id != requestId) return; // a newer request or profile replaced this one
                loading = false;
                if (fr != null) {
                    suggestions = fr;
                    error = null;
                    if (m.equals("chat")) sugChatTail = tail;
                    else { sugProfileId = pid; sugProfileLen = text.length(); }
                } else {
                    error = fe;
                }
                updateBubble();
                if (panelShown) renderPanel();
            });
        }).start();
    }

    // ------------------------------------------------------------------ bubble

    private void buildBubble() {
        bubble = new TextView(this);
        bubble.setText("✨");
        bubble.setTextSize(20);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF5F8F, 0xFFFF8A5C});
        g.setShape(GradientDrawable.OVAL);
        bubble.setBackground(g);
        bubble.setElevation(dp(6));

        bubbleLp = new WindowManager.LayoutParams(dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = getResources().getDisplayMetrics().widthPixels - dp(70);
        bubbleLp.y = getResources().getDisplayMetrics().heightPixels / 3;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX(); downY = ev.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved && bubbleShown) {
                            bubbleLp.x = startX + (int) dx;
                            bubbleLp.y = startY + (int) dy;
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });
    }

    private void showBubble() {
        if (bubbleShown) return;
        try { wm.addView(bubble, bubbleLp); bubbleShown = true; updateBubble(); } catch (Exception ignored) { }
    }

    private void hideBubble() {
        if (!bubbleShown) return;
        try { wm.removeView(bubble); } catch (Exception ignored) { }
        bubbleShown = false;
    }

    private void updateBubble() {
        if (bubble == null) return;
        if (loading) bubble.setText("…");
        else if (error != null) bubble.setText("!");
        else if (!suggestions.isEmpty() && isFresh()) bubble.setText(String.valueOf(suggestions.size()));
        else bubble.setText("✨");
    }

    private boolean isFresh() {
        if (mode.equals("chat")) return chatTail().equals(sugChatTail);
        return profileId == sugProfileId;
    }

    // ------------------------------------------------------------------ panel

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        panel.setBackground(bg);
        panel.setElevation(dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = new TextView(this);
        panelTitle.setTextSize(15);
        panelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        panelTitle.setTextColor(0xFF1D1D1F);
        head.addView(panelTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = smallButton("✕", false);
        close.setOnClickListener(v -> hidePanel());
        head.addView(close);
        panel.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        hs.addView(chipRow);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(8);
        panel.addView(hs, hlp);

        listScroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.42);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, h);
        llp.topMargin = dp(8);
        panel.addView(listScroll, llp);

        LinearLayout foot = new LinearLayout(this);
        TextView again = smallButton("✨ Suggest", true);
        suggestBtn = again;
        again.setOnClickListener(v -> { if (useApp()) askClaudeApp(); else generate(); });
        TextView read = smallButton("👀 What it read", false);
        read.setOnClickListener(v -> { showRead = !showRead; renderPanel(); });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(44), 1);
        half.setMargins(0, 0, dp(6), 0);
        foot.addView(again, half);
        foot.addView(read, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(10);
        panel.addView(foot, flp);

        panelLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.BOTTOM;
        panelLp.horizontalMargin = 0.02f;
        panelLp.y = dp(12);
    }

    private void togglePanel() {
        if (panelShown) { hidePanel(); return; }
        if (engine().equals("nano")) { openNano(); return; }
        try { wm.addView(panel, panelLp); panelShown = true; } catch (Exception ignored) { return; }
        renderPanel();
        if (suggestions.isEmpty() && !loading && !useApp()) generate();
    }

    private void hidePanel() {
        if (!panelShown) return;
        try { wm.removeView(panel); } catch (Exception ignored) { }
        panelShown = false;
    }

    private void renderPanel() {
        String what = mode.equals("chat")
                ? "💬 Chat · replies"
                : "👤 Profile · openers (" + profileLines.size() + " lines read)";
        panelTitle.setText(what);

        chipRow.removeAllViews();
        for (String[] t : Claude.TONES) {
            if (mode.equals("profile") && (t[0].equals("date") || t[0].equals("exit"))) continue;
            TextView c = new TextView(this);
            c.setText(t[1]);
            c.setTextSize(13);
            c.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            boolean on = t[0].equals(tone);
            if (on) { g.setColor(0xFFFF6F86); c.setTextColor(Color.WHITE); }
            else { g.setColor(0xFFF3F1F3); c.setTextColor(0xFF1D1D1F); }
            c.setBackground(g);
            final String key = t[0];
            c.setOnClickListener(v -> { tone = key; if (useApp()) renderPanel(); else generate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(6), 0);
            chipRow.addView(c, lp);
        }

        boolean app = useApp();
        suggestBtn.setText(app ? "💬 Ask Claude" : "✨ Suggest");
        listBox.removeAllViews();
        if (showRead) {
            TextView r = new TextView(this);
            String txt = currentText();
            r.setText(txt.isEmpty() ? "Nothing read yet." : txt);
            r.setTextSize(12);
            r.setTextColor(0xFF555555);
            r.setTextIsSelectable(false);
            listBox.addView(r);
            return;
        }
        if (app) {
            listBox.addView(note((mode.equals("chat") ? "Chat read. " : "Profile read: " + profileLines.size() + " lines. ")
                    + "Pick a vibe, then tap 💬 Ask Claude.\n\nWingman sends what it read to your Claude app. "
                    + "Tap send there, copy the reply you like, come back here and paste it."));
            return;
        }
        if (loading) { listBox.addView(note("Writing suggestions…")); }
        if (error != null) { TextView e = note(error); e.setTextColor(0xFFD70015); listBox.addView(e); }
        if (!loading && suggestions.isEmpty() && error == null) {
            listBox.addView(note(mode.equals("chat") ? "Tap ✨ Suggest for reply ideas." : "Scroll through the profile, then tap ✨ Suggest."));
        }
        if (!suggestions.isEmpty() && !isFresh() && !loading) {
            listBox.addView(note("Screen changed. Tap ✨ Suggest to refresh."));
        }
        for (Claude.Suggestion s : suggestions) listBox.addView(card(s));
    }

    private View card(Claude.Suggestion s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0xFFEEE4E8);
        g.setColor(0xFFFFFBFC);
        c.setBackground(g);

        TextView t = new TextView(this);
        t.setText(s.text);
        t.setTextSize(16);
        t.setTextColor(0xFF1D1D1F);
        c.addView(t);
        if (s.why != null && !s.why.isEmpty()) {
            TextView w = new TextView(this);
            w.setText(s.why);
            w.setTextSize(12);
            w.setTextColor(0xFF8E8E93);
            w.setPadding(0, dp(3), 0, dp(6));
            c.addView(w);
        }
        LinearLayout row = new LinearLayout(this);
        TextView copy = smallButton("📋 Copy", false);
        copy.setOnClickListener(v -> copy(s.text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMargins(0, 0, dp(6), 0);
        row.addView(copy, lp);
        TextView ins = smallButton("✍️ Put in chat box", false);
        ins.setOnClickListener(v -> insert(s.text));
        row.addView(ins, new LinearLayout.LayoutParams(-2, dp(36)));
        c.addView(row);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(8);
        c.setLayoutParams(clp);
        return c;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF6E6E73);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private TextView smallButton(String s, boolean primary) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(dp(36));
        GradientDrawable g;
        if (primary) {
            g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF5F8F, 0xFFFF8A5C});
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            g = new GradientDrawable();
            g.setColor(0xFFF3F1F3);
            b.setTextColor(0xFF1D1D1F);
        }
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        return b;
    }

    // ------------------------------------------------------------------ actions

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Wingman", text));
        Toast.makeText(this, "Copied. Paste it in the app.", Toast.LENGTH_SHORT).show();
    }

    void insert(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo box = null;
        if (root != null) {
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty() && box == null) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                if (n.isEditable() && n.isVisibleToUser()) { box = n; break; }
                for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
            }
        }
        if (box == null) {
            copy(text);
            Toast.makeText(this, "No message box here. Open the chat (or tap the comment box) and paste.", Toast.LENGTH_LONG).show();
            return;
        }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok) {
            hidePanel();
            Toast.makeText(this, "Added. Edit it if you like, then hit send.", Toast.LENGTH_SHORT).show();
        } else {
            copy(text);
        }
    }
}
