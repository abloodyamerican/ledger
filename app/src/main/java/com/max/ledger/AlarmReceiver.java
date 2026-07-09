package com.max.ledger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Fires daily at ~9 AM (even with the app closed), reads the schedule the
 *  HTML app saved via Android.setSchedule(), and posts anything dated today. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String json = ctx.getSharedPreferences("ledger", Context.MODE_PRIVATE)
                             .getString("schedule", "[]");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            JSONArray arr = new JSONArray(json);
            int id = 5000;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (today.equals(o.optString("d"))) {
                    Notify.post(ctx, o.optString("t", "Ledger"), o.optString("b", ""), id++);
                }
            }
        } catch (Exception ignored) {}
    }
}
