package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glass's keyboard, made for a TV remote. Big keys on dark glass; the chosen key rises, white, so it's plain from the
 * sofa. ← → wrap around a row (from q, ← is the delete key), ↑ ↓ keep their column, OK types, and a held OK types the
 * capital. Words come up as you type (8,000 common English words, plus words you've typed); ↑ from the top row picks
 * one. The action key says what Enter does there (Search, Go, Send, Next, Done). Back closes the keyboard.
 *
 * With a phone connected, the phone's own keyboard types here directly (PhoneServer): the phone page opens its text
 * box by itself when the TV wants text. Nothing typed is kept, except the words for suggestions (never in password
 * fields), on the TV only.
 */
public final class GlassKeyboard extends InputMethodService {
    static volatile GlassKeyboard live;
    /** What the TV wants typed right now, for a phone ("text", "password", "email", "search", "url", "number"), or null. */
    static volatile String wants;
    static volatile String wantsHint;

    private Board board;
    private EditorInfo info;
    private final Handler h = new Handler(Looper.getMainLooper());
    private List<String> words;
    private final List<String> learned = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        Ui.init(this);
        Sound.init(this);
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        wants = null;
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;   // never the full-screen editor: the app's own box stays in view above the keys
    }

    @Override
    public View onCreateInputView() {
        board = new Board(this);
        return board;
    }

    @Override
    public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        info = ei;
        if (board == null) return;
        int cls = ei.inputType & InputType.TYPE_MASK_CLASS, var = ei.inputType & InputType.TYPE_MASK_VARIATION;
        boolean password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean email = var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        boolean url = var == InputType.TYPE_TEXT_VARIATION_URI;
        boolean number = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
        int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        board.setup(password, email, url, number, actionLabel(action, ei), (ei.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0);
        wants = password ? "password" : email ? "email" : url ? "url" : number ? "number" : action == EditorInfo.IME_ACTION_SEARCH ? "search" : "text";
        wantsHint = ei.hintText != null && !password ? ei.hintText.toString() : null;
        if (words == null) loadWords();
        board.suggest();
    }

    @Override
    public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        wants = null;
        wantsHint = null;
    }

    private static String actionLabel(int action, EditorInfo ei) {
        if (ei.actionLabel != null) return ei.actionLabel.toString();
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            default: return "Done";
        }
    }

    private void loadWords() {
        words = new ArrayList<>();
        new Thread(() -> {
            List<String> w = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("words.txt")))) {
                for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) w.add(line);
            } catch (Exception ignored) {
            }
            String saved = Glass.prefs(this).getString("kb_learned", "");
            List<String> l = new ArrayList<>();
            for (String s : saved.split(" ")) if (s.length() > 1) l.add(s);
            h.post(() -> {
                words = w;
                learned.clear();
                learned.addAll(l);
            });
        }, "glass-words").start();
    }

    // ---- typing -------------------------------------------------------------------------------------------------

    /** From a phone (PhoneServer): straight into the box the TV is typing in. False when no box wants text. */
    static boolean commitFromPhone(String text, boolean enter) {
        GlassKeyboard k = live;
        if (k == null || wants == null) return false;
        k.h.post(() -> {
            InputConnection ic = k.getCurrentInputConnection();
            if (ic == null) return;
            if (text != null && !text.isEmpty()) ic.commitText(text, 1);
            if (enter) k.performAction();
            if (k.board != null) k.board.suggest();
        });
        return true;
    }

    /** A phone's backspace. */
    static boolean deleteFromPhone() {
        GlassKeyboard k = live;
        if (k == null || wants == null) return false;
        k.h.post(() -> {
            InputConnection ic = k.getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        });
        return true;
    }

    private void type(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
    }

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else ic.deleteSurroundingText(1, 0);
    }

    private void move(int d) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) {
            sendDownUpKeyEvents(d < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            return;
        }
        int at = Math.max(0, Math.min(et.text.length(), et.selectionStart + d));
        ic.setSelection(at, at);
    }

    private void performAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || info == null) return;
        learn();
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multi = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (multi && action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_GO && action != EditorInfo.IME_ACTION_SEND) {
            ic.commitText("\n", 1);
            return;
        }
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
        if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO
                || action == EditorInfo.IME_ACTION_SEND) requestHideSelf(0);
    }

    /** The word being typed (letters just before the cursor). */
    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(32, 0);
        if (before == null) return "";
        int i = before.length();
        while (i > 0 && (Character.isLetter(before.charAt(i - 1)) || before.charAt(i - 1) == '\'')) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private boolean atSentenceStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence before = ic.getTextBeforeCursor(3, 0);
        if (before == null || before.length() == 0) return true;
        String b = before.toString().trim();
        return b.isEmpty() || b.endsWith(".") || b.endsWith("!") || b.endsWith("?");
    }

    private List<String> suggestions(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix.isEmpty() || words == null) return out;
        String p = prefix.toLowerCase(Locale.ROOT);
        for (int i = learned.size() - 1; i >= 0 && out.size() < 2; i--) {
            String w = learned.get(i);
            if (w.startsWith(p) && !w.equals(p) && !out.contains(w)) out.add(w);
        }
        for (String w : words) {
            if (out.size() >= 5) break;
            if (w.startsWith(p) && !w.equals(p) && !out.contains(w)) out.add(w);
        }
        // keep the case the person started with
        if (Character.isUpperCase(prefix.charAt(0))) {
            for (int i = 0; i < out.size(); i++) out.set(i, Character.toUpperCase(out.get(i).charAt(0)) + out.get(i).substring(1));
        }
        return out;
    }

    private void pick(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String cur = currentWord();
        ic.beginBatchEdit();
        if (!cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
        ic.commitText(word + " ", 1);
        ic.endBatchEdit();
        remember(word);
    }

    private void learn() {
        String w = currentWord();
        if (w.length() > 2) remember(w);
    }

    private void remember(String w) {
        if (board != null && board.password) return;   // never from a password box
        String lw = w.toLowerCase(Locale.ROOT);
        if (lw.length() < 3 || lw.length() > 24) return;
        learned.remove(lw);
        learned.add(lw);
        while (learned.size() > 200) learned.remove(0);
        Glass.prefs(this).edit().putString("kb_learned", android.text.TextUtils.join(" ", learned)).apply();
    }

    // ---- the remote ---------------------------------------------------------------------------------------------

    private long okDownAt;
    private boolean okRepeated;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (board == null || !isInputViewShown()) return super.onKeyDown(keyCode, e);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: board.nav(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: board.nav(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: board.nav(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: board.nav(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) {
                    okDownAt = SystemClock.uptimeMillis();
                    okRepeated = false;
                } else if (board.isDelete()) {
                    board.press(false);   // a held delete keeps deleting
                    okRepeated = true;
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
                backspace();
                board.suggest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                move(1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                return super.onKeyDown(keyCode, e);   // closes the keyboard
            default:
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    type(String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0)));
                    board.suggest();
                    return true;
                }
                return super.onKeyDown(keyCode, e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (board == null || !isInputViewShown()) return super.onKeyUp(keyCode, e);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (okDownAt != 0 && !okRepeated) board.press(!board.isDelete() && SystemClock.uptimeMillis() - okDownAt > 450);
            okDownAt = 0;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return super.onKeyUp(keyCode, e);
    }

    // ---- the board ----------------------------------------------------------------------------------------------

    /** The keys, drawn in one view (cheap on a TV): a row of suggestions, then four rows of keys. */
    private final class Board extends View {
        static final float H = 300, KH = 50, GAP = 8, TOP = 50, SIDE = 70;
        static final String DEL = "⌫", SHIFT = "⇧", SYM = "123", ABC = "ABC", SPACE = "space", LEFT = "‹", RIGHT = "›", ACT = "↵";
        private final String[][] LETTERS = {
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p", DEL},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'", ACT},
                {SHIFT, "z", "x", "c", "v", "b", "n", "m", ",", ".", "?"},
                {SYM, "@", SPACE, ".com", LEFT, RIGHT}};
        private final String[][] SYMBOLS = {
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", DEL},
                {"@", "#", "$", "_", "&", "-", "+", "(", ")", "/", ACT},
                {"*", "\"", ":", ";", "!", "?", "%", "=", ",", ".", "~"},
                {ABC, "€", SPACE, "₹", LEFT, RIGHT}};
        private String[][] keys = LETTERS;
        private final List<RectF[]> boxes = new ArrayList<>();
        private int row = 0, col = 0;         // row -1: the suggestions
        private int sugAt = 0;
        private List<String> sug = new ArrayList<>();
        private boolean shift, caps, symbols, password, email, url, autoCaps, phone;
        private String action = "Done";
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), key = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long lastMove;
        private float pop = 1f;   // the chosen key's rise, 0..1 (eases in after a move)
        private final Runnable easing = new Runnable() {
            @Override
            public void run() {
                pop = Math.min(1f, pop + 0.25f);
                invalidate();
                if (pop < 1f) postDelayed(this, 16);
            }
        };

        Board(Context c) {
            super(c);
            Typeface face = Ui.face(c, 500);
            label.setTypeface(face);
            label.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Ui.face(c, 500));
            small.setTextAlign(Paint.Align.CENTER);
            shade.setColor(0xFF000000);
            layout();
        }

        @Override
        protected void onMeasure(int w, int hh) {
            setMeasuredDimension(MeasureSpec.getSize(w), Ui.px(H));
        }

        void setup(boolean password, boolean email, boolean url, boolean number, String action, boolean autoCaps) {
            this.password = password;
            this.email = email;
            this.url = url;
            this.action = action;
            this.autoCaps = autoCaps;
            phone = !Phones.list(getContext()).isEmpty();
            symbols = number;
            keys = number ? SYMBOLS : LETTERS;
            LETTERS[3][3] = url || email ? ".com" : "-";
            LETTERS[3][1] = url ? "/" : "@";
            shift = false;
            caps = false;
            row = number ? 0 : 1;
            col = number ? 0 : 0;
            sugAt = 0;
            layout();
            invalidate();
        }

        private void layout() {
            boxes.clear();
            float width = 1280 - 2 * SIDE;
            for (int r = 0; r < keys.length; r++) {
                String[] rk = keys[r];
                float[] weights = new float[rk.length];
                float total = 0;
                for (int i = 0; i < rk.length; i++) {
                    String k = rk[i];
                    weights[i] = SPACE.equals(k) ? 4.6f : ".com".equals(k) ? 1.5f : SYM.equals(k) || ABC.equals(k) ? 1.5f : 1f;
                    total += weights[i];
                }
                float unit = (width - GAP * (rk.length - 1)) / total;
                float x = SIDE, y = TOP + 14 + r * (KH + GAP);
                RectF[] rb = new RectF[rk.length];
                for (int i = 0; i < rk.length; i++) {
                    float w = unit * weights[i];
                    rb[i] = new RectF(x, y, x + w, y + KH);
                    x += w + GAP;
                }
                boxes.add(rb);
            }
            col = Math.min(col, keys[Math.max(0, row)].length - 1);
        }

        boolean isDelete() {
            return row >= 0 && DEL.equals(keys[row][col]);
        }

        void suggest() {
            if (password) {
                sug = new ArrayList<>();
            } else {
                String w = currentWord();
                sug = suggestions(w);
                if (autoCaps && !caps && atSentenceStart() && !symbols) shift = true;
            }
            if (row < 0 && sug.isEmpty()) row = 0;
            sugAt = Math.min(sugAt, Math.max(0, sug.size() - 1));
            invalidate();
        }

        void nav(int dx, int dy) {
            Sound.tick();
            if (dy != 0) {
                if (row < 0) {
                    if (dy > 0) {
                        row = 0;
                        col = nearest(0, sugCentre(sugAt));
                    } else {
                        Sound.bump();
                    }
                } else {
                    int nr = row + dy;
                    if (nr < 0) {
                        if (!sug.isEmpty()) {
                            row = -1;
                            sugAt = nearestSug(boxes.get(0)[col].centerX());
                        } else {
                            Sound.bump();
                        }
                    } else if (nr >= keys.length) {
                        Sound.bump();
                    } else {
                        float cx = boxes.get(row)[col].centerX();
                        row = nr;
                        col = nearest(row, cx);
                    }
                }
            } else if (row < 0) {
                sugAt = (sugAt + dx + sug.size()) % sug.size();
            } else {
                col = (col + dx + keys[row].length) % keys[row].length;   // rows wrap
            }
            pop = 0.35f;
            removeCallbacks(easing);
            post(easing);
            lastMove = SystemClock.uptimeMillis();
        }

        private int nearest(int r, float cx) {
            int best = 0;
            RectF[] rb = boxes.get(r);
            for (int i = 1; i < rb.length; i++) if (Math.abs(rb[i].centerX() - cx) < Math.abs(rb[best].centerX() - cx)) best = i;
            return best;
        }

        private float sugCentre(int i) {
            float w = (1280 - 2 * SIDE) / 5f;
            return SIDE + w * i + w / 2;
        }

        private int nearestSug(float cx) {
            int best = 0;
            for (int i = 1; i < sug.size(); i++) if (Math.abs(sugCentre(i) - cx) < Math.abs(sugCentre(best) - cx)) best = i;
            return best;
        }

        /** OK on the chosen key; held: the capital (or the symbol's alternative). */
        void press(boolean held) {
            Sound.pop();
            if (row < 0) {
                if (sugAt < sug.size()) pick(sug.get(sugAt));
                row = 0;
                suggest();
                return;
            }
            String k = keys[row][col];
            switch (k) {
                case DEL: backspace(); break;
                case SHIFT:
                    if (shift && !caps) caps = true;
                    else if (caps) { caps = false; shift = false; }
                    else shift = true;
                    break;
                case SYM: symbols = true; keys = SYMBOLS; layout(); break;
                case ABC: symbols = false; keys = LETTERS; layout(); break;
                case SPACE: learn(); type(" "); break;
                case LEFT: move(-1); break;
                case RIGHT: move(1); break;
                case ACT: performAction(); break;
                default: {
                    String s = k;
                    if (k.length() == 1 && Character.isLetter(k.charAt(0)) && (shift || caps || held)) s = k.toUpperCase(Locale.getDefault());
                    if (",".equals(k) || ".".equals(k) || "?".equals(k) || "!".equals(k)) learn();
                    type(s);
                    if (shift && !caps) shift = false;
                }
            }
            suggest();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Ui.s;
            c.save();
            c.scale(s, s);
            // the glass: dark, a hairline of light along its top
            bg.setColor(0xF0141418);
            c.drawRect(0, 0, 1280, H, bg);
            bg.setColor(0x24FFFFFF);
            c.drawRect(0, 0, 1280, 1, bg);
            // suggestions, or what this box is for
            small.setTextSize(15 * Ui.fs);
            if (sug.isEmpty()) {
                small.setColor(0x80F2F2F5);
                small.setTextAlign(Paint.Align.LEFT);
                String hint = password ? "Password · the letters stay hidden in the box"
                        : !phone ? "Hold OK for a capital · ‹ › move the cursor · Back closes"
                        : "Your phone can type here too · Hold OK for a capital · Back closes";
                c.drawText(hint, SIDE + 4, 32, small);
                small.setTextAlign(Paint.Align.CENTER);
            } else {
                float w = (1280 - 2 * SIDE) / 5f;
                for (int i = 0; i < sug.size(); i++) {
                    RectF box = new RectF(SIDE + w * i + 4, 8, SIDE + w * (i + 1) - 4, 42);
                    boolean on = row < 0 && i == sugAt;
                    if (on) drawChosen(c, box, 12);
                    small.setColor(on ? 0xFF0E0E10 : 0xD9F2F2F5);
                    small.setTypeface(Ui.face(getContext(), on ? 600 : 500));
                    c.drawText(sug.get(i), box.centerX(), box.centerY() + 5.5f * Ui.fs, small);
                }
            }
            // keys
            for (int r = 0; r < keys.length; r++) {
                for (int i = 0; i < keys[r].length; i++) {
                    RectF box = boxes.get(r)[i];
                    boolean on = r == row && i == col;
                    String k = keys[r][i];
                    boolean special = k.length() > 1 && !".com".equals(k) || DEL.equals(k) || SHIFT.equals(k) || ACT.equals(k) || LEFT.equals(k) || RIGHT.equals(k);
                    if (on) {
                        drawChosen(c, box, 12);
                    } else {
                        key.setColor(ACT.equals(k) ? 0x40FFFFFF : special ? 0x1CFFFFFF : 0x2BFFFFFF);
                        c.drawRoundRect(box, 12, 12, key);
                    }
                    String text = keyText(k);
                    label.setColor(on ? 0xFF0E0E10 : 0xF2F2F2F5);
                    float size = text.length() > 2 ? 15 : 21;
                    label.setTextSize(size * Ui.fs * (on ? 1f + 0.06f * pop : 1f));
                    label.setTypeface(Ui.face(getContext(), text.length() > 2 ? 600 : 500));
                    c.drawText(text, box.centerX(), box.centerY() + size * 0.36f * Ui.fs, label);
                    if (SHIFT.equals(k) && (shift || caps)) {
                        key.setColor(on ? 0xFF0E0E10 : 0xFFF2F2F5);
                        c.drawRoundRect(box.centerX() - 9, box.bottom - 9, box.centerX() + 9, box.bottom - 6, 2, 2, key);
                        if (caps) c.drawRoundRect(box.centerX() - 9, box.bottom - 14, box.centerX() + 9, box.bottom - 11, 2, 2, key);
                    }
                }
            }
            c.restore();
        }

        /** The chosen key: white, a little bigger, on a soft shadow. */
        private void drawChosen(Canvas c, RectF box, float r) {
            float grow = 3f + 3f * pop;
            RectF b = new RectF(box.left - grow, box.top - grow, box.right + grow, box.bottom + grow);
            for (int i = 4; i >= 1; i--) {
                shade.setAlpha(Math.round(255 * 0.07f * pop));
                RectF sh = new RectF(b);
                sh.inset(-i * 1.5f, -i * 1.2f);
                sh.offset(0, 2 + i);
                c.drawRoundRect(sh, r + i, r + i, shade);
            }
            key.setColor(0xFFF5F5F7);
            c.drawRoundRect(b, r + 1, r + 1, key);
        }

        private String keyText(String k) {
            switch (k) {
                case SPACE: return "space";
                case ACT: return action;
                default:
                    if (k.length() == 1 && Character.isLetter(k.charAt(0)) && (shift || caps)) return k.toUpperCase(Locale.getDefault());
                    return k;
            }
        }
    }
}
