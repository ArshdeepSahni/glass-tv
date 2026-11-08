package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glass II: the TV's inputs as people name them. An HDMI-CEC device's own name ("PlayStation 5") over its port,
 * the port's custom label when the owner gave one, else "HDMI 1". Used by the Inputs sheet and Home's status line.
 */
final class GlassInputs {
    private GlassInputs() {
    }

    static final class Input {
        final String id, title, port;
        final boolean hdmi, on, empty;
        Input(String id, String title, String port, boolean hdmi, boolean on, boolean empty) {
            this.id = id; this.title = title; this.port = port; this.hdmi = hdmi; this.on = on; this.empty = empty;
        }
    }

    static List<Input> list(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> inputs;
        try { inputs = tim.getTvInputList(); } catch (RuntimeException e) { return out; }
        List<String> ids = new ArrayList<>();
        for (TvInputInfo i : inputs) ids.add(i.getId());
        Map<String, List<TvInputInfo>> devices = new HashMap<>();
        for (TvInputInfo i : inputs) {
            if (i.getParentId() != null && ids.contains(i.getParentId())) {
                List<TvInputInfo> l = devices.get(i.getParentId());
                if (l == null) devices.put(i.getParentId(), l = new ArrayList<>());
                l.add(i);
            }
        }
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
            if (info.getParentId() != null && ids.contains(info.getParentId())) continue;
            CharSequence l = info.loadLabel(ctx);
            String port = l == null ? info.getId() : l.toString();
            boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            if (hdmi && !port.toUpperCase(Locale.ROOT).contains("HDMI")) port = "HDMI " + port;
            CharSequence custom = InputNames.tidy(info.loadCustomLabel(ctx));
            List<TvInputInfo> cec = devices.get(info.getId());
            if (cec != null && !cec.isEmpty()) {
                for (TvInputInfo d : cec) {
                    CharSequence name = InputNames.tidy(d.loadCustomLabel(ctx));
                    if (name == null || name.length() == 0) name = custom != null ? custom : d.loadLabel(ctx);
                    String title = name == null || name.length() == 0 ? port : name.toString();
                    boolean on = state(tim, d.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
                    out.add(new Input(d.getId(), title, port, true, on, false));
                }
                continue;
            }
            int st = hdmi ? state(tim, info.getId()) : -1;
            boolean empty = hdmi && st == TvInputManager.INPUT_STATE_DISCONNECTED;
            boolean on = hdmi && st == TvInputManager.INPUT_STATE_CONNECTED;
            String title = custom != null && custom.length() > 0 ? custom.toString() : port;
            out.add(new Input(info.getId(), title, port, hdmi, on, empty));
        }
        return out;
    }

    /** The first input with a device that is switched on and named itself, for Home's status line. */
    static Input deviceOn(Context ctx) {
        for (Input i : list(ctx)) if (i.on && !i.title.equals(i.port)) return i;
        return null;
    }

    private static int state(TvInputManager tim, String id) {
        try { return tim.getInputState(id); } catch (RuntimeException e) { return -1; }
    }

    /** ACTION_VIEW on the input's passthrough channel, through the TV's own Live TV app first. */
    static boolean open(Context ctx, String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String liveTv = Config.load(ctx).liveTvPackage;
        if (liveTv != null && !liveTv.isEmpty()) {
            try { ctx.startActivity(new Intent(view).setPackage(liveTv)); return true; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        try { ctx.startActivity(view); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }
}
