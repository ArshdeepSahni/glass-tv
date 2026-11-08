package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The desktop. Time-of-day wallpaper, the menu bar on top, the dock at the bottom, and whatever is open over them
 * (panels from the bar, windows from the dock, the switcher, All apps). Remote: the D-pad moves focus; OK opens;
 * Back closes the top thing; Menu opens Controls; Home closes everything and comes back here.
 */
public class GlassHomeActivity extends Activity implements Bar.Host, Dock.Host, Desk {
    static final String EXTRA_OPEN = "open";
    static final String APPEARANCE = "appearance";   // auto | light | dark
    static final String REDUCED = "reduced_motion";
    static final String DOCK = "dock";

    private static GlassHomeActivity live;
    private static boolean resumed, visible;
    private static boolean updateSaid;   // once per start of Glass
    private static String reopen;     // what to open again after a rebuild (look or text size changed)

    private FrameLayout root, layer;
    private ImageView wall;
    private Bar bar;
    private Dock dock;
    private Widgets.Board board;   // the desktop's widgets (Settings ▸ Backgrounds & savers), or null
    private Wake.Film film;   // Glass waking up: the cover, then the film, over everything (Wake)
    private boolean night;
    private float builtFs;
    private boolean rebuildPending;
    private final List<Sheet> sheets = new ArrayList<>();
    private BroadcastReceiver ticks;
    private ConnectivityManager.NetworkCallback net;

    /** The desktop is on screen (perhaps under the assistant, which is translucent). */
    static boolean showing() {
        return visible;
    }

    /** From the assistant (DeskProvider): its item in the bar stays lit while it's up. */
    static void assistShowing(boolean on) {
        GlassHomeActivity a = live;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.bar == null) return;
            Sheet top = a.top();
            a.bar.setOpen(on ? "assist" : top != null ? top.barKey : null);
        });
    }

    /** An open Control Center on the desktop catches up with a change made elsewhere (the remote's Vol+/-). */
    static void refreshControls() {
        GlassHomeActivity a = live;
        if (a == null) return;
        for (Sheet s : a.sheets) if (s instanceof ControlsSheet && !s.closing) ((ControlsSheet) s).refresh();
        if (a.bar != null) a.bar.refresh();
    }

    /** The look was changed from a phone: the desktop changes with it (a sheet open on it waits, then it does). */
    static void appearanceChanged() {
        GlassHomeActivity a = live;
        if (a != null) a.checkLook();
        HomeKeyService svc = HomeKeyService.current();
        if (svc != null) Hud.of(svc).lookChanged();
    }

    static void screenOff() {
        GlassHomeActivity a = live;
        if (a != null) a.runOnUiThread(a::dismissAll);
    }

    /** Glass waking up (Wake): the screen went off, so the desktop is covered and never shows before the film. */
    static void wakeCover() {
        GlassHomeActivity a = live;
        if (a != null) a.runOnUiThread(a::cover);
    }

    /** The screen came on: a desktop already in front plays the film now (one that comes up later does on resume). */
    static void wakeNow() {
        GlassHomeActivity a = live;
        if (a != null && resumed) a.runOnUiThread(a::wake);
    }

    /** A key: the film gives way at once. */
    static void wakeSkip() {
        GlassHomeActivity a = live;
        if (a != null && a.film != null) a.runOnUiThread(() -> {
            if (a.film != null) a.film.skip();
        });
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        live = this;
        Sample.load(this);
        Sound.init(this);
        Wake.bootCheck(this);   // the first desktop of a boot opens with the film
        build();
        handle(getIntent());
        ticks = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_TIME_TICK.equals(i.getAction()) || Intent.ACTION_TIME_CHANGED.equals(i.getAction())) checkLook();
                if (bar != null) bar.refresh();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction("android.media.VOLUME_CHANGED_ACTION");
        f.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        registerReceiver(ticks, f);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            net = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { runOnUiThread(() -> { if (bar != null) bar.refresh(); }); }
                @Override public void onLost(Network n) { runOnUiThread(() -> { if (bar != null) bar.refresh(); }); }
            };
            try {
                cm.registerDefaultNetworkCallback(net);
            } catch (RuntimeException ignored) {
                net = null;
            }
        }
    }

    private SharedPreferences prefs() {
        return Glass.prefs(this);
    }

    private void build() {
        Ui.init(this);
        Ui.reduced = prefs().getBoolean(REDUCED, false);
        night = Ui.night(prefs().getString(APPEARANCE, "auto"));
        Ui.tone(night);
        builtFs = Ui.fs;
        sheets.clear();
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(wall, Ui.fill());
        Bitmap w = Wall.peek(night);
        if (w != null) {
            wall.setImageBitmap(w);
        } else {
            // the stand-in sits on the root, under the picture fading in (the window itself has no fill behind)
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    night ? new int[]{0xFF0B1022, 0xFF141A33, 0xFF22182C, 0xFF2A1A22} : new int[]{0xFFA9C6E4, 0xFFC9D9EA, 0xFFEBDCCB, 0xFFD7B9A1}));
            final boolean n = night;
            final Context app = getApplicationContext();
            Ui.BG.execute(() -> {
                Bitmap b = Wall.get(app, n);
                Wall.forgetOther(n);
                runOnUiThread(() -> {
                    if (n != night || isFinishing()) return;
                    wall.setImageBitmap(b);
                    wall.setAlpha(0f);
                    final View r = root;
                    wall.animate().alpha(1f).setDuration(Ui.ms(240)).withEndAction(() -> r.setBackground(null)).start();
                });
            });
        }
        board = Widgets.board(this, this);
        if (board != null) root.addView(board.view, Ui.fill());
        bar = new Bar(this, this);
        root.addView(bar, Ui.at(0, 0, 1280, 52));
        dock = new Dock(this, this);
        root.addView(dock, Ui.fill());
        dock.setItems(dockItems());
        layer = new FrameLayout(this);
        layer.setClipChildren(false);
        root.addView(layer, Ui.fill());
        film = null;
        if (Wake.primed()) cover();   // the TV is waking (or just started): nothing of the desktop before the film
        setContentView(root);
        Ui.marquee(root);   // words cut short scroll into view while focused
        // the wallpaper covers every pixel: the theme's black behind it would be a second full-screen fill each frame
        getWindow().setBackgroundDrawable(null);
        View first = dock.first();
        if (first != null) first.post(first::requestFocus);
        if (reopen != null) {
            String r = reopen;
            reopen = null;
            root.post(() -> open(r));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent i) {
        // the TV's original home is in charge (Settings ▸ Glass & original home): Home goes there, not here
        if (i != null && i.hasCategory(Intent.CATEGORY_HOME) && i.getStringExtra(EXTRA_OPEN) == null && Glass.original(this)) {
            GlassKeys.start(this, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(Homes.stock(this)));
            return;
        }
        // for the person building Glass, through the TV's debugging: a look to show, and a check of the screen's layout
        String look = i == null ? null : i.getStringExtra("look");
        if (look != null && (look.equals("light") || look.equals("dark") || look.equals("auto"))) {
            i.removeExtra("look");
            if (!look.equals(prefs().getString(APPEARANCE, "auto"))) {
                prefs().edit().putString(APPEARANCE, look).apply();
                rebuild(i.getStringExtra(EXTRA_OPEN));
                i.removeExtra(EXTRA_OPEN);
                return;
            }
        }
        String sample = i == null ? null : i.getStringExtra("sample");
        if (sample != null && (sample.equals("on") || sample.equals("off"))) {
            i.removeExtra("sample");
            if ((sample.equals("on")) != Sample.on) {
                Sample.set(this, sample.equals("on"));
                rebuild(i.getStringExtra(EXTRA_OPEN));
                i.removeExtra(EXTRA_OPEN);
                return;
            }
        }
        String audit = i == null ? null : i.getStringExtra("audit");
        if (audit != null) {
            i.removeExtra("audit");
            root.postDelayed(() -> Audit.run(audit, getWindow().getDecorView()), 1600);
        }
        String what = i == null ? null : i.getStringExtra(EXTRA_OPEN);
        if (i != null) i.removeExtra(EXTRA_OPEN);
        if (what == null) {
            // Home: everything closes, focus comes back to the dock (a widget being moved is put down)
            if (board != null) board.done();
            dismissAll();
            View first = dock == null ? null : dock.first();
            if (first != null && !dock.hasFocusInside()) first.post(first::requestFocus);
            return;
        }
        root.post(() -> open(what));
    }

    /** Opens one of the desktop's places by name. */
    void open(String what) {
        switch (what) {
            case "switcher": openSwitcher(); break;
            case "controls": openControls(); break;
            case "controls:look":
            case "controls:text":
                if (!(top() instanceof ControlsSheet)) show(new ControlsSheet(this, "main", "controls").focusOn(what.substring(9)), bar.item("controls"));
                break;
            case "apps": openApps(); break;
            case "search": openSearch(); break;
            case "files": openFiles(dock.cellFor(AppIcons.FILES)); break;
            case "inputs": openInputs(); break;
            case "phone": show(new PhoneSheet(this), null); break;
            case "weather": show(new WeatherSheet(this, false), null); break;
            case "wake": playFilm(); break;
            case "keys": Keys.warm(this); break;   // the phone's key helper, started now (the installer)
            case "phone-allow":
                if (PhoneSheet.Allow.pending != null) show(new PhoneSheet.Allow(this, PhoneSheet.Allow.pending), null);
                PhoneSheet.Allow.pending = null;
                break;
            default:
                if (what.startsWith("settings")) openSettings(what.contains(":") ? what.substring(what.indexOf(':') + 1) : null);
                else if (what.startsWith("show:")) Show.open(this, what.substring(5));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        if (film != null && film.started()) film.cut();   // another app came up mid-film: the desktop is whole under it
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Ui.readScale(this);
        Glass.onFront(getPackageName(), getPackageName());   // in case the helper missed our window
        if (checkLook()) return;
        wake();
        bar.refresh();
        if (board != null) board.start();
        if (!updateSaid) {   // Glass just replaced itself with a newer version: say so once
            updateSaid = true;
            getWindow().getDecorView().postDelayed(() -> {
                String said = Updates.afterRestart(this);
                if (said == null) return;
                HomeKeyService svc = HomeKeyService.current();
                if (svc != null) Hud.of(svc).note("Glass", said, "check");
                else android.widget.Toast.makeText(this, said, android.widget.Toast.LENGTH_LONG).show();
            }, 2500);
        }
        Homes.tidy(this);
        dock.setRunning(running());
        Running.refresh(this, pkgs -> {
            if (live == this) dock.setRunning(pkgs);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (board != null) board.stop();
    }

    @Override
    protected void onDestroy() {
        if (live == this) live = null;
        try { unregisterReceiver(ticks); } catch (RuntimeException ignored) { }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && net != null) try { cm.unregisterNetworkCallback(net); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fontScaleChanged();
    }

    /** True when the look (day/night) or text size changed and the desktop is being rebuilt. */
    private boolean checkLook() {
        boolean n = Ui.night(prefs().getString(APPEARANCE, "auto"));
        float fs = Math.max(0.85f, Math.min(1.3f, getResources().getConfiguration().fontScale));
        if (n == night && Math.abs(fs - builtFs) < 0.01f) return false;
        if (!sheets.isEmpty()) {
            rebuildPending = true;
            return false;
        }
        rebuild(null);
        return true;
    }

    private void rebuild(String thenOpen) {
        reopen = thenOpen;
        recreate();
    }

    @Override
    public void lookChanged() {
        rebuild("settings:display:Appearance");
    }

    @Override
    public void lookChanged(String reopen) {
        rebuild(reopen);
    }

    @Override
    public void fontScaleChanged() {
        float fs = Math.max(0.85f, Math.min(1.3f, getResources().getConfiguration().fontScale));
        if (Math.abs(fs - builtFs) < 0.01f) return;
        Sheet top = top();
        String again = top instanceof ControlsSheet ? "controls:text" : top instanceof SettingsSheet ? "settings:display:Text size" : null;
        rebuild(again);
    }

    @Override
    public Bar bar() {
        return bar;
    }

    @Override
    public Context context() {
        return this;
    }

    @Override
    public float panelTop() {
        return Ui.PANEL_Y;
    }

    @Override
    public boolean overApp() {
        return false;
    }

    // ---- waking up ------------------------------------------------------------------------------------------

    /** The black cover, on top of everything, until the film plays (or turns out not to). */
    private void cover() {
        if (film != null || root == null) return;
        film = new Wake.Film(this);
        root.addView(film, Ui.fill());
    }

    /** The desktop came up: the film if the TV is waking, the cover off if it isn't, or wait for the screen. */
    private void wake() {
        int d = Wake.decide(this);
        if (d == Wake.PLAY) {
            playFilm();
        } else if (d == Wake.NONE && film != null && !film.started()) {
            root.removeView(film);
            film = null;
        } else if (d == Wake.WAIT) {
            // in front with the screen still off (a TV that starts in standby): look again until it comes on
            root.postDelayed(() -> {
                if (live == this && resumed) wake();
            }, 1500);
        }
    }

    private void playFilm() {
        if (film != null && film.started()) return;
        dismissAll();
        cover();
        final Wake.Film f = film;
        f.play(night, () -> {
            if (film != f) return;
            film = null;
            if (Wake.primed()) cover();   // the screen went off again mid-film: ready for the next wake
        }, board != null ? new View[]{bar, dock, board.view} : new View[]{bar, dock});
    }

    // ---- sheets ---------------------------------------------------------------------------------------------

    Sheet top() {
        return sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
    }

    @Override
    public void show(Sheet s, View anchor) {
        // focus goes back to whatever had it (the dock cell when Menu opened Controls), not always to the opener
        View had = getCurrentFocus();
        View back = had != null ? had : anchor;
        for (Sheet o : new ArrayList<>(sheets)) {
            boolean keep = o.window && !s.window && !s.overlay;
            if (!keep) {
                if (had != null && o.root != null && o.root.inside(had)) back = o.anchor != null ? o.anchor : anchor;
                dismiss(o, false);
            }
        }
        s.anchor = back;
        s.attach(layer);
        sheets.add(s);
        bar.setOpen(s.barKey);
        if (s.window) bar.setContext(s instanceof FilesSheet ? "Files" : "Settings");
        s.animateIn();
        if (s instanceof SearchSheet) ((SearchSheet) s).showKeyboard();
        else if (s instanceof AppsSheet) ((AppsSheet) s).focusFirst();
        else {
            View f = s.firstFocus();
            if (f != null) f.requestFocus();
        }
    }

    /** A menu over a sheet (an app's options in All apps): the sheet under it stays. */
    @Override
    public void stack(Sheet s, View anchor) {
        s.anchor = anchor;
        s.attach(layer);
        sheets.add(s);
        s.animateIn();
        View f = s.firstFocus();
        if (f != null) f.requestFocus();
    }

    @Override
    public void dismiss(Sheet s) {
        dismiss(s, true);
    }

    private void dismiss(Sheet s, boolean refocus) {
        if (s == null || s.closing || !sheets.contains(s)) return;
        sheets.remove(s);
        s.onClosed();
        final View r = s.root;
        s.animateOut(() -> layer.removeView(r));
        Sheet top = top();
        bar.setOpen(top != null ? top.barKey : null);
        boolean windowOpen = false;
        for (Sheet o : sheets) if (o.window) windowOpen = true;
        if (!windowOpen) bar.setContext("Home");
        if (refocus) {
            View back = s.anchor;
            boolean usable = back != null && back.isAttachedToWindow() && back.isShown() && (top == null || top.root.inside(back));
            if (usable) {
                back.requestFocus();
            } else if (top != null) {
                View f = top.firstFocus();
                if (f != null) f.requestFocus();
                else if (top instanceof AppsSheet) ((AppsSheet) top).focusFirst();
            } else if (dock.first() != null) {
                dock.first().requestFocus();
            }
        }
        if (sheets.isEmpty() && rebuildPending) {
            rebuildPending = false;
            root.postDelayed(() -> { if (sheets.isEmpty()) rebuild(null); }, Ui.ms(360));
        }
    }

    void dismissAll() {
        for (Sheet o : new ArrayList<>(sheets)) dismiss(o, false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (film != null) {   // waking up: any key ends the film, and does nothing else
            if (e.getAction() == KeyEvent.ACTION_DOWN) film.skip();
            return true;
        }
        // a widget being moved takes the arrows, OK and Back until it's put down
        if (board != null && board.editing() && (top() == null || top().closing) && board.key(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            Sheet top = top();
            if (top != null && !top.closing && top.onKey(e)) return true;
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_BACK) return true;
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!e.isCanceled()) {
                    Sheet top = top();
                    if (top != null && !top.onBack()) dismiss(top);
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) {
                if (!e.isCanceled()) {
                    if (top() instanceof ControlsSheet) dismiss(top());
                    else openControls();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- opening things -------------------------------------------------------------------------------------

    @Override
    public float barCenter(String key) {
        View v = bar.item(key);
        if (v == null) return 1062;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return (loc[0] + v.getWidth() / 2f) / Ui.s;
    }

    private float[] barSpan(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new float[]{loc[0] / Ui.s, (loc[0] + v.getWidth()) / Ui.s};
    }

    @Override
    public void openControls() {
        if (top() instanceof ControlsSheet) return;
        show(new ControlsSheet(this, "main", "controls"), bar.item("controls"));
    }

    @Override
    public void openSearch() {
        if (top() instanceof SearchSheet) return;
        show(new SearchSheet(this), bar.item("search"));
    }

    @Override
    public void openApps() {
        if (top() instanceof AppsSheet) return;
        show(new AppsSheet(this), dock.cellFor(AppIcons.ALL_APPS));
    }

    void openSwitcher() {
        if (top() instanceof SwitcherSheet) return;
        show(new SwitcherSheet(this), dock.first());
    }

    void openFiles(View cell) {
        if (top() instanceof FilesSheet) return;
        FilesSheet s = new FilesSheet(this);
        View from = cell != null ? cell : dock.cellFor(AppIcons.FILES);
        float[] o = dock.center(from);
        s.ox = o[0];
        s.oy = o[1];
        show(s, from);
    }

    @Override
    public void openSettings(String section) {
        if (top() instanceof SettingsSheet) dismiss(top(), false);
        SettingsSheet s = new SettingsSheet(this, section);
        View from = dock.cellFor(AppIcons.SETTINGS);
        float[] o = dock.center(from);
        s.ox = o[0];
        s.oy = o[1];
        show(s, from);
        if (section != null) root.post(s::focusRows);
    }

    @Override
    public void openInputs() {
        menuUnder("inputs", bar.item("inputs"), inputRows(), 300, false);
    }

    private void menuUnder(String key, View item, List<Sheet.Row> rows, float w, boolean right) {
        if (rows.isEmpty() || item == null) return;
        float[] span = barSpan(item);
        float x = right ? span[1] - w : span[0];
        x = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - w, x));
        Sheet.Menu m = new Sheet.Menu(this, rows, x, Ui.PANEL_Y, w, false);
        m.barKey = key;
        m.dropX = (span[0] + span[1]) / 2;
        show(m, item);
    }

    private List<Sheet.Row> inputRows() {
        List<Sheet.Row> rows = new ArrayList<>();
        for (GlassInputs.Input in : GlassInputs.list(this)) {
            String detail = in.on ? "On" : in.empty ? null : in.title.equals(in.port) ? null : in.port;
            rows.add(new Sheet.Row(in.hdmi ? "input" : "tv", in.title, detail, () -> GlassInputs.open(this, in.id)));
        }
        String liveTv = Config.load(this).liveTvPackage;
        if (liveTv != null && !liveTv.isEmpty() && launchable(liveTv)) rows.add(new Sheet.Row("live", "Live TV", null, () -> launch(liveTv)));
        rows.add(new Sheet.Row("sliders", "Input settings…", null, () -> openSystem("android.settings.TV_INPUT_SETTINGS")));
        return rows;
    }

    @Override
    public void barOpen(String key, View item) {
        List<Sheet.Row> rows = new ArrayList<>();
        switch (key) {
            case "mark":
                rows.add(new Sheet.Row("info", "About this TV", null, () -> openSettings("about")));
                rows.add(new Sheet.Row("sliders", "Settings…", null, () -> openSettings(null)));
                rows.add(new Sheet.Row("clock", "Recent apps", "hold Home", this::openSwitcher));
                rows.add(new Sheet.Row("remote", "Remote guide", null, () -> barOpen("guide", item)));
                menuUnder("mark", item, rows, 260, false);
                break;
            case "guide":
                rows.add(new Sheet.Row("home", "Home", "the desktop", null));
                rows.add(new Sheet.Row("clock", "Hold Home", "recent apps", null));
                rows.add(new Sheet.Row("menu", "Menu", "Control Center", null));
                rows.add(new Sheet.Row("input", "Input", "sources", null));
                rows.add(new Sheet.Row("assist", "Assistant button", "ask Glass", null));
                rows.add(new Sheet.Row("volume", "Volume", "the volume pill", null));
                rows.add(new Sheet.Row("remote", "Hold OK", "options", null));
                menuUnder("mark", bar.item("mark"), rows, 300, false);
                break;
            case "inputs":
                menuUnder(key, item, inputRows(), 300, false);
                break;
            case "wifi":
                if (top() instanceof ControlsSheet) dismiss(top(), false);
                show(new ControlsSheet(this, "wifi", "wifi"), item);
                break;
            case "volume":
                if (top() instanceof ControlsSheet) dismiss(top(), false);
                show(new ControlsSheet(this, "sound", "volume"), item);
                break;
            case "controls":
                openControls();
                break;
            case "search":
                openSearch();
                break;
            case "assist":
                ask(null);
                break;
        }
    }

    // ---- the dock -------------------------------------------------------------------------------------------

    @Override
    public void dockOpen(Dock.Item it, View cell) {
        switch (it.pkg) {
            case AppIcons.FILES: openFiles(cell); break;
            case AppIcons.SETTINGS: openSettings(null); break;
            case AppIcons.ALL_APPS: openApps(); break;
            default: launch(it.pkg, cell);
        }
    }

    @Override
    public void dockMenu(Dock.Item it, View cell) {
        float[] cxy = dock.center(cell);
        List<Sheet.Row> rows = new ArrayList<>();
        if (running().contains(it.pkg)) rows.add(new Sheet.Row("close", "Close app", null, () -> Running.close(this, it.pkg, this::runningChanged)));
        rows.add(new Sheet.Row("left", "Move left", null, () -> move(it.pkg, -1)));
        rows.add(new Sheet.Row("right", "Move right", null, () -> move(it.pkg, 1)));
        rows.add(new Sheet.Row("unpin", "Remove from dock", null, () -> keepInDock(it.pkg, false)));
        rows.add(new Sheet.Row("info", "App info", null, () -> appInfo(it.pkg)));
        float x = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - 220, cxy[0] - 110));
        show(new Sheet.Menu(this, rows, x, Dock.FOCUS_TOP - 52, 220, true), cell);
    }

    @Override
    public void appMenu(String pkg, View from) {
        boolean inDock = dockPackages().contains(pkg);
        List<Sheet.Row> rows = new ArrayList<>();
        if (running().contains(pkg)) rows.add(new Sheet.Row("close", "Close app", null, () -> Running.close(this, pkg, this::runningChanged)));
        rows.add(new Sheet.Row(inDock ? "unpin" : "pin", inDock ? "Remove from dock" : "Keep in dock", null, () -> keepInDock(pkg, !inDock)));
        rows.add(new Sheet.Row("info", "App info", null, () -> appInfo(pkg)));
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float x = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - 220, loc[0] / Ui.s - 35));
        float y = Math.min(720 - 16 - 96, loc[1] / Ui.s + 110);
        stack(new Sheet.Menu(this, rows, x, y, 220, false), from);
    }

    private List<String> dockPackages() {
        List<String> out = new ArrayList<>();
        for (Dock.Item it : dock.apps()) out.add(it.pkg);
        return out;
    }

    private void saveDock(List<String> pkgs) {
        // the sample dock is for pictures: the owner's own dock stays as it was
        if (!Sample.on) prefs().edit().putString(DOCK, android.text.TextUtils.join(",", pkgs)).apply();
        dock.setItems(dockItems());
        dock.setRunning(running());
    }

    private void move(String pkg, int dir) {
        List<String> pkgs = dockPackages();
        int i = pkgs.indexOf(pkg), j = i + dir;
        if (i < 0 || j < 0 || j >= pkgs.size()) return;
        Collections.swap(pkgs, i, j);
        saveDock(pkgs);
        View cell = dock.cellFor(pkg);
        if (cell != null) cell.post(cell::requestFocus);
    }

    private void keepInDock(String pkg, boolean keep) {
        List<String> pkgs = dockPackages();
        if (keep && !pkgs.contains(pkg)) pkgs.add(pkg);
        if (!keep) pkgs.remove(pkg);
        saveDock(pkgs);
        if (top() == null && dock.first() != null) dock.first().requestFocus();
    }

    @Override
    public void resetDock() {
        prefs().edit().remove(DOCK).apply();
        dock.setItems(dockItems());
        dock.setRunning(running());
        Toast.makeText(this, "Dock reset", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void focusDock() {
        View first = dock == null ? null : dock.first();
        if (first != null) first.requestFocus();
    }

    /** Settings turned a widget on or off, or the weather's place changed: the board is made again where it is. */
    @Override
    public void widgetsChanged() {
        if (root == null) return;
        int at = board != null ? root.indexOfChild(board.view) : root.indexOfChild(wall) + 1;
        if (board != null) {
            board.stop();
            root.removeView(board.view);
        }
        board = Widgets.board(this, this);
        if (board != null) {
            root.addView(board.view, Math.max(1, at), Ui.fill());
            if (resumed) board.start();
        }
    }

    /** ↑ from the dock: the widget right above the icon, if there is one; otherwise the bar, as always. */
    @Override
    public boolean dockUp(View cell) {
        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        float x0 = loc[0] / Ui.s, x1 = (loc[0] + cell.getWidth()) / Ui.s;
        View w = board == null ? null : board.above(x0, x1);
        if (w != null) return w.requestFocus();
        View b = bar.nearest((x0 + x1) / 2);
        return b != null && b.requestFocus();
    }

    @Override
    public void runningChanged() {
        if (dock != null) dock.setRunning(running());
    }

    @Override
    public void openAppInfo(String pkg) {
        appInfo(pkg);
    }

    private void appInfo(String pkg) {
        try {
            startActivity(SystemSettings.appInfo(this, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "App info isn't available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private List<Dock.Item> dockItems() {
        String saved = prefs().getString(DOCK, null);
        List<String> pkgs = new ArrayList<>();
        if (saved != null) {
            for (String p : saved.split(",")) if (!p.isEmpty()) pkgs.add(p);
        } else {
            pkgs = defaults();
        }
        if (Sample.on) {   // pictures: the well-known apps the TV has, never its owner's own choice
            pkgs = new ArrayList<>(java.util.Arrays.asList(Sample.APPS));
            pkgs.add(3, Config.load(this).liveTvPackage);
        }
        List<Dock.Item> out = new ArrayList<>();
        for (String p : pkgs) {
            if (p == null || p.isEmpty() || !launchable(p) || !Sample.shows(this, p)) continue;
            CharSequence l = Glass.label(this, p);
            out.add(new Dock.Item(p, l == null ? p : l.toString()));
            if (out.size() >= 9) break;
        }
        return out;
    }

    /** The first time: the TV's own big apps where installed, then the ones you open most. */
    private List<String> defaults() {
        String[][] wanted = {
                {"com.google.android.youtube.tv", "com.google.android.youtube"},
                {"com.netflix.ninja"},
                {"com.amazon.amazonvideo.livingroom"},
                {"in.startv.hotstar", "in.startv.hotstartvonly", "com.jio.jiohotstar"},
                {Config.load(this).liveTvPackage},
                {"com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.smarttube.stable", "org.smarttube.beta"},
                {"com.phlox.tvwebbrowser", "com.android.chrome", "org.mozilla.tv.firefox", "com.cloudmosa.puffinTV", "com.opera.browser"}};
        List<String> out = new ArrayList<>();
        for (String[] choice : wanted) {
            for (String p : choice) {
                if (p != null && !p.isEmpty() && launchable(p)) { out.add(p); break; }
            }
        }
        for (String p : Config.load(this).dockApps) if (out.size() < 7 && !out.contains(p) && launchable(p)) out.add(p);
        for (String[] r : LastOpened.recent(this, 10)) if (out.size() < 7 && !out.contains(r[0]) && getPackageManager().getLeanbackLaunchIntentForPackage(r[0]) != null) out.add(r[0]);
        return out;
    }

    private boolean launchable(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The apps open in the background (the dock's dashes): see Running. */
    private Set<String> running() {
        return Running.quick(this);
    }

    /** {label, package} for every app, A to Z. */
    @Override
    public List<String[]> allApps() {
        return apps(this);
    }

    /** {label, package} for every TV app, A to Z. */
    static List<String[]> apps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // TV apps only: the phone-style entries on this firmware are setup wizards and vendor helpers
        List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<String> hidden = Config.load(ctx).hiddenApps;
        String liveTv = Config.load(ctx).liveTvPackage;
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || pkg.startsWith("com.gathin.glass") || !seen.add(pkg)) continue;
            if (hidden.contains(pkg) && !pkg.equals(liveTv)) continue;
            if (!Sample.shows(ctx, pkg)) continue;
            CharSequence l = Glass.label(ctx, pkg);
            apps.add(new String[]{l == null ? pkg : l.toString(), pkg});
        }
        Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        return apps;
    }

    // ---- leaving the desktop --------------------------------------------------------------------------------

    void launch(String pkg) {
        launch(pkg, null);
    }

    /** Opens an app, zooming it out of `from` (its icon) when there is one. */
    @Override
    public void launch(String pkg, View from) {
        if (!Glass.open(this, pkg, from)) Toast.makeText(this, "Couldn't open that app", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void openFile(File f) {
        Files.open(this, f, false);
    }

    @Override
    public void openSystem(String action) {
        // pages Glass has itself, nicer than the TV's (which has none of its own for these on Android TV 9)
        if (Settings.ACTION_BLUETOOTH_SETTINGS.equals(action)) {
            show(new ControlsSheet(this, "bluetooth", "controls"), null);
            return;
        }
        if (Settings.ACTION_DREAM_SETTINGS.equals(action) || Settings.ACTION_DISPLAY_SETTINGS.equals(action)) {
            openSettings("look");
            return;
        }
        try {
            startActivity(SystemSettings.intent(this, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                startActivity(SystemSettings.intent(this, Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e2) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** The assistant: listening, or straight to a typed question. */
    @Override
    public void ask(String q) {
        Intent i = getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT);
        if (i == null) {
            Toast.makeText(this, "The assistant isn't installed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (q != null) i.putExtra("q", q); else i.putExtra("voice", true);
        GlassKeys.start(this, i);
    }
}
