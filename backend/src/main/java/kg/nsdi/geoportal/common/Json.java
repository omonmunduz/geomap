package kg.nsdi.geoportal.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Небольшие утилиты для jsonb ↔ Java. */
@Component
public class Json {
    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) { this.mapper = mapper; }

    public String write(Object o) {
        try { return o == null ? null : mapper.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> map(String s) {
        try { return s == null ? null : mapper.readValue(s, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public List<Object> list(String s) {
        try { return s == null ? null : mapper.readValue(s, new TypeReference<List<Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Значение jsonb из ResultSet как дерево (Map/List/скаляр). */
    public Object tree(String s) {
        try { return s == null ? null : mapper.readValue(s, Object.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public ObjectMapper mapper() { return mapper; }
}
