package dev.qingmo.mcwebui.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec for protocol envelopes. It intentionally accepts objects only. */
public final class BridgeCodec {
    private BridgeCodec() { }

    public static String encode(BridgeMessage message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", message.version());
        envelope.put("type", message.type());
        if (message instanceof BridgeRequest request) {
                envelope.put("id", request.id());
                envelope.put("method", request.method());
                envelope.put("payload", request.payload());
        } else if (message instanceof BridgeResponse response) {
                envelope.put("id", response.id());
                envelope.put("success", response.success());
                envelope.put("payload", response.payload());
                if (response.error() != null) envelope.put("error", Map.of("code", response.error().code(),
                        "message", response.error().message(), "details", response.error().details()));
        } else if (message instanceof BridgeEvent event) {
                envelope.put("channel", event.channel());
                envelope.put("payload", event.payload());
        } else if (message instanceof BridgeStateUpdate update) {
                envelope.put("channel", update.channel());
                envelope.put("value", update.value());
                envelope.put("revision", update.revision());
        } else if (message instanceof BridgeHandshake handshake) {
                envelope.put("runtime", handshake.runtime());
                envelope.put("capabilities", handshake.capabilities().stream().map(Enum::name).toList());
        } else {
            throw new IllegalArgumentException("Unsupported bridge message: " + message.getClass());
        }
        return Json.write(envelope);
    }

    @SuppressWarnings("unchecked")
    public static BridgeMessage decode(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Bridge envelope must be an object");
        Map<String, Object> map = stringMap(raw);
        int version = number(map, "version").intValue();
        String type = string(map, "type");
        return switch (type) {
            case "request" -> new BridgeRequest(version, string(map, "id"), string(map, "method"), mapValue(map, "payload"));
            case "response" -> {
                boolean success = bool(map, "success");
                BridgeError error = null;
                if (!success) {
                    Map<String, Object> errorMap = stringMap(valueMap(map, "error"));
                    error = new BridgeError(string(errorMap, "code"), string(errorMap, "message"), mapValue(errorMap, "details"));
                }
                yield new BridgeResponse(version, string(map, "id"), success, mapValue(map, "payload"), error);
            }
            case "event" -> new BridgeEvent(version, string(map, "channel"), mapValue(map, "payload"));
            case "state" -> new BridgeStateUpdate(version, string(map, "channel"), map.get("value"), number(map, "revision").longValue());
            case "handshake" -> {
                List<Object> values = list(map, "capabilities");
                java.util.EnumSet<BridgeCapability> capabilities = java.util.EnumSet.noneOf(BridgeCapability.class);
                for (Object value : values) capabilities.add(BridgeCapability.valueOf(String.valueOf(value)));
                yield new BridgeHandshake(version, String.valueOf(map.getOrDefault("runtime", "mcwebui")), capabilities);
            }
            default -> throw new IllegalArgumentException("Unknown bridge message type: " + type);
        };
    }

    private static Map<String, Object> mapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return Map.of();
        return stringMap(value);
    }
    private static Map<?, ?> valueMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> result)) throw new IllegalArgumentException("Expected object: " + key);
        return result;
    }
    private static List<Object> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected array: " + key);
        return new ArrayList<>(list);
    }
    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object");
        return stringMap(map);
    }
    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String stringKey)) throw new IllegalArgumentException("Object keys must be strings");
            result.put(stringKey, value);
        });
        return result;
    }
    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string)) throw new IllegalArgumentException("Expected string: " + key);
        return string;
    }
    private static Number number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected number: " + key);
        return number;
    }
    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("Expected boolean: " + key);
        return bool;
    }

    private static final class Json {
        static String write(Object value) {
            if (value == null) return "null";
            if (value instanceof String string) return quote(string);
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Map<?, ?> map) {
                StringBuilder out = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                    if (!first) out.append(',');
                    first = false;
                    out.append(quote(key)).append(':').append(write(entry.getValue()));
                }
                return out.append('}').toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder out = new StringBuilder("[");
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(write(item));
                }
                return out.append(']').toString();
            }
            if (value.getClass().isArray()) return write(java.util.Arrays.asList((Object[]) value));
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }

        private static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }

        static Object parse(String json) {
            if (json == null) throw new IllegalArgumentException("JSON must not be null");
            Parser parser = new Parser(json);
            Object result = parser.value();
            parser.ws();
            if (!parser.end()) throw new IllegalArgumentException("Trailing JSON content");
            return result;
        }

        private static final class Parser {
            private final String source;
            private int index;
            Parser(String source) { this.source = source; }
            boolean end() { return index == source.length(); }
            void ws() { while (!end() && Character.isWhitespace(source.charAt(index))) index++; }
            char take() { if (end()) throw new IllegalArgumentException("Unexpected end of JSON"); return source.charAt(index++); }
            Object value() {
                ws();
                if (end()) throw new IllegalArgumentException("Missing JSON value");
                return switch (source.charAt(index)) {
                    case '{' -> object(); case '[' -> array(); case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE); case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null); default -> number();
                };
            }
            Object literal(String token, Object value) {
                if (!source.startsWith(token, index)) throw new IllegalArgumentException("Invalid JSON literal");
                index += token.length(); return value;
            }
            Map<String, Object> object() {
                take(); Map<String, Object> result = new LinkedHashMap<>(); ws();
                if (!end() && source.charAt(index) == '}') { index++; return result; }
                while (true) {
                    ws(); String key = string(); ws(); if (take() != ':') throw new IllegalArgumentException("Expected ':'");
                    result.put(key, value()); ws(); char separator = take(); if (separator == '}') return result;
                    if (separator != ',') throw new IllegalArgumentException("Expected ','");
                }
            }
            List<Object> array() {
                take(); List<Object> result = new ArrayList<>(); ws();
                if (!end() && source.charAt(index) == ']') { index++; return result; }
                while (true) {
                    result.add(value()); ws(); char separator = take(); if (separator == ']') return result;
                    if (separator != ',') throw new IllegalArgumentException("Expected ','");
                }
            }
            String string() {
                if (take() != '"') throw new IllegalArgumentException("Expected string");
                StringBuilder result = new StringBuilder();
                while (!end()) {
                    char c = take(); if (c == '"') return result.toString();
                    if (c == '\\') {
                        char escaped = take();
                        switch (escaped) {
                            case '"', '\\', '/' -> result.append(escaped); case 'b' -> result.append('\b');
                            case 'f' -> result.append('\f'); case 'n' -> result.append('\n'); case 'r' -> result.append('\r');
                            case 't' -> result.append('\t'); case 'u' -> result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                            default -> throw new IllegalArgumentException("Invalid string escape");
                        }
                        if (escaped == 'u') index += 4;
                    } else { if (c < 0x20) throw new IllegalArgumentException("Control character in string"); result.append(c); }
                }
                throw new IllegalArgumentException("Unterminated string");
            }
            Number number() {
                int start = index; if (source.charAt(index) == '-') index++;
                while (!end() && Character.isDigit(source.charAt(index))) index++;
                boolean decimal = false;
                if (!end() && source.charAt(index) == '.') { decimal = true; index++; while (!end() && Character.isDigit(source.charAt(index))) index++; }
                if (!end() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) { decimal = true; index++; if (!end() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++; while (!end() && Character.isDigit(source.charAt(index))) index++; }
                String value = source.substring(start, index);
                try { return decimal ? Double.parseDouble(value) : Long.parseLong(value); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid number", ex); }
            }
        }
    }
}
