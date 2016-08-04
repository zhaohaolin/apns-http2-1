package br.com.zup.push.util;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.Date;

public class DateAsMillisecondsSinceEpochTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {

    @Override
    public Date deserialize(final JsonElement json, final Type typeOfT, final JsonDeserializationContext context) throws JsonParseException {
        final Date date;

        if (json.isJsonPrimitive()) {
            date = new Date(json.getAsLong());
        } else if (json.isJsonNull()) {
            date = null;
        } else {
            throw new JsonParseException("Dates represented as seconds since the epoch must either be numbers or null.");
        }

        return date;
    }

    @Override
    public JsonElement serialize(final Date src, final Type typeOfSrc, final JsonSerializationContext context) {
        final JsonElement element;

        if (src != null) {
            element = new JsonPrimitive(src.getTime());
        } else {
            element = JsonNull.INSTANCE;
        }

        return element;
    }
}
