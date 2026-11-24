package com.md.mdreelcurrentaffair;

import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "NativeTts")
public class NativeTtsPlugin extends Plugin {

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean ttsInitializing = false;
    private final List<InitCallback> initQueue = new ArrayList<>();
    private final ConcurrentHashMap<String, SynthTask> pendingSynth = new ConcurrentHashMap<>();

    private static class InitCallback {
        final Runnable onReady;
        final Runnable onFail;
        InitCallback(Runnable onReady, Runnable onFail) {
            this.onReady = onReady;
            this.onFail = onFail;
        }
    }

    private static class SynthTask {
        final PluginCall call;
        final File file;
        SynthTask(PluginCall call, File file) {
            this.call = call;
            this.file = file;
        }
    }

    private void ensureTts(Runnable onReady, Runnable onFail) {
        if (ttsReady && tts != null) {
            onReady.run();
            return;
        }

        boolean shouldStart = false;
        synchronized (initQueue) {
            if (ttsReady && tts != null) {
                onReady.run();
                return;
            }
            initQueue.add(new InitCallback(onReady, onFail));
            if (!ttsInitializing) {
                ttsInitializing = true;
                shouldStart = true;
            }
        }

        if (shouldStart) {
            try {
                tts = new TextToSpeech(getContext().getApplicationContext(), status -> {
                    List<InitCallback> callbacks;
                    boolean ok = (status == TextToSpeech.SUCCESS && tts != null);
                    if (ok) {
                        try {
                            tts.setLanguage(new Locale("hi", "IN"));
                            attachProgressListener();
                        } catch (Exception ignored) {}
                    }
                    synchronized (initQueue) {
                        ttsReady = ok;
                        ttsInitializing = false;
                        if (!ok) {
                            tts = null;
                        }
                        callbacks = new ArrayList<>(initQueue);
                        initQueue.clear();
                    }
                    for (InitCallback cb : callbacks) {
                        if (ok) {
                            cb.onReady.run();
                        } else {
                            cb.onFail.run();
                        }
                    }
                });
            } catch (Exception ex) {
                List<InitCallback> callbacks;
                synchronized (initQueue) {
                    ttsReady = false;
                    ttsInitializing = false;
                    tts = null;
                    callbacks = new ArrayList<>(initQueue);
                    initQueue.clear();
                }
                for (InitCallback cb : callbacks) {
                    cb.onFail.run();
                }
            }
        }
    }

    private void attachProgressListener() {
        if (tts == null) return;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Speaking or synthesizing started
            }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId == null) return;
                SynthTask task = pendingSynth.remove(utteranceId);
                if (task == null) return;

                try {
                    byte[] wavBytes = readAllBytes(task.file);
                    if (wavBytes == null || wavBytes.length <= 44) {
                        throw new IOException("Android TTS returned an empty WAV file");
                    }
                    String b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP);
                    JSObject ret = new JSObject();
                    ret.put("base64", b64);
                    ret.put("mimeType", "audio/wav");
                    ret.put("byteLength", wavBytes.length);
                    task.call.resolve(ret);
                } catch (Exception e) {
                    task.call.reject("Failed to read synthesized WAV: " + e.getMessage());
                } finally {
                    safeDelete(task.file);
                }
            }

            @Override
            @Deprecated
            public void onError(String utteranceId) {
                failSynth(utteranceId, "TTS synthesis error");
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                failSynth(utteranceId, "TTS synthesis error code: " + errorCode);
            }
        });
    }

    private void failSynth(String utteranceId, String message) {
        if (utteranceId == null) return;
        SynthTask task = pendingSynth.remove(utteranceId);
        if (task != null) {
            safeDelete(task.file);
            task.call.reject(message);
        }
    }

    private void safeDelete(File file) {
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception ignored) {}
        }
    }

    private byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private void configureVoiceAndRate(String voiceName, float rate) {
        if (tts == null) throw new IllegalStateException("Android TTS engine unavailable");
        float clampedRate = Math.max(0.5f, Math.min(2.0f, rate));
        tts.setSpeechRate(clampedRate);
        tts.setPitch(1.0f);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Set<Voice> voices = tts.getVoices();
                Voice chosen = null;
                if (voices != null) {
                    // Honour a selected voice only when it is Hindi AND fully offline.
                    if (voiceName != null && !voiceName.trim().isEmpty()) {
                        for (Voice v : voices) {
                            if (v != null && v.getLocale() != null
                                    && "hi".equalsIgnoreCase(v.getLocale().getLanguage())
                                    && !v.isNetworkConnectionRequired()
                                    && voiceName.equals(v.getName())) {
                                chosen = v;
                                break;
                            }
                        }
                    }
                    // Otherwise choose an installed local Hindi voice automatically.
                    if (chosen == null) {
                        for (Voice v : voices) {
                            if (v != null && v.getLocale() != null
                                    && "hi".equalsIgnoreCase(v.getLocale().getLanguage())
                                    && !v.isNetworkConnectionRequired()) {
                                chosen = v;
                                break;
                            }
                        }
                    }
                }
                if (chosen == null) {
                    throw new IllegalStateException(
                        "Hindi Offline voice data is not installed. Download Hindi voice data in Android Text-to-Speech settings.");
                }
                tts.setVoice(chosen);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Could not select an offline Hindi voice: " + e.getMessage());
            }
        } else {
            int result = tts.setLanguage(new Locale("hi", "IN"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw new IllegalStateException("Hindi voice data is not installed on this device.");
            }
        }
    }

    @PluginMethod
    public void getVoices(PluginCall call) {
        ensureTts(() -> {
            JSArray arr = new JSArray();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && tts != null) {
                try {
                    Set<Voice> voices = tts.getVoices();
                    if (voices != null) {
                        for (Voice v : voices) {
                            if (v == null || v.getLocale() == null) continue;
                            JSObject obj = new JSObject();
                            obj.put("name", v.getName());
                            obj.put("lang", v.getLocale().toLanguageTag());
                            obj.put("local", !v.isNetworkConnectionRequired());
                            arr.put(obj);
                        }
                    }
                } catch (Exception ignored) {}
            }
            JSObject ret = new JSObject();
            ret.put("voices", arr);
            call.resolve(ret);
        }, () -> call.reject("Android TTS engine unavailable"));
    }

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        if (text == null || text.trim().isEmpty()) {
            call.resolve();
            return;
        }
        Float rateObj = call.getFloat("rate", 0.95f);
        float rate = (rateObj != null) ? rateObj : 0.95f;
        String voiceName = call.getString("voiceName", "");

        ensureTts(() -> {
            try {
                configureVoiceAndRate(voiceName, rate);
                String uid = "spk_" + UUID.randomUUID().toString();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
                } else {
                    //noinspection deprecation
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                }
                call.resolve();
            } catch (Exception e) {
                call.reject("Speak failed: " + e.getMessage());
            }
        }, () -> call.reject("Android TTS engine unavailable"));
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void synthesizeToBase64(PluginCall call) {
        String text = call.getString("text", "");
        if (text == null || text.trim().isEmpty()) {
            call.reject("Empty text");
            return;
        }
        Float rateObj = call.getFloat("rate", 0.95f);
        float rate = (rateObj != null) ? rateObj : 0.95f;
        String voiceName = call.getString("voiceName", "");

        ensureTts(() -> {
            try {
                configureVoiceAndRate(voiceName, rate);
                String uid = "syn_" + UUID.randomUUID().toString();
                File outFile = new File(getContext().getCacheDir(), uid + ".wav");
                pendingSynth.put(uid, new SynthTask(call, outFile));

                int result;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Bundle params = new Bundle();
                    result = tts.synthesizeToFile(text, params, outFile, uid);
                } else {
                    result = TextToSpeech.ERROR;
                }

                if (result != TextToSpeech.SUCCESS) {
                    pendingSynth.remove(uid);
                    safeDelete(outFile);
                    call.reject("synthesizeToFile failed with code: " + result);
                }
            } catch (Exception e) {
                call.reject("Synthesis exception: " + e.getMessage());
            }
        }, () -> call.reject("Android TTS engine unavailable"));
    }

    @Override
    protected void handleOnDestroy() {
        for (SynthTask task : pendingSynth.values()) {
            safeDelete(task.file);
        }
        pendingSynth.clear();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
        }
        ttsReady = false;
        super.handleOnDestroy();
    }
}
