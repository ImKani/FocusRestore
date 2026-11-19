/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 语法/有界编码器；导出和 D1 通知适配复用语法层，不施加规则字段白名单。 */
final class IslandExportJson {
    static final class Invalid extends RuntimeException { private static final long serialVersionUID = 1L; }
    static final class Capacity extends RuntimeException { private static final long serialVersionUID = 1L; }
    static final class NumberToken {
        final String text;
        NumberToken(String text) { this.text = text; }
    }
    private static final class Frame {
        final Object container;
        String key;
        int state;
        Frame(Object container) { this.container = container; }
    }
    private final String input;
    private int position;
    private final Deque<Frame> stack = new ArrayDeque<Frame>();
    private final Runnable checkpoint;
    private int work;
    private IslandExportJson(String input, Runnable checkpoint) {
        this.input = input;
        this.checkpoint = checkpoint;
    }
    private void tick() {
        if (checkpoint != null && (++work & 255) == 0) checkpoint.run();
    }

    static Object parse(String text) { return parse(text, null); }

    /** C3-1 在有限迭代/词法工作中检查时间；旧调用者不接预算。 */
    static Object parse(String text, Runnable checkpoint) {
        if (text == null) throw new Invalid();
        if (checkpoint != null) checkpoint.run();
        IslandExportJson parser = new IslandExportJson(text, checkpoint);
        Object root = parser.value();
        while (!parser.stack.isEmpty()) parser.advance(parser.stack.peek());
        parser.space();
        if (parser.position != text.length()) throw new Invalid();
        if (checkpoint != null) checkpoint.run();
        return root;
    }

    @SuppressWarnings("unchecked")
    private void advance(Frame frame) {
        tick();
        space();
        if (frame.container instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) frame.container;
            if (frame.state == 0 || frame.state == 4) {
                if (frame.state == 0 && take('}')) { stack.pop(); return; }
                if (!at('"')) throw new Invalid();
                frame.key = string();
                if (map.containsKey(frame.key)) throw new Invalid();
                frame.state = 1;
            } else if (frame.state == 1) {
                if (!take(':')) throw new Invalid();
                frame.state = 2;
            } else if (frame.state == 2) {
                frame.state = 3;
                map.put(frame.key, value());
            } else if (take('}')) stack.pop();
            else if (take(',')) frame.state = 4;
            else throw new Invalid();
        } else {
            List<Object> list = (List<Object>) frame.container;
            if (frame.state == 0 || frame.state == 2) {
                if (frame.state == 0 && take(']')) { stack.pop(); return; }
                frame.state = 1;
                list.add(value());
            } else if (take(']')) stack.pop();
            else if (take(',')) frame.state = 2;
            else throw new Invalid();
        }
    }

    private Object value() {
        space();
        if (position >= input.length()) throw new Invalid();
        char c = input.charAt(position);
        if (c == '{' || c == '[') {
            position++;
            Object container = c == '{' ? new LinkedHashMap<String, Object>() : new ArrayList<Object>();
            stack.push(new Frame(container));
            return container;
        }
        if (c == '"') return string();
        if (c == 't') { word("true"); return Boolean.TRUE; }
        if (c == 'f') { word("false"); return Boolean.FALSE; }
        if (c == 'n') { word("null"); return null; }
        int start = position;
        take('-');
        if (!take('0')) { if (!digit19()) throw new Invalid(); while (digit()) position++; }
        if (take('.')) { if (!digit()) throw new Invalid(); while (digit()) position++; }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            if (!digit()) throw new Invalid();
            while (digit()) position++;
        }
        return new NumberToken(input.substring(start, position));
    }

    private String string() {
        if (!take('"')) throw new Invalid();
        StringBuilder out = new StringBuilder();
        while (position < input.length()) {
            tick();
            char c = input.charAt(position++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new Invalid();
            if (c == '\\') {
                if (position == input.length()) throw new Invalid();
                c = input.charAt(position++);
                switch (c) {
                    case '"': case '\\': case '/': out.append(c); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            if (position >= input.length()) throw new Invalid();
                            char hex = input.charAt(position++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw new Invalid();
                            code = (code << 4) | digit;
                        }
                        out.append((char) code); break;
                    default: throw new Invalid();
                }
            } else out.append(c);
        }
        throw new Invalid();
    }
    private void word(String word) {
        if (!input.startsWith(word, position)) throw new Invalid();
        position += word.length();
    }
    private boolean digit19() {
        if (position < input.length() && input.charAt(position) >= '1' && input.charAt(position) <= '9') {
            position++; return true;
        }
        return false;
    }
    private boolean digit() { tick(); return position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9'; }
    private boolean at(char c) { return position < input.length() && input.charAt(position) == c; }
    private boolean take(char c) { if (!at(c)) return false; position++; return true; }
    private void space() {
        while (position < input.length() && " \t\r\n".indexOf(input.charAt(position)) >= 0) {
            tick();
            position++;
        }
    }

    static final class Text {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private int bytes;
        Text(int limit) { this.limit = limit; }
        Text add(String part) {
            // 所有代理项由 JSON 字符串编码器转义；固定 Markdown 片段也不携带孤立代理项。
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                int count;
                if (c <= 0x7f) count = 1;
                else if (c <= 0x7ff) count = 2;
                else if (Character.isHighSurrogate(c) && i + 1 < part.length()
                        && Character.isLowSurrogate(part.charAt(i + 1))) { count = 4; i++; }
                else count = Character.isSurrogate(c) ? 1 : 3;
                if (bytes > limit - count) throw new Capacity();
                bytes += count;
            }
            text.append(part);
            return this;
        }
        int bytes() { return bytes; }
        @Override public String toString() { return text.toString(); }
    }
    private static final class Raw {
        final String text;
        Raw(String text) { this.text = text; }
    }
    static String write(Object value, int limit) {
        Text output = new Text(limit);
        Deque<Object> tasks = new ArrayDeque<Object>();
        // ArrayDeque 不支持 null，以固定 Raw 令牌表示 JSON null。
        tasks.push(value == null ? new Raw("null") : value);
        while (!tasks.isEmpty()) {
            Object item = tasks.pop();
            if (item instanceof Raw) output.add(((Raw) item).text);
            else if (item instanceof String) quote(output, (String) item);
            else if (item instanceof NumberToken) output.add(((NumberToken) item).text);
            else if (item instanceof Number || item instanceof Boolean) output.add(item.toString());
            else if (item instanceof Map) {
                List<? extends Map.Entry<?, ?>> entries = new ArrayList<Map.Entry<?, ?>>(((Map<?, ?>) item).entrySet());
                output.add("{"); tasks.push(new Raw("}"));
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<?, ?> entry = entries.get(i);
                    tasks.push(entry.getValue() == null ? new Raw("null") : entry.getValue());
                    tasks.push(new Raw(":")); tasks.push((String) entry.getKey());
                    if (i > 0) tasks.push(new Raw(","));
                }
            } else if (item instanceof List) {
                List<?> list = (List<?>) item;
                output.add("["); tasks.push(new Raw("]"));
                for (int i = list.size() - 1; i >= 0; i--) {
                    tasks.push(list.get(i) == null ? new Raw("null") : list.get(i));
                    if (i > 0) tasks.push(new Raw(","));
                }
            } else throw new Invalid();
        }
        return output.toString();
    }
    private static void quote(Text out, String text) {
        out.add("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') out.add("\\\"");
            else if (c == '\\') out.add("\\\\");
            else if (c < 0x20 || Character.isSurrogate(c) || c == 0x2028 || c == 0x2029) {
                String hex = Integer.toHexString(c);
                out.add("\\u" + "0000".substring(hex.length()) + hex);
            } else out.add(String.valueOf(c));
        }
        out.add("\"");
    }
    private IslandExportJson() { throw new AssertionError(); }
}
