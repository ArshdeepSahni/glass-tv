package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** Without stock_home_redirect: the packages of the TV's own home screens (stockHome's), whichever they are. */
    private final Set<String> ownHomes = new HashSet<>();
    /** The TV's own home apps give way to Glass when the TV opens them itself, at a start or a wake (ownHomeApp). */
    private boolean ownAppsAtPowerOn;
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries, and the package of the activity in front, from window changes while there are any. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    private String frontPackage = "";
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    private BroadcastReceiver screenOffReceiver, volumeReceiver;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    private static HomeKeyService running;

    /** Glass: the bound helper, for the sleep timer and the Palette; null while it is off. */
    static HomeKeyService current() { return running; }

    private static boolean glassBootAsked;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        Ui.init(this);
        Sound.init(this);
        PhoneServer.start(this);   // Glass on a phone, on the home Wi-Fi (only phones the TV allowed)
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        Dim.release();
        PhoneServer.stop();
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (screenOffReceiver != null) { unregisterReceiver(screenOffReceiver); screenOffReceiver = null; }
        if (volumeReceiver != null) { unregisterReceiver(volumeReceiver); volumeReceiver = null; }
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        ownHomes.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        } else {
            // any TV: the TV's own home screens that are on now. Some TVs turn theirs back on as they start (a Xiaomi
            // does), or rank it above any app; until Homes.keepOff has turned it off again, it is sent back here
            // whenever it comes up, as stock_home_redirect does for the TVs it was written for.
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                if (ri.activityInfo == null || ri.priority <= -1000) continue;   // Android's fallback home, for the first seconds of a start
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || p.equals("com.android.tv.settings") || p.equals("com.android.settings")
                        || p.contains("setup") || p.contains("provision")) continue;
                stockHome.add(ri.activityInfo.name);
                ownHomes.add(p);
                if (stockHomePackage.isEmpty()) stockHomePackage = p;
            }
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        // any TV: the TV's own home app, opened by the TV itself as it starts or wakes, gives way to Glass until the
        // first key (Xiaomi opens PatchWall at every start, whatever the home is). Not where Glass opens something on
        // purpose at the start.
        ownAppsAtPowerOn = !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways || ownAppsAtPowerOn) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {   // Glass II always follows the app in front (Flip, Resume)
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            // the assistant reads the screen when asked (Screen), and Google's words when the button is pressed
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            setServiceInfo(info);
        }
        if (!config.stockHomeRedirect.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        else if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) frontAtStart(0);
        if (firstBindAfterBoot(Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                getSharedPreferences("boot", MODE_PRIVATE).getInt("homes_boot", -1), SystemClock.elapsedRealtime())) {
            getSharedPreferences("boot", MODE_PRIVATE).edit()
                    .putInt("homes_boot", Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1)).apply();
            Homes.keepOff(this);   // the TV's own homes, off again where the TV turned them back on as it started
        }
        Dim.restore(this);   // the picture as dim as it was left
        final android.content.Context app = getApplicationContext();
        Ui.BG.execute(() -> Assist.keep(app));   // the assistant button's settings, where a start cleared them
        armStandbyExit();
        // Glass II: the power-on chooser once per boot, and the night volume ceiling.
        if (!glassBootAsked && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            glassBootAsked = true;
            handler.postDelayed(() -> bootChooser(0), 3000);
        }
        GlassKeys.watchNight(this, handler);
        handler.postDelayed(() -> Updates.comeBack(this), 600);   // after Glass replaced itself, its page again
        if (screenOffReceiver == null) {
            // the screen going off closes whatever was open on the desktop, and covers it for the wake film (Wake)
            screenOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        Wake.screenOn(context);   // Glass waking up: the film plays as the desktop comes up
                        return;
                    }
                    GlassHomeActivity.screenOff();   // wake to a clean desktop
                    Hud.closeAll();
                    Wake.screenOff(context);
                }
            };
            IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            off.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenOffReceiver, off);
        }
        Wake.bootCheck(this);   // the first desktop of a boot opens with the film
        if (volumeReceiver == null) {
            // the TV's volume, whoever changed it (the remote's keys never reach an app on Android TV)
            volumeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    GlassKeys.volumeChanged(HomeKeyService.this, intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1));
                }
            };
            IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            vf.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
            registerReceiver(volumeReceiver, vf);
        }
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        String target = keys.get(code);
        String alt = front.get(code);
        return alt != null && RemoteKeys.inFront(target, frontPackage) ? alt : target;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    /** The last key let through to the TV (code, scan code) and when it was let go: if the TV answers it by bringing up
     *  its own home, it's this remote's Home button (onAccessibilityEvent learns it: GlassKeys.isHome). */
    private int passedCode, passedScan;
    private long passedAt;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean taken = takeKey(event);
        if (!taken && event.getAction() == KeyEvent.ACTION_UP && GlassKeys.couldBeHome(event.getKeyCode())) {
            passedCode = event.getKeyCode();
            passedScan = event.getScanCode();
            passedAt = SystemClock.uptimeMillis();
        }
        return taken;
    }

    private boolean takeKey(KeyEvent event) {
        Probe.key(this, event);   // Glass IV: first three days, key names only
        // Glass waking up: any key but power ends the film (Home too, which never reaches the desktop as a key)
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() != KeyEvent.KEYCODE_POWER
                && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) GlassHomeActivity.wakeSkip();
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (SleepTimer.handleKey(event)) return true;   // Glass: OK in the timer's last minute adds 15 min
        if (GlassKeys.handle(this, event, handler)) return true;   // Glass II: Home tap/hold, Back-Back, night ceiling
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, inFront(appKeys, frontAppKeys, event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty() && !Glass.original(this)) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    /**
     * Whether one of Google's own pages is on the TV right now (its settings and the like are settings-style pages,
     * with a title bar; its voice screen has none). Looked at only when a window of Google's comes up.
     */
    private boolean googlePageShowing() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    CharSequence p = r.getPackageName();
                    if (p != null && Takeover.GOOGLE.contentEquals(p) && pageLike(r, 0)) return true;
                } finally {
                    r.recycle();
                }
            }
        } catch (RuntimeException ignored) {
            // windows unavailable: treat it as the voice screen
        }
        return false;
    }

    /** A settings-style page: it has a title bar (Google's voice screen has none). Searched across the whole window. */
    private static boolean pageLike(android.view.accessibility.AccessibilityNodeInfo root, int unused) {
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> hits;
        try {
            hits = root.findAccessibilityNodeInfosByViewId(Takeover.GOOGLE + ":id/decor_title");
        } catch (RuntimeException e) {
            return false;
        }
        boolean found = hits != null && !hits.isEmpty();
        if (hits != null) for (android.view.accessibility.AccessibilityNodeInfo h : hits) h.recycle();
        return found;
    }

    private boolean isActivity(String pkg, String cls) {
        if (cls.isEmpty()) return false;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence evPkg = event.getPackageName(), evCls = event.getClassName();
        if (Takeover.GOOGLE.contentEquals(evPkg == null ? "" : evPkg)) {
            String cls = evCls == null ? "" : evCls.toString();
            if (isActivity(Takeover.GOOGLE, cls) && !cls.contains("VoicePlate")) {
                // Google's own pages (its settings, accounts, search): someone went there on purpose
                android.util.Log.i("GlassKeys", "google page left alone: " + cls.substring(cls.lastIndexOf('.') + 1));
            } else if (googlePageShowing()) {
                // a window inside one of those pages (or the button pressed while one is open): left to Google too
                android.util.Log.i("GlassKeys", "google window over its own page left alone");
            } else {
                // the remote's assistant button opened Google's voice screen: Glass takes it from here
                android.util.Log.i("GlassKeys", "google assistant window: " + cls);
                // not an activity: Google's voice screen is a window over the apps (a Xiaomi's), which Glass rides
                Takeover.googleAppeared(this, !isActivity(Takeover.GOOGLE, cls));
            }
        }
        // Glass II: the app in front and the one before it (Flip, Resume). Activities only.
        if (event.getPackageName() != null && event.getClassName() != null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(event.getPackageName().toString(), event.getClassName().toString()), 0);
                Glass.onFront(event.getPackageName().toString(), getPackageName());
                Glass.noteFront(this, event.getPackageName().toString());   // the switcher's recent apps
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, toast or panel: the app under it is still in front
            }
        }
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (!Glass.original(this) && powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }
        // any TV: the TV's own home app came up by itself as the TV started or woke, before anyone pressed a key
        if (ownAppsAtPowerOn && !Glass.original(this) && stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)
                && ownHomeApp(event.getPackageName()) && event.getClassName() != null
                && isActivity(event.getPackageName().toString(), event.getClassName().toString())) {
            android.util.Log.i("GlassKeys", "the TV opened its own home app at power-on: Glass instead");
            openHome();
            return;
        }
        // any TV: a key Glass let through brought up the TV's own home, so it's this remote's Home button (a Xiaomi's
        // arrives as BUTTON_2). Glass learns it, and from the next press it is Glass's Home (GlassKeys.isHome).
        if (passedAt != 0 && SystemClock.uptimeMillis() - passedAt < 2500 && !Glass.original(this)
                && event.getClassName() != null && event.getPackageName() != null
                && (stockHome.contains(event.getClassName().toString()) || ownHomeApp(event.getPackageName()))
                && isActivity(event.getPackageName().toString(), event.getClassName().toString())) {
            android.util.Log.i("GlassKeys", "the remote's Home is " + KeyEvent.keyCodeToString(passedCode) + " scan=" + passedScan);
            GlassKeys.learnHome(this, passedCode, passedScan);
            passedAt = 0;
            openHome();
            return;
        }
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        CharSequence cls = event.getClassName();
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString()) && !Glass.original(this)) openHome();
    }

    /** The power-on chooser, once per boot, after the TV's own homes are off again (Homes.keepOff): asked any earlier,
     *  it would offer a home that's about to go. */
    private void bootChooser(int waited) {
        if (Homes.keeping && waited < 20) {
            handler.postDelayed(() -> bootChooser(waited + 1), 1000);
            return;
        }
        GlassKeys.powerOn(this);
    }

    /** One of the TV's own home apps: one of its home screens (ownHomes), or an app whose name says it is its home
     *  (Xiaomi's PatchWall, com.mitv.tvhome.atv, isn't a home screen to Android). Never Glass, Android's own apps,
     *  settings or a setup screen. */
    private boolean ownHomeApp(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        if (ownHomes.contains(p)) return true;
        if (p.equals(getPackageName()) || p.startsWith("com.gathin.") || p.startsWith("com.android.") || p.equals("android")
                || p.contains("setup") || p.contains("settings")) return false;
        String q = p.toLowerCase(java.util.Locale.ROOT);
        return q.contains("tvhome") || q.contains("launcher") || q.contains("patchwall") || q.contains("homeshell")
                || q.endsWith(".home") || q.contains(".home.");
    }

    /** The checks of frontAtStart, so a wake's can replace a start's. */
    private static final Object FRONT = new Object();

    /** At a start or a wake: the TV's own home app may be in front before Android bound this helper (or before the
     *  wake's note came), and no window event comes for it then. Looked at now and twice more, a few seconds apart,
     *  until the first key; sent back here if it is. */
    private void frontAtStart(int tries) {
        if (tries > 2 || !stillArmed(SystemClock.elapsedRealtime(), powerOnUntil) || Glass.original(this)) return;
        try {
            android.view.accessibility.AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                CharSequence p = r.getPackageName();
                r.recycle();
                if (p != null && (ownHomes.contains(p.toString()) || (ownAppsAtPowerOn && ownHomeApp(p)))) {
                    android.util.Log.i("GlassKeys", "the TV's own home was in front at the start: Glass instead");
                    openHome();
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // nothing readable yet
        }
        handler.postAtTime(() -> frontAtStart(tries + 1), FRONT, SystemClock.uptimeMillis() + (tries == 0 ? 1800 : 3500));
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                // the TV's own home may already be up (a Xiaomi brings its own at every wake, often before this
                // note arrives): looked at in a moment, and twice more
                if (ownAppsAtPowerOn) {
                    handler.removeCallbacksAndMessages(FRONT);
                    handler.postAtTime(() -> frontAtStart(0), FRONT, SystemClock.uptimeMillis() + 600);
                }
                if (!powerOnLiveTv.isEmpty() || powerOnAlways) {   // armed for the TV's own apps only: no chooser here
                    handler.postDelayed(() -> GlassKeys.powerOn(HomeKeyService.this), 1500);   // Glass IV: light first
                }
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        startActivity(open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one). A Home intent would then put this launcher in the home task and make it the home process, and from
     *  Android 10 the stock launcher, no longer exempt from the limits on background activity starts, can't open
     *  apps for the remote's YouTube and Netflix buttons (#1274). openHome starts it like an app instead:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
