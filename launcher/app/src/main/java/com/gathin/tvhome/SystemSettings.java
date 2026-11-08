package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

/** The standard settings intent, or, when nothing usable answers it, the first vendor settings entry
 *  from settings_fallback_components that is installed (Xiaomi China, TCL / FFALCON, Fire OS), else the
 *  AOSP TV settings app's main screen where it is installed. */
final class SystemSettings {
    private SystemSettings() {}

    /** Not a standard action: the TV settings app's own "pair a remote or accessory" page. */
    static final String PAIR = "glass.settings.PAIR";

    /**
     * Pages the TV settings app has but answers no standard action for (Android TV 9 here: Bluetooth, the wider
     * network page, storage), by component. Without these, those links fell back to the settings' first screen.
     */
    private static final String[][] PAGES = {
            {PAIR, "com.android.tv.settings/.accessories.AddAccessoryActivity"},
            {Settings.ACTION_BLUETOOTH_SETTINGS, "com.android.tv.settings/.accessories.AddAccessoryActivity"},
            {Settings.ACTION_WIRELESS_SETTINGS, "com.android.tv.settings/.connectivity.NetworkActivity"},
            {Settings.ACTION_WIFI_SETTINGS, "com.android.tv.settings/.connectivity.NetworkActivity"},
            {Settings.ACTION_SOUND_SETTINGS, "com.android.tv.settings/.device.sound.SoundActivity"},
            {Settings.ACTION_APPLICATION_SETTINGS, "com.android.tv.settings/.device.apps.AppsActivity"},
            {Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "com.android.tv.settings/.device.apps.AppsActivity"},
            {"android.settings.STORAGE_SETTINGS", "com.android.tv.settings/.device.StorageResetActivity"},
            {Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "com.android.tv.settings/.device.StorageResetActivity"},
            {Settings.ACTION_DATE_SETTINGS, "com.android.tv.settings/.system.DateTimeActivity"},
            {Settings.ACTION_LOCALE_SETTINGS, "com.android.tv.settings/.system.LanguageActivity"},
            {Settings.ACTION_INPUT_METHOD_SETTINGS, "com.android.tv.settings/.inputmethod.KeyboardActivity"},
            {Settings.ACTION_DEVICE_INFO_SETTINGS, "com.android.tv.settings/.about.AboutActivity"},
    };

    /** The page itself when this TV has it: a standard action that opens something real, else its component. */
    static Intent exact(Context context, String action) {
        PackageManager pm = context.getPackageManager();
        if (!PAIR.equals(action)) {
            Intent standard = new Intent(action);
            ResolveInfo r = pm.resolveActivity(standard, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && real(r)) return standard;
        }
        for (String[] page : PAGES) {
            if (!page[0].equals(action)) continue;
            Intent i = new Intent().setComponent(ComponentName.unflattenFromString(page[1]));
            if (pm.resolveActivity(i, 0) != null) return i;
        }
        return null;
    }

    /** Placeholders that answer an action and open nothing (or ask which app): Fire OS's CTS stub, Android TV's
     *  empty stubs, the chooser when two apps answer. */
    private static boolean real(ResolveInfo r) {
        String n = r.activityInfo.name;
        return !n.contains("CTSDummy") && !n.contains("EmptyStub") && !n.contains("Stubs$") && !n.endsWith("ResolverActivity");
    }

    static Intent intent(Context context, String action) {
        PackageManager pm = context.getPackageManager();
        Intent exact = exact(context, action);
        if (exact != null) return exact;
        Intent standard = new Intent(action);
        Intent vendor = vendorIntent(context);
        if (vendor != null) return vendor;
        // Some firmware ships the AOSP TV settings app with no filter for the standard actions (HY300 Pro+
        // projector, rk3566, Android 14): open its main screen directly. Not a vendor entry, so the Settings
        // menu's "Native settings" row stays as it was.
        Intent aosp = new Intent().setComponent(AOSP_TV_SETTINGS);
        try {
            pm.getPackageInfo(AOSP_TV_SETTINGS.getPackageName(), 0);
            return aosp;
        } catch (PackageManager.NameNotFoundException e) {
            return standard;
        }
    }

    private static final ComponentName AOSP_TV_SETTINGS =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings");

    /** The first vendor settings entry from settings_fallback_components that is installed, or null
     *  when none is. China-market firmware keeps pages the AOSP TV settings has no row for -
     *  Xiaomi's Account & Security, where the ADB switch lives - in the vendor's own settings app;
     *  MainActivity's Settings menu offers it as "Native settings" while one is installed. */
    static Intent vendorIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            // Explicit resolveActivity() always succeeds, so check the package exists instead.
            try {
                pm.getPackageInfo(component.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            return new Intent().setComponent(component);
        }
        return null;
    }

    /** Settings ▸ TV settings: the TV's own pages that this TV really has, each opening exactly that page.
     *  {title, what it holds, action or component}. */
    static final String[][] NATIVE = {
            {"All TV settings", "Everything the TV's own settings hold", Settings.ACTION_SETTINGS},
            {"Network & internet", "Wi-Fi, Ethernet, proxies", Settings.ACTION_WIFI_SETTINGS},
            {"Remotes & accessories", "Pair a remote, a gamepad, a speaker", PAIR},
            {"Sound", "Sound modes, outputs, surround", Settings.ACTION_SOUND_SETTINGS},
            {"Apps", "Storage, permissions, uninstall", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS},
            {"Date & time", "Time zone, 24-hour clock", Settings.ACTION_DATE_SETTINGS},
            {"Language", "The TV's language", Settings.ACTION_LOCALE_SETTINGS},
            {"Keyboard", "Which on-screen keyboard is used", Settings.ACTION_INPUT_METHOD_SETTINGS},
            {"Storage & reset", "Free space, factory reset", "android.settings.STORAGE_SETTINGS"},
            {"Device name", "How phones and speakers see this TV", "android.settings.DEVICE_NAME"},
            {"Captions", "Subtitles' size and style", Settings.ACTION_CAPTIONING_SETTINGS},
            {"Security & restrictions", "Unknown sources, restricted profile", Settings.ACTION_SECURITY_SETTINGS},
            {"Privacy", "Usage & diagnostics, ads", Settings.ACTION_PRIVACY_SETTINGS},
            {"System update", "Android updates for this TV", "android.settings.SYSTEM_UPDATE_SETTINGS"},
            {"About", "Model, Android version, status", Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"Developer options", "Network debugging and more", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS},
    };

    /** The TV's App info page for pkg, or null where the firmware has none (CVTE's settings app, #1202). */
    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY) != null ? info : null;
    }

    /**
     * The TV's display or picture settings. On Xiaomi sets, the AOSP DISPLAY_SETTINGS action resolves to
     * an empty stub in DroidTvSettings; prefer Xiaomi's own PictureActivity where installed.
     */
    static Intent displayIntent(Context context) {
        ComponentName xiaomi = new ComponentName("com.android.tv.settings",
                "com.xiaomi.mitv.settings.picture.PictureActivity");
        if (context.getPackageManager().resolveActivity(new Intent().setComponent(xiaomi), 0) != null) {
            return new Intent().setComponent(xiaomi);
        }
        return intent(context, Settings.ACTION_DISPLAY_SETTINGS);
    }
}
