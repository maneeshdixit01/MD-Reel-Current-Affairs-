package com.md.mdreelcurrentaffair;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Converts the MediaRecorder source (WebM/Opus or MP4) to a standards-based
 * H.264/AAC MP4 before publishing it to MediaStore. This gives Gallery and
 * social apps a conventional MP4 container with a finalized duration index.
 */
@CapacitorPlugin(name = "VideoFixer")
public class VideoFixerPlugin extends Plugin {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Transformer activeTransformer;

    private long readDurationMs(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void copy(File src, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
        }
    }

    private String saveToGallery(Context context, File file, String name, long duration) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MD_Reel_Current_Affair");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open Gallery output");
                copy(file, out);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                if (e instanceof IOException) throw (IOException)e;
                throw new IOException(e);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (duration > 0) done.put(MediaStore.Video.Media.DURATION, duration);
            resolver.update(uri, done, null, null);
            return "Movies/MD_Reel_Current_Affair/" + name;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MD_Reel_Current_Affair");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create Movies folder");
        File target = new File(dir, name);
        try (OutputStream out = new FileOutputStream(target)) { copy(file, out); }
        android.media.MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return "Movies/MD_Reel_Current_Affair/" + name;
    }

    private void delete(File f) { if (f != null && f.exists()) { //noinspection ResultOfMethodCallIgnored
        f.delete();
    }}

    @PluginMethod
    public void remuxAndSaveToGallery(PluginCall call) {
        String inputPath = call.getString("inputPath", "");
        String fileName = call.getString("fileName", "MD_Reel_Current_Affair.mp4");
        Boolean saveFlag = call.getBoolean("saveToGallery", true);
        boolean saveGallery = saveFlag == null || saveFlag;
        if (inputPath.trim().isEmpty()) { call.reject("Missing inputPath"); return; }

        Context context = getContext().getApplicationContext();
        File input = new File(context.getCacheDir(), inputPath);
        if (!input.exists() || input.length() < 1024) { call.reject("Recorded source video is missing or empty"); return; }
        File output = new File(context.getCacheDir(), "converted_" + System.currentTimeMillis() + ".mp4");

        try {
            Transformer transformer = new Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        executor.execute(() -> {
                            try {
                                long duration = readDurationMs(output);
                                if (!output.exists() || output.length() < 1024 || duration <= 0) {
                                    delete(output);
                                    call.reject("MP4 conversion completed but duration/audio metadata is invalid");
                                    return;
                                }
                                String path = "";
                                boolean saved = false;
                                if (saveGallery) {
                                    path = saveToGallery(context, output, fileName, duration);
                                    saved = true;
                                }
                                JSObject response = new JSObject();
                                response.put("remuxed", true);
                                response.put("transcoded", true);
                                response.put("gallerySaved", saved);
                                response.put("galleryPath", path);
                                response.put("galleryError", "");
                                response.put("cacheUri", Uri.fromFile(output).toString());
                                response.put("durationMs", duration);
                                response.put("sizeBytes", output.length());
                                call.resolve(response);
                            } catch (Exception e) {
                                delete(output);
                                call.reject("Could not save converted MP4: " + e.getMessage());
                            }
                        });
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException exception) {
                        delete(output);
                        call.reject("WebM/MP4 to H.264/AAC conversion failed: " + exception.getMessage());
                    }
                }).build();
            activeTransformer = transformer;
            transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.getAbsolutePath());
        } catch (Exception e) {
            delete(output);
            call.reject("Could not start MP4 conversion: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cleanupCache(PluginCall call) {
        executor.execute(() -> {
            File[] files = getContext().getApplicationContext().getCacheDir().listFiles();
            if (files != null) for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".mp4") || n.endsWith(".webm")) delete(f);
            }
            call.resolve();
        });
    }

    @Override
    protected void handleOnDestroy() {
        try { if (activeTransformer != null) activeTransformer.cancel(); } catch (Exception ignored) {}
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}
