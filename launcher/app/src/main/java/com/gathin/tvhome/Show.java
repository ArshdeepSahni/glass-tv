package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Photos and videos sent from a phone (the phone page's Send): kept in Glass's own folder on the TV ("From phones",
 * in Files and in the Photos screen saver), and shown on the TV the moment they arrive (ShowActivity). Nothing leaves
 * the TV; the folder is Glass's own, so uninstalling Glass removes it.
 */
final class Show {
    private Show() {
    }

    static final long MAX_BYTES = 400L * 1024 * 1024;

    static File dir(Context c) {
        File d = c.getExternalFilesDir("From phones");
        if (d == null) d = new File(c.getFilesDir(), "From phones");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static boolean photo(String name) {
        return "photo".equals(Files.kindOf(name));
    }

    static boolean video(String name) {
        return "video".equals(Files.kindOf(name));
    }

    /** Everything sent, newest first. */
    static List<File> all(Context c) {
        if (Sample.on) return Sample.photos(c);
        File[] fs = dir(c).listFiles();
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile() || f.getName().startsWith(".")) continue;
            if (photo(f.getName()) || video(f.getName())) out.add(f);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    static List<File> photos(Context c) {
        List<File> out = new ArrayList<>();
        for (File f : all(c)) if (photo(f.getName())) out.add(f);
        return out;
    }

    /** A file name for something arriving: the phone's own name, cleaned, made unique. */
    static File arriving(Context c, String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9._ -]", "").trim();
        if (clean.isEmpty() || clean.startsWith(".")) clean = "from-phone-" + System.currentTimeMillis() + ".jpg";
        if (clean.length() > 80) clean = clean.substring(clean.length() - 80);
        String ext = Files.ext(clean);
        if (!photo(clean) && !video(clean)) {
            clean = clean + ".jpg";
            ext = "jpg";
        }
        File f = new File(dir(c), clean);
        String stem = clean.substring(0, clean.length() - ext.length() - 1);
        for (int i = 2; f.exists() && i < 1000; i++) f = new File(dir(c), stem + " " + i + "." + ext);
        return f;
    }

    /** A picture of a file for a card, about w x h (centre-cropped). Null when it can't be read. */
    static Bitmap thumb(File f, int w, int h) {
        try {
            if (video(f.getName())) {
                Bitmap b = ThumbnailUtils.createVideoThumbnail(f.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
                return b == null ? null : ThumbnailUtils.extractThumbnail(b, w, h, ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            return b == null ? null : ThumbnailUtils.extractThumbnail(b, w, h, ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Shows a sent file full screen, over whatever is playing (the TV comes to it). */
    static void open(Context c, String name) {
        File f = name == null ? null : new File(dir(c), new File(name).getName());
        Intent i = new Intent(c, ShowActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (f != null && f.exists()) i.putExtra(ShowActivity.FILE, f.getName());
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    static String nice(String name) {
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.length() > 40 ? n.substring(0, 40) + "…" : n;
    }

    static final List<String> SENDABLE = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "mov", "m4v", "webm", "3gp", "mkv");

    static boolean sendable(String name) {
        return SENDABLE.contains(Files.ext(name).toLowerCase(Locale.ROOT));
    }
}
