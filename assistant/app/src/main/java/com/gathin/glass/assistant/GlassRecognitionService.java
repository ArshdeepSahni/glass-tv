package com.gathin.glass.assistant;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Android requires an assistant to name a recognition service. Glass keeps using the TV's own speech recognizer
 * (the installer leaves voice_recognition_service as it was), so this one only answers politely if ever called.
 */
public final class GlassRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback cb) {
        try { cb.error(SpeechRecognizer.ERROR_CLIENT); } catch (Exception ignored) { }
    }
    @Override protected void onCancel(Callback cb) { }
    @Override protected void onStopListening(Callback cb) { }
}
