package br.com.zup.push.data;

import br.com.zup.push.client.DeliveryPriority;

import java.util.Date;

public class ZupHttpPushNotification implements HttpPushNotification {

    private final String token;
    private final String payload;
    private final Date invalidationTime;
    private String topic;
    private final DeliveryPriority priority;

    public ZupHttpPushNotification(final String token, String topic, final String payload) {
        this(token, topic, payload, null, DeliveryPriority.IMMEDIATE);
    }

    public ZupHttpPushNotification(final String token, final String topic, final String payload, final Date invalidationTime) {
        this(token, topic, payload, invalidationTime, DeliveryPriority.IMMEDIATE);
    }

    public ZupHttpPushNotification(final String token, final String topic, final String payload, final Date invalidationTime, final DeliveryPriority priority) {

        this.token = token;
        this.payload = payload;
        this.invalidationTime = invalidationTime;
        this.topic = topic;
        this.priority = priority;
    }

    @Override
    public String getToken() {
        return this.token;
    }

    @Override
    public String getPayload() {
        return this.payload;
    }

    @Override
    public Date getExpiration() {
        return this.invalidationTime;
    }

    @Override
    public String getTopic() {
        return this.topic;
    }

    @Override
    public void setTopic(String topic) {
    	this.topic = topic;
    }

    @Override
    public DeliveryPriority getPriority() {
        return this.priority;
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.invalidationTime == null) ? 0 : this.invalidationTime.hashCode());
        result = prime * result + ((this.payload == null) ? 0 : this.payload.hashCode());
        result = prime * result + ((this.priority == null) ? 0 : this.priority.hashCode());
        result = prime * result + ((this.token == null) ? 0 : this.token.hashCode());
        result = prime * result + ((this.topic == null) ? 0 : this.topic.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ZupHttpPushNotification)) {
            return false;
        }
        final ZupHttpPushNotification other = (ZupHttpPushNotification) obj;
        if (this.invalidationTime == null) {
            if (other.invalidationTime != null) {
                return false;
            }
        } else if (!this.invalidationTime.equals(other.invalidationTime)) {
            return false;
        }
        if (this.payload == null) {
            if (other.payload != null) {
                return false;
            }
        } else if (!this.payload.equals(other.payload)) {
            return false;
        }
        if (this.priority != other.priority) {
            return false;
        }
        if (this.token == null) {
            if (other.token != null) {
                return false;
            }
        } else if (!this.token.equals(other.token)) {
            return false;
        }
        if (this.topic == null) {
            if (other.topic != null) {
                return false;
            }
        } else if (!this.topic.equals(other.topic)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("ZupHttpPushNotification [token=");
        builder.append(this.token);
        builder.append(", payload=");
        builder.append(this.payload);
        builder.append(", invalidationTime=");
        builder.append(this.invalidationTime);
        builder.append(", priority=");
        builder.append(this.priority);
        builder.append(", topic=");
        builder.append(this.topic);
        builder.append("]");
        return builder.toString();
    }
}