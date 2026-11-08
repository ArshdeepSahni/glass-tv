package com.gathin.glass.assistant;

import android.service.voice.VoiceInteractionService;

/**
 * Makes Glass the TV's assistant. The remote's assistant button doesn't send a key event: it asks Android to start
 * "the assistant", i.e. the voice interaction service set in Settings.Secure. With this service set there
 * (by the installer, reversible), the button opens Glass's assistant instead of Google's.
 */
public final class GlassVoiceService extends VoiceInteractionService {
}
