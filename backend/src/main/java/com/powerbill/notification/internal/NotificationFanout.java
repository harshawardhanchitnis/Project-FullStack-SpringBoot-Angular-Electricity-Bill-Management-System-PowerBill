package com.powerbill.notification.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes live notifications on a Redis channel that every Core API instance subscribes to, so a customer's
 * stream receives events whichever instance handled the domain event. If Redis is unavailable, the event is
 * delivered to streams on this instance only; the persisted inbox remains complete either way.
 */
@Component
class NotificationFanout {
    static final String CHANNEL = "gridiq:notifications";
    private static final Logger LOG = LoggerFactory.getLogger(NotificationFanout.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final NotificationStream stream;

    NotificationFanout(StringRedisTemplate redis, ObjectMapper json, NotificationStream stream) {
        this.redis = redis;
        this.json = json;
        this.stream = stream;
    }

    record Message(UUID userId, NotificationStore.Notification notification, int unread) {}

    void publish(UUID user, NotificationStore.Notification notification, int unread) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(new Message(user, notification, unread)));
        } catch (DataAccessException | JacksonException error) {
            LOG.debug("Live notification fan-out unavailable; delivering locally ({})", error.getClass().getSimpleName());
            stream.push(user, notification, unread);
        }
    }

    /** Called for every message on the channel, including this instance's own. */
    void receive(String body) {
        try {
            var message = json.readValue(body, Message.class);
            if (message.userId() != null && message.notification() != null) {
                stream.push(message.userId(), message.notification(), message.unread());
            }
        } catch (JacksonException error) {
            LOG.warn("Ignored a malformed live notification message");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Subscription {
        @Bean
        RedisMessageListenerContainer notificationListener(RedisConnectionFactory connections, NotificationFanout fanout) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connections);
            container.addMessageListener((message, pattern) ->
                    fanout.receive(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
