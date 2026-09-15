package com.petplatform.boot.adapter.web.c;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict C-end request parsing: unknown fields, wrong types and null values are all 400. */
public final class CHttpModels {
    private CHttpModels() {}

    public static final class Request {
        private final Map<String, Object> fields;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public Request(Map<String, Object> values) {
            if (values == null) throw new IllegalArgumentException("Body required");
            fields = new LinkedHashMap<>(values);
        }

        public void require(Set<String> allowed, String... required) {
            if (!allowed.containsAll(fields.keySet())
                    || fields.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Unexpected fields");
            }
            for (String name : required) {
                if (!fields.containsKey(name)) throw new IllegalArgumentException("Missing field " + name);
            }
        }

        public String string(String name) {
            Object v = fields.get(name);
            if (v == null) return null;
            if (!(v instanceof String s)) throw new IllegalArgumentException(name + " must be a string");
            return s;
        }

        public Boolean bool(String name) {
            Object v = fields.get(name);
            if (v == null) return null;
            if (!(v instanceof Boolean b)) throw new IllegalArgumentException(name + " must be a boolean");
            return b;
        }

        @Override
        public String toString() {
            return "CRequest[REDACTED]";
        }
    }
}
