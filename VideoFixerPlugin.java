package com.md.mdreelcurrentaffair;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "VideoFixer")
public class VideoFixerPlugin extends Plugin {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isMp4MuxerSupportedMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase();
        return m.equals("video/avc")
            || m.equals("video/hevc")
            || m.equals("video/mp4v-es")
            || m.equals("audio/mp4a-latm")
            || m.equals("audio/mpeg")
            || m.equals("audio/3gpp")
            || m.equals("audio/amr-wb");
    }

    /**
     * Losslessly remuxes a fragmented MediaRecorder MP4 into a standard flat MP4
     * with a complete moov header, seek table, and exact full duration so Android Gallery,
     * Instagram, Facebook, and YouTube read the real length immediately.
     */
    private boolean remuxToStandardMp4(File inputFile, File outputFile) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int framesWritten = 0;

        try {
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) {
                return false;
            }

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] trackIndexMap = new int[trackCount];
            long[] lastPtsPerTrack = new long[trackCount];
            boolean hasVideoTrack = false;
            int maxBufferSize = 4 * 1024 * 1024;

            for (int i = 0; i < trackCount; i++) {
                trackIndexMap[i] = -1;
                lastPtsPerTrack[i] = -1L;

                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (!isMp4MuxerSupportedMime(mime)) {
                    continue;
                }

                try {
                    int muxIdx = muxer.addTrack(format);
                    trackIndexMap[i] = muxIdx;
                    extractor.selectTrack(i);
                    if (mime != null && mime.startsWith("video/")) {
                        hasVideoTrack = true;
                    }
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        int fmtMax = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                        if (fmtMax > maxBufferSize && fmtMax <= 16 * 1024 * 1024) {
                            maxBufferSize = fmtMax;
                        }
                    }
                } catch (Exception ignoredTrackErr) {
                    trackIndexMap[i] = -1;
                }
            }

            if (!hasVideoTrack) {
                return false;
            }

            muxer.start();
            muxerStarted = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long basePts = -1L;

            while (true) {
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex < 0) {
                    break;
                }

                int muxerTrackIndex = (trackIndex < trackIndexMap.length) ? trackIndexMap[trackIndex] : -1;
                if (muxerTrackIndex < 0) {
                    extractor.advance();
                    continue;
                }

                buffer.clear();
                int sampleSize;
                try {
                    sampleSize = extractor.readSampleData(buffer, 0);
                } catch (IllegalArgumentException smallBufErr) {
                    if (buffer.capacity() < 16 * 1024 * 1024) {
                        buffer = ByteBuffer.allocateDirect(Math.min(16 * 1024 * 1024, buffer.capacity() * 2));
                        sampleSize = extractor.readSampleData(buffer, 0);
                    } else {
                        extractor.advance();
                        continue;
                    }
                }

                if (sampleSize < 0) {
                    break;
                }

                long rawPts = extractor.getSampleTime();
                if (rawPts >= 0 && basePts < 0) {
                    basePts = rawPts;
                }

                long pts;
                if (rawPts >= 0 && basePts >= 0) {
                    pts = Math.max(0L, rawPts - basePts);
                } else {
                    pts = (lastPtsPerTrack[trackIndex] >= 0) ? (lastPtsPerTrack[trackIndex] + 33333L) : 0L;
                }

                if (lastPtsPerTrack[trackIndex] >= 0 && pts <= lastPtsPerTrack[trackIndex]) {
                    pts = lastPtsPerTrack[trackIndex] + 1000L;
                }
                lastPtsPerTrack[trackIndex] = pts;

                int extractorFlags = extractor.getSampleFlags();
                int codecFlags = 0;
                if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && (extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                }

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = pts;
                bufferInfo.flags = codecFlags;

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);
                framesWritten++;
                extractor.advance();
            }

            if (muxerStarted && framesWritten > 0) {
                muxer.stop();
                muxerStarted = false;
            }
            return framesWritten > 0 && outputFile.exists() && outputFile.length() > 1024;
        } catch (Exception e) {
            safeDelete(outputFile);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted && framesWritten > 0) {
                        muxer.stop();
                    }
                } catch (Exception ignored) {}
                try {
                    muxer.release();
                } catch (Exception ignored) {}
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {}
            }
        }
    }

    private long readVideoDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                return Long.parseLong(durStr);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private void copyFileToStream(File src, OutputStream dst) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                dst.write(buf, 0, len);
            }
            dst.flush();
        }
    }

    private String saveVideoToGallery(Context context, File sourceFile, String fileName, String mimeType, long durationMs) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MD_Reel_Current_Affair");
            if (durationMs > 0) {
                values.put(MediaStore.Video.Media.DURATION, durationMs);
            }
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = resolver.insert(collection, values);
            if (itemUri == null) {
                throw new IOException("MediaStore insert returned null");
            }

            try (OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) {
                    throw new IOException("Could not open MediaStore output stream");
                }
                copyFileToStream(sourceFile, out);
            } catch (IOException writeErr) {
                try {
                    resolver.delete(itemUri, null, null);
                } catch (Exception ignored) {}
                throw writeErr;
            }

            ContentValues doneValues = new ContentValues();
            doneValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (durationMs > 0) {
                doneValues.put(MediaStore.Video.Media.DURATION, durationMs);
            }
            resolver.update(itemUri, doneValues, null, null);
            return "Movies/MD_Reel_Current_Affair/" + fileName;
        } else {
            File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File targetDir = new File(moviesDir, "MD_Reel_Current_Affair");
            if (!targetDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetDir.mkdirs();
            }
            File destFile = new File(targetDir, fileName);
            try (OutputStream out = new FileOutputStream(destFile)) {
                copyFileToStream(sourceFile, out);
            }
            MediaScannerConnection.scanFile(
                context,
                new String[]{ destFile.getAbsolutePath() },
                new String[]{ mimeType },
                null
            );
            return "Movies/MD_Reel_Current_Affair/" + fileName;
        }
    }

    private void safeDelete(File f) {
        if (f != null && f.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Exception ignored) {}
        }
    }

    @PluginMethod
    public void remuxAndSaveToGallery(PluginCall call) {
        String inputPath = call.getString("inputPath", "");
        String fileName = call.getString("fileName", "MD_Reel_Current_Affair.mp4");
        String mimeType = call.getString("mimeType", "video/mp4");
        Boolean saveFlag = call.getBoolean("saveToGallery", true);
        boolean shouldSaveToGallery = (saveFlag == null) || saveFlag;

        if (inputPath == null || inputPath.trim().isEmpty()) {
            call.reject("Missing inputPath");
            return;
        }

        final Context appContext = getContext().getApplicationContext();
        final File cacheDir = appContext.getCacheDir();
        final File rawFile = new File(cacheDir, inputPath);

        if (!rawFile.exists() || rawFile.length() == 0) {
            call.reject("Input video file not found in cache");
            return;
        }

        executor.execute(() -> {
            try {
                File fixedFile = new File(cacheDir, "fixed_" + fileName);
                boolean isMp4 = mimeType != null && mimeType.toLowerCase().contains("mp4");
                boolean remuxed = isMp4 && remuxToStandardMp4(rawFile, fixedFile);

                File readyFile;
                if (remuxed && fixedFile.exists() && fixedFile.length() > 1024) {
                    readyFile = fixedFile;
                    safeDelete(rawFile);
                } else {
                    readyFile = rawFile;
                    safeDelete(fixedFile);
                }

                long durationMs = readVideoDurationMs(readyFile);
                boolean gallerySaved = false;
                String galleryPath = "";
                String galleryError = "";

                if (shouldSaveToGallery) {
                    try {
                        galleryPath = saveVideoToGallery(appContext, readyFile, fileName, mimeType, durationMs);
                        gallerySaved = true;
                    } catch (Exception gErr) {
                        galleryError = gErr.getMessage() != null ? gErr.getMessage() : "Gallery save failed";
                    }
                }

                JSObject ret = new JSObject();
                ret.put("remuxed", remuxed);
                ret.put("gallerySaved", gallerySaved);
                ret.put("galleryPath", galleryPath);
                ret.put("galleryError", galleryError);
                ret.put("cacheUri", Uri.fromFile(readyFile).toString());
                ret.put("durationMs", durationMs);
                ret.put("sizeBytes", readyFile.length());
                call.resolve(ret);
            } catch (Exception ex) {
                call.reject("Video processing failed: " + ex.getMessage());
            }
        });
    }

    @PluginMethod
    public void cleanupCache(PluginCall call) {
        executor.execute(() -> {
            try {
                File cacheDir = getContext().getApplicationContext().getCacheDir();
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (n.endsWith(".mp4") || n.endsWith(".webm")) {
                            safeDelete(f);
                        }
                    }
                }
            } catch (Exception ignored) {}
            call.resolve();
        });
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}
