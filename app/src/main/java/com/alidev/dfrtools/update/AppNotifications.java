package com.alidev.dfrtools.update;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted in-app notification feed shown by NotificationActivity, with a red dot on Home's
 * bell icon while any entry is unread. Two producers feed it: the update checker (silent
 * background check on Home, and the manual "Cek Update" button in About) and AppFcmService when
 * a push arrives - both go through add() so a release the user hears about via push and then
 * re-confirms via the in-app checker only ever produces one entry (deduped by id).
 */
public class AppNotifications {
    private static final String PREF_NAME = "app_notifications_prefs";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 30;

    public static class Item {
        public String id;       // e.g. "update_1.0.29" - dedup key
        public String title;
        public String message;  // subtext / changelog snippet
        public long timestampMillis;
        public boolean read;

        public Item() {}

        Item(String id, String title, String message, long timestampMillis) {
            this.id = id;
            this.title = title;
            this.message = message;
            this.timestampMillis = timestampMillis;
            this.read = false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** Adds a new notification, newest first. If an entry with the same id already exists, it's
     *  left as-is (no duplicate, no read-state reset) rather than replaced. */
    public static synchronized void add(Context context, String id, String title, String message) {
        List<Item> items = getAll(context);
        for (Item existing : items) {
            if (existing.id.equals(id)) return;
        }
        items.add(0, new Item(id, title, message, System.currentTimeMillis()));
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        save(context, items);
    }

    public static synchronized List<Item> getAll(Context context) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item item = new Item();
                item.id = o.optString("id", "");
                item.title = o.optString("title", "");
                item.message = o.optString("message", "");
                item.timestampMillis = o.optLong("timestampMillis", 0);
                item.read = o.optBoolean("read", false);
                items.add(item);
            }
        } catch (JSONException ignored) {}
        return items;
    }

    public static synchronized boolean hasUnread(Context context) {
        for (Item item : getAll(context)) {
            if (!item.read) return true;
        }
        return false;
    }

    public static synchronized void markRead(Context context, String id) {
        List<Item> items = getAll(context);
        boolean changed = false;
        for (Item item : items) {
            if (item.id.equals(id) && !item.read) {
                item.read = true;
                changed = true;
            }
        }
        if (changed) save(context, items);
    }

    public static synchronized void markAllRead(Context context) {
        List<Item> items = getAll(context);
        boolean changed = false;
        for (Item item : items) {
            if (!item.read) {
                item.read = true;
                changed = true;
            }
        }
        if (changed) save(context, items);
    }

    private static void save(Context context, List<Item> items) {
        Collections.sort(items, (a, b) -> Long.compare(b.timestampMillis, a.timestampMillis));
        JSONArray arr = new JSONArray();
        try {
            for (Item item : items) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("message", item.message);
                o.put("timestampMillis", item.timestampMillis);
                o.put("read", item.read);
                arr.put(o);
            }
        } catch (JSONException ignored) {}
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply();
    }
}
