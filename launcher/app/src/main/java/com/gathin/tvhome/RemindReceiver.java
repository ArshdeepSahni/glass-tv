package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** A reminder the assistant set: Glass's note at the top right, over whatever is playing, with a soft chime. */
public final class RemindReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra("text");
        if (text == null || text.trim().isEmpty()) text = "Your reminder";
        HomeKeyService svc = HomeKeyService.current();
        if (svc != null) {
            Hud.of(svc).note("Reminder", text);
            Sound.glass();
        } else {
            Toast.makeText(context, "Reminder: " + text, Toast.LENGTH_LONG).show();
        }
    }
}
