import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 輕量 JSON 解析與輸出。
 * 只處理 Graph／Entra ID 回應需要的部分，避免在 Domino 上多掛一個 JSON jar。
 * 物件 → LinkedHashMap，陣列 → ArrayList，數字 → Long 或 Double。
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("JSON 結尾有多餘的字元");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o == null ? new ArrayList<Object>() : (List<Object>) o;
    }

    /** 依「a.b.c」路徑取字串，任何一層不存在就回傳 null。 */
    public static String str(Map<String, Object> m, String path) {
        Object cur = m;
        for (String key : path.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(key);
        }
        return cur == null ? null : cur.toString();
    }

    /** 以 key, value, key, value… 建立有順序的 Map。 */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            b.append(quote((String) v));
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Map) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                b.append(quote(e.getKey().toString())).append(':');
                write(e.getValue(), b);
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            Iterator<?> it = ((Iterable<?>) v).iterator();
            while (it.hasNext()) {
                write(it.next(), b);
                if (it.hasNext()) b.append(',');
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("不支援輸出成 JSON 的型別：" + v.getClass().getName());
        }
    }

    /** 中文等非 ASCII 字元原樣輸出，送出時以 UTF-8 編碼。 */
    public static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private Object value() {
        if (i >= s.length()) throw err("JSON 提前結束");
        switch (s.charAt(i)) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<Object>();
        i++;
        ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("字串沒有結束");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("跳脫字元不完整");
            char e = s.charAt(i++);
            switch (e) {
                case '"': case '\\': case '/': b.append(e); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("\\u 跳脫字元不完整");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("不合法的跳脫字元 \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.length() == 0) throw err("無法解析的值");
        if (n.matches("-?\\d+")) {
            try {
                return Long.valueOf(n);
            } catch (NumberFormatException tooBig) {
                return Double.valueOf(n);
            }
        }
        return Double.valueOf(n);
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw err("無法解析的值");
        i += word.length();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw err("預期是 '" + c + "'");
        i++;
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + "（位置 " + i + "）");
    }
}
