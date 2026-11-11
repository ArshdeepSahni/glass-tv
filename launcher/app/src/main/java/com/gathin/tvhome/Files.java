package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Storage for the Files window: the places (Recents, Downloads, Movies, Photos, Music; each USB drive; the TV's
 * own storage), what's in them, thumbnails, and opening a file in whichever app plays it. Read-only.
 */
final class Files {
    private Files() {
    }

    static final ExecutorService IO = Executors.newFixedThreadPool(2);

    static final class Place {
        final String key, title, glyph;
        final File root;     // null for the smart places, which come from the media index
        final int media;     // 0 folder · -1 recents · else a MediaStore media type

        Place(String key, String title, String glyph, File root, int media) {
            this.key = key;
            this.title = title;
            this.glyph = glyph;
            this.root = root;
            this.media = media;
        }
    }

    static final class Entry {
        String name, meta, kind;    // kind: folder · video · photo · music · doc · file
        File file;
        long id = -1, size, modified;
    }

    static List<Place> favourites(Context c) {
        List<Place> p = new ArrayList<>();
        // photos and videos sent from a phone (Show): first, so what just arrived is a click away
        p.add(new Place("phones", "From phones", "phone", Sample.on ? Sample.photoDir(c) : Show.dir(c), 0));
        p.add(new Place("recents", "Recents", "clock", null, -1));
        p.add(new Place("downloads", "Downloads", "down", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 0));
        p.add(new Place("movies", "Movies", "film", null, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        p.add(new Place("photos", "Photos", "photo", null, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        p.add(new Place("music", "Music", "music", null, MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO));
        return p;
    }

    /** Each mounted USB drive (or card), then the TV's own storage. */
    static List<Place> locations(Context c) {
        List<Place> out = new ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File root = path(v);
                if (root == null) continue;
                String d = v.getDescription(c);
                out.add(new Place("usb:" + root.getPath(), d == null || d.isEmpty() ? "USB drive" : d, "drive", root, 0));
            }
        }
        if (out.isEmpty()) {
            // some firmware hides removable volumes from the API; /storage still lists them
            File[] all = new File("/storage").listFiles();
            if (all != null) for (File f : all) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || !f.isDirectory() || !f.canRead()) continue;
                out.add(new Place("usb:" + f.getPath(), "USB drive", "drive", f, 0));
            }
        }
        out.add(new Place("tv", "TV storage", "tv", Environment.getExternalStorageDirectory(), 0));
        return out;
    }

    private static File path(StorageVolume v) {
        try {
            Object p = StorageVolume.class.getMethod("getPath").invoke(v);
            return p == null ? null : new File(p.toString());
        } catch (Exception e) {
            String uuid = v.getUuid();
            return uuid == null ? null : new File("/storage/" + uuid);
        }
    }

    /** "USB drive" or "TV storage", for a path. */
    static String where(Context c, String path) {
        String internal = Environment.getExternalStorageDirectory().getPath();
        if (path.startsWith(internal) || path.startsWith("/storage/emulated") || path.startsWith("/sdcard")) return "TV storage";
        return "USB drive";
    }

    static List<Entry> list(Context c, Place p, File dir) {
        if (dir != null) return folder(c, dir);
        if (p.root != null) return folder(c, p.root);
        return withSent(c, media(c, p.media), p.media);
    }

    /** What phones sent belongs in Photos, Movies and Recents too (the TV's media index never sees Glass's folder). */
    private static List<Entry> withSent(Context c, List<Entry> found, int type) {
        boolean photos = type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, videos = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
        if (!photos && !videos && type != -1) return found;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Entry e : found) have.add(e.file.getPath());
        List<Entry> out = new ArrayList<>(found);
        for (File f : Show.all(c)) {
            boolean ph = Show.photo(f.getName());
            if (photos && !ph || videos && ph || have.contains(f.getPath())) continue;
            Entry e = new Entry();
            e.file = f;
            e.name = f.getName();
            e.kind = ph ? "photo" : "video";
            e.size = f.length();
            e.modified = f.lastModified();
            e.meta = "From a phone · " + Formatter.formatShortFileSize(c, e.size);
            out.add(e);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.modified, x.modified));
        return out;
    }

    /** Something a phone sent (Glass's own folder): Glass may delete it. */
    static boolean sent(Context c, File f) {
        return f != null && f.getParentFile() != null && f.getParentFile().equals(Show.dir(c));
    }

    private static List<Entry> folder(Context c, File dir) {
        List<Entry> dirs = new ArrayList<>(), files = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids == null) return dirs;
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            Entry e = new Entry();
            e.file = f;
            e.name = f.getName();
            e.modified = f.lastModified();
            if (f.isDirectory()) {
                e.kind = "folder";
                String[] inside = f.list();
                int n = 0;
                if (inside != null) for (String s : inside) if (!s.startsWith(".")) n++;
                e.meta = n == 1 ? "1 item" : n + " items";
                dirs.add(e);
            } else {
                e.kind = kindOf(e.name);
                e.size = f.length();
                e.meta = Formatter.formatShortFileSize(c, e.size);
                files.add(e);
            }
        }
        Collections.sort(dirs, (x, y) -> x.name.compareToIgnoreCase(y.name));
        Collections.sort(files, (x, y) -> x.name.compareToIgnoreCase(y.name));
        dirs.addAll(files);
        return dirs;
    }

    private static List<Entry> media(Context c, int type) {
        List<Entry> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        Uri u = MediaStore.Files.getContentUri("external");
        String where = type == -1
                ? MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,2,3)"
                : MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + type;
        String[] cols = {MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor k = cr.query(u, cols, where, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            int limit = type == -1 ? 60 : 400;
            while (k != null && k.moveToNext() && out.size() < limit) {
                String path = k.getString(2);
                if (path == null) continue;
                File f = new File(path);
                if (!f.exists()) continue;
                Entry e = new Entry();
                e.id = k.getLong(0);
                e.name = k.getString(1) != null ? k.getString(1) : f.getName();
                e.file = f;
                e.size = k.getLong(3);
                e.modified = k.getLong(4) * 1000L;
                int mt = k.getInt(5);
                e.kind = mt == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "video" : mt == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ? "photo" : "music";
                e.meta = Formatter.formatShortFileSize(c, e.size);
                out.add(e);
            }
        } catch (RuntimeException ignored) {
            // no storage permission: an empty place
        }
        return out;
    }

    private static final List<String> VIDEO = Arrays.asList("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "wmv", "flv", "3gp", "mpg", "mpeg");
    private static final List<String> PHOTO = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif");
    private static final List<String> MUSIC = Arrays.asList("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma");
    private static final List<String> DOC = Arrays.asList("pdf", "txt", "doc", "docx", "srt", "epub");

    static String kindOf(String name) {
        String e = ext(name);
        if (VIDEO.contains(e)) return "video";
        if (PHOTO.contains(e)) return "photo";
        if (MUSIC.contains(e)) return "music";
        if (DOC.contains(e)) return "doc";
        return "file";
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String mime(File f) {
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f.getName()));
        if (m != null) return m;
        switch (kindOf(f.getName())) {
            case "video": return "video/*";
            case "photo": return "image/*";
            case "music": return "audio/*";
            default: return "*/*";
        }
    }

    static boolean open(Context c, File f, boolean choose) {
        // something a phone sent: Glass's own viewer (← → through the rest, a slideshow)
        if (!choose && f.getParentFile() != null && f.getParentFile().equals(Show.dir(c))) {
            Show.open(c, f.getName());
            return true;
        }
        try {
            Uri u = FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (choose) i = Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "No app on this TV opens " + f.getName(), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(c, "Couldn't open " + f.getName(), Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    static String free(Context c, File root) {
        try {
            StatFs s = new StatFs((root != null ? root : Environment.getExternalStorageDirectory()).getPath());
            return Formatter.formatShortFileSize(c, s.getAvailableBytes()) + " free";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A small picture of a photo or video, about 192×144. Null when there isn't one. */
    @SuppressWarnings("deprecation")
    static Bitmap thumb(Context c, Entry e) {
        try {
            if ("photo".equals(e.kind)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(e.file.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 192 && o.outHeight / (s * 2) >= 144) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeFile(e.file.getPath(), o);
            }
            if ("video".equals(e.kind)) {
                if (e.id >= 0) {
                    Bitmap b = MediaStore.Video.Thumbnails.getThumbnail(c.getContentResolver(), e.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                    if (b != null) return b;
                }
                return ThumbnailUtils.createVideoThumbnail(e.file.getPath(), MediaStore.Video.Thumbnails.MINI_KIND);
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {
        }
        return null;
    }
}
