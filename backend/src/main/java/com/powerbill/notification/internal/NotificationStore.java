package com.powerbill.notification.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationStore {
    private static final String FIELDS = """
            SELECT id,category,title,message,link,is_read,created_at FROM notifications
            """;
    private final JdbcClient db;

    NotificationStore(JdbcClient db) {
        this.db = db;
    }

    Optional<Notification> insert(UUID user, String channel, String category, String title, String message, String link,
            String metadata, String dedupeKey) {
        return db.sql("""
                INSERT INTO notifications(id,user_id,channel,category,title,message,link,metadata,dedupe_key)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT (user_id,dedupe_key) DO NOTHING
                RETURNING id,category,title,message,link,is_read,created_at
                """).params(UUID.randomUUID(), user, channel, category, title, message, link, metadata, dedupeKey)
                .query(Notification.class).optional();
    }

    List<Notification> list(UUID user, boolean unreadOnly, int limit) {
        return db.sql(FIELDS + " WHERE user_id=? AND channel='IN_APP' AND (NOT ? OR NOT is_read)"
                + " ORDER BY created_at DESC,id LIMIT ?").params(user, unreadOnly, limit)
                .query(Notification.class).list();
    }

    int unread(UUID user) {
        return db.sql("SELECT count(*) FROM notifications WHERE user_id=? AND channel='IN_APP' AND NOT is_read")
                .param(user).query(Integer.class).single();
    }

    boolean markRead(UUID user, UUID id) {
        return db.sql("UPDATE notifications SET is_read=true WHERE id=? AND user_id=?").params(id, user).update() == 1;
    }

    int markAllRead(UUID user) {
        return db.sql("UPDATE notifications SET is_read=true WHERE user_id=? AND NOT is_read").param(user).update();
    }

    record Notification(UUID id, String category, String title, String message, String link, boolean isRead,
            OffsetDateTime createdAt) {}
}
