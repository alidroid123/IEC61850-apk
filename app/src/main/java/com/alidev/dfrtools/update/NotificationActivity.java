package com.alidev.dfrtools.update;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.BaseActivity;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** In-app feed of update announcements (and, in future, other app notices) - see AppNotifications. */
public class NotificationActivity extends BaseActivity {

    private final UpdateFlow updateFlow = new UpdateFlow(this);
    private RecyclerView.Adapter<NotifVH> adapter;
    private List<AppNotifications.Item> items;
    private View layoutEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnMarkAllRead).setOnClickListener(v -> {
            AppNotifications.markAllRead(this);
            reload();
        });

        layoutEmpty = findViewById(R.id.layoutNotifEmpty);
        RecyclerView rv = findViewById(R.id.rvNotifications);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<NotifVH>() {
            @NonNull @Override
            public NotifVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new NotifVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification, parent, false));
            }

            @Override public void onBindViewHolder(@NonNull NotifVH holder, int position) {
                holder.bind(items.get(position));
            }

            @Override public int getItemCount() {
                return items == null ? 0 : items.size();
            }
        };
        rv.setAdapter(adapter);

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFlow.onResume();
    }

    private void reload() {
        items = AppNotifications.getAll(this);
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Update-tagged entries re-check GitHub for a fresh download link before offering the
     *  install dialog, rather than trying to persist the APK URL alongside the notification. */
    private void onItemClicked(AppNotifications.Item item) {
        AppNotifications.markRead(this, item.id);
        reload();

        if (item.id.startsWith("update_")) {
            UpdateChecker.checkForUpdate(this, info -> {
                if (info == null || isFinishing()) return;
                updateFlow.showUpdateDialog(info);
            });
        }
    }

    private static String relativeTime(android.content.Context context, long millis) {
        long diff = System.currentTimeMillis() - millis;
        long minutes = diff / (60 * 1000);
        long hours = diff / (60 * 60 * 1000);
        long days = diff / (24 * 60 * 60 * 1000);

        if (minutes < 1) return context.getString(R.string.lbl_notif_time_now);
        if (minutes < 60) return context.getString(R.string.lbl_notif_time_minutes, minutes);
        if (hours < 24) return context.getString(R.string.lbl_notif_time_hours, hours);
        if (days < 7) return context.getString(R.string.lbl_notif_time_days, days);
        return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(millis);
    }

    class NotifVH extends RecyclerView.ViewHolder {
        final ImageView imgIcon;
        final TextView txtTitle, txtMessage, txtTime;
        final View dotUnread;

        NotifVH(@NonNull View v) {
            super(v);
            imgIcon = v.findViewById(R.id.imgNotifIcon);
            txtTitle = v.findViewById(R.id.txtNotifTitle);
            txtMessage = v.findViewById(R.id.txtNotifMessage);
            txtTime = v.findViewById(R.id.txtNotifTime);
            dotUnread = v.findViewById(R.id.dotNotifItemUnread);
        }

        void bind(AppNotifications.Item item) {
            txtTitle.setText(item.title);
            if (item.message == null || item.message.trim().isEmpty()) {
                txtMessage.setVisibility(View.GONE);
            } else {
                txtMessage.setVisibility(View.VISIBLE);
                txtMessage.setText(item.message.trim());
            }
            txtTime.setText(relativeTime(NotificationActivity.this, item.timestampMillis));
            dotUnread.setVisibility(item.read ? View.GONE : View.VISIBLE);
            imgIcon.setImageResource(R.drawable.ic_download);
            itemView.setOnClickListener(v -> onItemClicked(item));
        }
    }
}
