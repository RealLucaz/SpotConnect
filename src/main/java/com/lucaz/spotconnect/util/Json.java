package com.lucaz.spotconnect.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON reader.
 *
 * Returns null rather than throwing on anything it cannot read. Every byte it sees comes
 * off the network, so "malformed" is a normal Tuesday: a truncated response on flaky
 * wifi, a captive portal serving an HTML login page, a proxy error, an empty body on a
 * status that usually has one. The old version indexed the string without bounds checks
 * and threw on all of those, which turned a bad connection into a stack trace per poll.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** @return the parsed value, or null if the text is not usable JSON. */
    public static Object parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            Json j = new Json(s);
            j.ws();
            return j.eof() ? null : j.value();
        } catch (Exception e) {
            // Deliberately swallowed. Callers all treat null as "no data", which is the
            // correct reading of a response we could not understand.
            return null;
        }
    }

    /** Escapes a string as a JSON literal, or the bare word {@code null}. */
    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // Control characters are illegal raw inside a JSON string.
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ cursor

    private boolean eof() { return i >= s.length(); }
    private char peek()   { return s.charAt(i); }

    private void ws() {
        while (!eof() && Character.isWhitespace(s.charAt(i))) i++;
    }

    /** Consumes a literal, or fails the parse if it is not actually there. */
    private Object literal(String word, Object result) {
        if (!s.startsWith(word, i)) throw new IllegalStateException("bad literal");
        i += word.length();
        return result;
    }

    // ------------------------------------------------------------------ values

    private Object value() {
        if (eof()) throw new IllegalStateException("truncated");
        return switch (peek()) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> str();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default  -> num();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (!eof() && peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (eof() || peek() != '"') throw new IllegalStateException("expected key");
            String k = str();
            ws();
            if (eof() || peek() != ':') throw new IllegalStateException("expected colon");
            i++; ws();
            m.put(k, value());
            ws();
            if (!eof() && peek() == ',') { i++; continue; }
            if (!eof() && peek() == '}') i++;
            return m;
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (!eof() && peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (!eof() && peek() == ',') { i++; continue; }
            if (!eof() && peek() == ']') i++;
            return l;
        }
    }

    private String str() {
        StringBuilder sb = new StringBuilder();
        i++;                                        // opening quote
        while (true) {
            if (eof()) throw new IllegalStateException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }

            if (eof()) throw new IllegalStateException("trailing escape");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    // Needs four more characters; a response cut mid-escape has fewer.
                    if (i + 4 > s.length()) throw new IllegalStateException("short \\u");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Object num() {
        int start = i;
        while (!eof() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        // Empty means the character was not the start of any value we know, so the text
        // is not JSON at all - an HTML error page, most often.
        if (t.isEmpty()) throw new IllegalStateException("not a value");
        if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException overflow) {
            // Out of long range. Keep it as a double rather than losing the field.
            return Double.parseDouble(t);
        }
    }
}
