package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Glass II All apps: every TV app as a word, alphabetical, in three columns. Typing a letter on any keyboard
 * filters the list; hold OK opens the app's system info page (uninstall, storage, force stop).
 */
public final class GlassAllAppsActivity extends Activity {
    private float s;
    private Glass.Palette p;
    private final List<String[]> apps = new ArrayList<>();   // {label, package}
    private LinearLayout columns;
    private TextView hint;
    private final StringBuilder query = new StringBuilder();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private int px(float v) { return Math.round(v * s); }

    private TextView text(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1280f;
        p = Glass.palette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(p.ground);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBaselineAligned(true);
        TextView title = text(56, p.ink, Glass.serif(this));
        title.setText("All apps");
        head.addView(title);
        hint = text(17, p.faint, Glass.sans(this));
        hint.setText("Type to find");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = px(18);
        head.addView(hint, hlp);
        FrameLayout.LayoutParams headLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.leftMargin = px(64);
        headLp.topMargin = px(48);
        root.addView(head, headLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(columns);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.leftMargin = px(64);
        clp.rightMargin = px(64);
        clp.topMargin = px(140);
        clp.bottomMargin = px(32);
        root.addView(scroll, clp);
        setContentView(root);

        PackageManager pm = getPackageManager();
        List<String> hidden = Config.load(this).hiddenApps;
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
            CharSequence l = Glass.label(this, pkg);
            apps.add(new String[]{l == null ? pkg : l.toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        render();
    }

    private void render() {
        columns.removeAllViews();
        String q = query.toString().toLowerCase(Locale.ROOT);
        List<String[]> shown = new ArrayList<>();
        for (String[] a : apps) if (q.isEmpty() || a[0].toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
        hint.setText(q.isEmpty() ? "Type to find" : "“" + query + "” · Back clears");
        int per = Math.max(1, (shown.size() + 2) / 3);
        View first = null;
        for (int c = 0; c < 3; c++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            int from = c * per, to = Math.min(shown.size(), from + per);
            if (from < to) {
                TextView head = text(13, p.faint, Glass.sans(this));
                head.setLetterSpacing(0.12f);
                head.setText(range(shown.get(from)[0], shown.get(to - 1)[0]));
                head.setPadding(0, px(8), 0, px(8));
                col.addView(head);
            }
            for (int i = from; i < to; i++) {
                View w = word(shown.get(i));
                if (first == null) first = w;
                col.addView(w);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = c < 2 ? px(48) : 0;
            columns.addView(col, lp);
        }
        if (first != null) first.requestFocus();
    }

    private static String range(String a, String b) {
        String x = a.isEmpty() ? "" : a.substring(0, 1).toUpperCase(Locale.ROOT);
        String y = b.isEmpty() ? "" : b.substring(0, 1).toUpperCase(Locale.ROOT);
        return x.equals(y) ? x : x + " – " + y;
    }

    private View word(String[] app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setPadding(0, px(6), 0, px(6));
        TextView t = text(30, Glass.withAlpha(p.ink, 0.5f), Glass.sansMedium(this));
        t.setLetterSpacing(-0.025f);
        t.setText(app[0]);
        box.addView(t);
        View line = new View(this);
        line.setBackgroundColor(p.accent);
        line.setVisibility(View.INVISIBLE);
        box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.max(2, px(3))));
        box.setOnFocusChangeListener((v, has) -> {
            t.setTextColor(has ? p.ink : Glass.withAlpha(p.ink, 0.5f));
            line.getLayoutParams().width = t.getWidth();
            line.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            line.requestLayout();
        });
        box.setOnClickListener(v -> { if (Glass.open(this, app[1])) finish(); });
        box.setOnLongClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app[1])));
            } catch (RuntimeException ignored) { }
            return true;
        });
        return box;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && query.length() > 0) {
            query.setLength(0);
            render();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && query.length() > 0) {
            query.setLength(query.length() - 1);
            render();
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch != 0 && Character.isLetterOrDigit(ch)) {
            query.append((char) ch);
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
