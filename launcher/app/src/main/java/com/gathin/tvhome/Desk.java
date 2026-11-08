package com.gathin.tvhome;

import android.content.Context;
import android.view.View;

import java.io.File;
import java.util.List;

/**
 * Where sheets live: the desktop (GlassHomeActivity) or a glass overlay over whatever app is playing (Hud).
 * Sheets ask their host for everything outside themselves, so the same Control Center, sources and volume work
 * on the desktop and over a film.
 */
interface Desk {
    Context context();

    void show(Sheet s, View anchor);

    void stack(Sheet s, View anchor);

    void dismiss(Sheet s);

    /** The x (design px) a panel hangs from: the centre of its bar item. */
    float barCenter(String key);

    /** The top (design px) panels hang from: under the bar on the desktop, near the top edge over an app. */
    float panelTop();

    /** True over an app (dark tinted frost, no wallpaper behind). */
    boolean overApp();

    Bar bar();

    void openSystem(String action);

    void openControls();

    void openSettings(String section);

    void openSearch();

    void openInputs();

    void openApps();

    void launch(String pkg, View from);

    void openFile(File f);

    void ask(String q);

    List<String[]> allApps();

    void lookChanged();

    /** The look changed from somewhere that should open again on the new look (Control Center). */
    default void lookChanged(String reopen) {
        lookChanged();
    }

    void fontScaleChanged();

    void resetDock();

    void appMenu(String pkg, View from);

    /** An app was closed (the switcher): the dock's dashes follow. */
    default void runningChanged() {
    }

    /** Focus back on the dock (something that had it went away). */
    default void focusDock() {
    }

    /** The desktop's widgets changed (Settings): the board is made again in place. */
    default void widgetsChanged() {
    }

    /** The TV's App info page for an app. */
    default void openAppInfo(String pkg) {
    }
}
