package fm.sweet977.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the LED screen when the TV turns on (and after the app updates itself). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(MainActivity.AUTOSTART, true)) return;
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // some TVs block this until "Display over other apps" is allowed
        }
    }
}
