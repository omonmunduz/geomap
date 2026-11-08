package kg.nsdi.geoportal.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Rows {
    private Rows() {}

    /** Переименовывает snake_case ключи строки в camelCase для JSON-ответов. */
    public static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(toCamel(k), v instanceof org.postgresql.util.PGobject pg ? pg.getValue() : v));
        return out;
    }

    public static String toCamel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }

    public static UUID uuid(String s) {
        try { return s == null || s.isBlank() ? null : UUID.fromString(s); }
        catch (IllegalArgumentException e) { throw ApiException.bad("Некорректный идентификатор: " + s); }
    }
}
