package com.gathin.tvhome;

/**
 * The TV's own extras that Glass can turn off to free memory (Settings ▸ Speed & memory): {package, name, what it does}.
 * Off means "disabled for the user", never uninstalled. Off is noted on the TV with the homes Glass turned off
 * (Homes.TURNED_OFF), so the TV's start keeps it off (some TVs turn their updated apps back on as they start) and
 * ./glass restore turns everything back on. Glass's key helper turns these, and only these, off and on (KeyDaemon).
 * Never here: what the TV needs (inputs, Wi-Fi, the remote, the Play Store, Google's assistant, the TV's own home).
 */
final class Extras {
    private Extras() {
    }

    static final String[][] ALL = {
            // Xiaomi
            {"com.mitv.tvhome.atv", "PatchWall", "Xiaomi's home screen. Glass is home, so it only uses memory"},
            {"com.mitv.tvhome.mitvplus", "Mi TV+", "Xiaomi's free channels"},
            {"com.mitv.dream", "Xiaomi screen saver", "Glass has its own screen savers"},
            {"com.miui.tv.analytics", "Xiaomi analytics", "Reports how the TV is used to Xiaomi"},
            {"com.xiaomi.statistic", "Xiaomi statistics", "Usage statistics for Xiaomi"},
            {"com.xiaomi.mitv.updateservice", "Xiaomi system updates", "Checks for and installs the TV's own software updates"},
            {"com.xiaomi.mitv.smartshare", "Xiaomi screen share", "Mirroring from Xiaomi phones"},
            {"com.mitv.milinkservice", "MiLink", "Connects Xiaomi phones to the TV"},
            {"com.xiaomi.wfdsinkhelperservice", "Screen mirroring helper", "Miracast mirroring from phones"},
            {"com.mitv.gallery", "Xiaomi gallery", "Xiaomi's photo viewer"},
            {"com.mitv.tvlock", "Xiaomi TV lock", "Xiaomi's lock for the TV"},
            {"com.xiaomi.mitv.handbook", "Xiaomi user guide", "The TV's manual"},
            {"com.xiaomi.mitv.mediaexplorer", "Xiaomi media explorer", "USB drives. Glass's Files opens them too"},
            {"com.xiaomo.tv.milegal", "Xiaomi legal", "Terms and notices"},
            {"com.xm.webcontent", "Xiaomi web content", "Promotions for Xiaomi's home"},
            {"android.autoinstalls.config.xioami.dangal", "Xiaomi auto-installs", "Installs partner apps by itself"},
            // Realme (Homwee)
            {"com.homwee.aipont", "AI PONT", "The maker's portal and promo channels"},
            {"com.homwee.store", "App store", "The maker's own app store"},
            {"com.homwee.help", "Help", "The maker's help app"},
            {"com.homwee.howtocast", "How to cast", "The maker's casting tutorial"},
            {"android.autoinstalls.config.homwee", "Auto-installs", "Installs partner apps by itself"},
            {"com.homwee.multiscreenshare", "Screen mirroring", "Mirroring from Android phones"},
            {"com.homwee.mmp.screenshare", "Screen share", "The maker's screen share"},
            // Google
            {"com.google.android.apps.mediashell", "Chromecast built-in", "The cast button in YouTube, Netflix and Chrome"},
            {"com.google.android.tvrecommendations", "Recommendations", "Sponsored rows for Android TV's home"},
            {"com.google.android.leanbacklauncher", "Old Android TV home", "Android TV's first home screen"},
            {"com.google.android.leanbacklauncher.recommendations", "Old home's recommendations", "Rows for that old home"},
            {"com.google.android.backdrop", "Ambient mode", "Google's photo screen saver"},
            {"com.google.android.play.games", "Play Games", "Google's games service"},
            {"com.google.android.videos", "Google TV app", "Google's films and shows store"},
            {"com.google.android.feedback", "Google feedback", "Sends reports to Google"},
            {"com.google.android.tv.bugreportsender", "Bug reports", "Sends bug reports to Google"},
            {"com.google.android.partnersetup", "Partner setup", "Used once, when the TV was set up"},
            {"com.google.android.onetimeinitializer", "Google first-run helper", "Used once, at the first start"},
            {"com.google.android.syncadapters.calendar", "Calendar sync", "Syncs a Google calendar nothing here shows"},
            // Android
            {"com.android.settings.intelligence", "Settings suggestions", "Suggestions for a phone's settings"},
            {"com.android.camera2", "Camera", "This TV has no camera"},
            {"com.android.dreams.basic", "Basic screen saver", "Glass has its own screen savers"},
            {"com.android.printspooler", "Printing", "Printing from apps"},
            {"com.android.htmlviewer", "HTML viewer", "Shows web pages saved as files"},
            {"com.android.wallpaperbackup", "Wallpaper backup", "Backs up a phone's wallpaper"},
            {"com.android.onetimeinitializer", "First-run helper", "Used once, at the first start"},
    };

    static boolean known(String pkg) {
        for (String[] e : ALL) if (e[0].equals(pkg)) return true;
        return false;
    }

    /** Apps the "runs in the background" switch never touches: Android, Google's services and assistant, Glass. */
    static boolean backgroundProtected(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.") || pkg.startsWith("com.gathin.")
                || pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf")
                || pkg.equals("com.google.android.katniss") || pkg.equals("com.google.android.tv.remote.service")
                || pkg.equals("com.google.android.tungsten.setupwraith") || pkg.startsWith("com.droidlogic")
                || pkg.startsWith("com.mediatek") || pkg.equals("com.android.vending");
    }
}
