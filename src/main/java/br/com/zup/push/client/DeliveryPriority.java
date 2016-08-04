package br.com.zup.push.client;

public enum DeliveryPriority {

    IMMEDIATE(10),

    CONSERVE_POWER(5);

    private final int code;

    DeliveryPriority(final int code) {
        this.code = code;
    }

    protected int getCode() {
        return this.code;
    }

    protected static DeliveryPriority getFromCode(final int code) {
        for (final DeliveryPriority priority : DeliveryPriority.values()) {
            if (priority.getCode() == code) {
                return priority;
            }
        }

        throw new IllegalArgumentException(String.format("No delivery priority found with code %d", code));
    }
}