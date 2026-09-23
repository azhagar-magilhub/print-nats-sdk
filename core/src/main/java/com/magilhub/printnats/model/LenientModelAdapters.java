package com.magilhub.printnats.model;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Tolerates a scalar where a nested model object is expected, e.g. maghilOrder's local order carries
 * {@code "paymentStatus": "Unpaid"} while the KOT model's {@code paymentStatus} is a {@link PaymentStatus} object.
 * Plain Gson throws ("Expected BEGIN_OBJECT but was STRING") and the whole ticket fails to render; here the
 * scalar is skipped and the field left null. Only applies to classes in this model package.
 */
public final class LenientModelAdapters implements TypeAdapterFactory {
    private static final String PKG = LenientModelAdapters.class.getPackage().getName() + ".";

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<? super T> raw = type.getRawType();
        if (raw.isEnum() || !raw.getName().startsWith(PKG)) return null;
        final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                delegate.write(out, value);
            }

            @Override
            public T read(JsonReader in) throws IOException {
                JsonToken t = in.peek();
                if (t == JsonToken.STRING || t == JsonToken.NUMBER || t == JsonToken.BOOLEAN) {
                    in.skipValue();
                    return null;
                }
                return delegate.read(in);
            }
        };
    }
}
