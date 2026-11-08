package com.gathin.glass.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Each press of the assistant button: open Glass's assistant listening, then step out of the way. */
public final class GlassSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    private static final class Session extends VoiceInteractionSession {
        Session(Context ctx) { super(ctx); }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Intent ask = new Intent(getContext(), AskActivity.class)
                    .putExtra("voice", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startAssistantActivity(ask);
            } catch (RuntimeException e) {
                getContext().startActivity(ask);
            }
            hide();
        }
    }
}
