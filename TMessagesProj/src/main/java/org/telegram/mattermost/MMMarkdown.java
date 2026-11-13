package org.telegram.mattermost;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Converts between Mattermost markdown and Telegram's plain text + entities.
 * Covers the common subset: bold, italic, strike, inline code, code blocks, links, headings, quotes.
 */
public class MMMarkdown {

    public static class Result {
        public String text;
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
    }

    private static void add(Result r, TLRPC.MessageEntity e, int offset, int length) {
        if (length <= 0) {
            return;
        }
        e.offset = offset;
        e.length = length;
        r.entities.add(e);
    }

    public static Result parse(String md) {
        Result r = new Result();
        if (md == null) {
            r.text = "";
            return r;
        }
        StringBuilder out = new StringBuilder();
        int n = md.length();
        int i = 0;
        // open style stacks: marker -> start offset in out
        int boldStart = -1, italicStart = -1, strikeStart = -1;
        String italicMarker = null;
        boolean lineStart = true;
        while (i < n) {
            char c = md.charAt(i);
            // code block
            if (md.startsWith("```", i)) {
                int lineEnd = md.indexOf('\n', i + 3);
                int close = lineEnd < 0 ? -1 : md.indexOf("```", lineEnd + 1);
                if (lineEnd >= 0 && close > 0) {
                    String lang = md.substring(i + 3, lineEnd).trim();
                    String code = md.substring(lineEnd + 1, close);
                    if (code.endsWith("\n")) {
                        code = code.substring(0, code.length() - 1);
                    }
                    TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                    pre.language = lang;
                    int start = out.length();
                    out.append(code);
                    add(r, pre, start, code.length());
                    i = close + 3;
                    lineStart = false;
                    continue;
                }
            }
            // inline code
            if (c == '`') {
                int close = md.indexOf('`', i + 1);
                if (close > i + 1 && md.substring(i + 1, close).indexOf('\n') < 0) {
                    int start = out.length();
                    out.append(md, i + 1, close);
                    add(r, new TLRPC.TL_messageEntityCode(), start, close - i - 1);
                    i = close + 1;
                    lineStart = false;
                    continue;
                }
            }
            // headings and quotes at line start
            if (lineStart) {
                int h = 0;
                while (i + h < n && md.charAt(i + h) == '#' && h < 6) {
                    h++;
                }
                if (h > 0 && i + h < n && md.charAt(i + h) == ' ') {
                    int lineEnd = md.indexOf('\n', i);
                    if (lineEnd < 0) {
                        lineEnd = n;
                    }
                    Result inner = parse(md.substring(i + h + 1, lineEnd));
                    int start = out.length();
                    out.append(inner.text);
                    for (TLRPC.MessageEntity e : inner.entities) {
                        e.offset += start;
                        r.entities.add(e);
                    }
                    add(r, new TLRPC.TL_messageEntityBold(), start, inner.text.length());
                    i = lineEnd;
                    lineStart = false;
                    continue;
                }
                if (c == '>' && (i + 1 < n && md.charAt(i + 1) == ' ')) {
                    int lineEnd = md.indexOf('\n', i);
                    if (lineEnd < 0) {
                        lineEnd = n;
                    }
                    Result inner = parse(md.substring(i + 2, lineEnd));
                    int start = out.length();
                    out.append(inner.text);
                    for (TLRPC.MessageEntity e : inner.entities) {
                        e.offset += start;
                        r.entities.add(e);
                    }
                    add(r, new TLRPC.TL_messageEntityBlockquote(), start, inner.text.length());
                    i = lineEnd;
                    lineStart = false;
                    continue;
                }
            }
            // link [text](url)
            if (c == '[') {
                int closeText = md.indexOf(']', i + 1);
                if (closeText > i && closeText + 1 < n && md.charAt(closeText + 1) == '(') {
                    int closeUrl = md.indexOf(')', closeText + 2);
                    if (closeUrl > 0) {
                        String text = md.substring(i + 1, closeText);
                        String url = md.substring(closeText + 2, closeUrl).trim();
                        if (text.indexOf('\n') < 0 && url.indexOf(' ') < 0 && !url.isEmpty()) {
                            TLRPC.TL_messageEntityTextUrl e = new TLRPC.TL_messageEntityTextUrl();
                            e.url = url;
                            int start = out.length();
                            out.append(text);
                            add(r, e, start, text.length());
                            i = closeUrl + 1;
                            lineStart = false;
                            continue;
                        }
                    }
                }
            }
            if (md.startsWith("**", i) || md.startsWith("__", i)) {
                if (boldStart < 0) {
                    if (md.indexOf(md.substring(i, i + 2), i + 2) > 0) {
                        boldStart = out.length();
                        i += 2;
                        continue;
                    }
                } else {
                    add(r, new TLRPC.TL_messageEntityBold(), boldStart, out.length() - boldStart);
                    boldStart = -1;
                    i += 2;
                    continue;
                }
            }
            if (md.startsWith("~~", i)) {
                if (strikeStart < 0) {
                    if (md.indexOf("~~", i + 2) > 0) {
                        strikeStart = out.length();
                        i += 2;
                        continue;
                    }
                } else {
                    add(r, new TLRPC.TL_messageEntityStrike(), strikeStart, out.length() - strikeStart);
                    strikeStart = -1;
                    i += 2;
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                String m = String.valueOf(c);
                boolean prevWord = i > 0 && Character.isLetterOrDigit(md.charAt(i - 1));
                boolean nextWord = i + 1 < n && !Character.isWhitespace(md.charAt(i + 1));
                if (italicStart < 0) {
                    int close = md.indexOf(m, i + 1);
                    if (!prevWord && nextWord && close > i + 1 && md.substring(i + 1, close).indexOf('\n') < 0) {
                        italicStart = out.length();
                        italicMarker = m;
                        i++;
                        continue;
                    }
                } else if (m.equals(italicMarker)) {
                    add(r, new TLRPC.TL_messageEntityItalic(), italicStart, out.length() - italicStart);
                    italicStart = -1;
                    italicMarker = null;
                    i++;
                    continue;
                }
            }
            out.append(c);
            lineStart = c == '\n';
            i++;
        }
        r.text = out.toString();
        return r;
    }

    /**
     * Telegram text + entities to Mattermost markdown.
     */
    public static String toMarkdown(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null) {
            return "";
        }
        if (entities == null || entities.isEmpty()) {
            return text;
        }
        // build insertion lists
        int n = text.length();
        ArrayList<String>[] before = new ArrayList[n + 1];
        ArrayList<String>[] after = new ArrayList[n + 1];
        ArrayList<TLRPC.MessageEntity> sorted = new ArrayList<>(entities);
        Collections.sort(sorted, (a, b) -> a.offset != b.offset ? Integer.compare(a.offset, b.offset) : Integer.compare(b.length, a.length));
        for (TLRPC.MessageEntity e : sorted) {
            int s = Math.max(0, Math.min(n, e.offset));
            int end = Math.max(s, Math.min(n, e.offset + e.length));
            String open = null, close = null;
            if (e instanceof TLRPC.TL_messageEntityBold) {
                open = close = "**";
            } else if (e instanceof TLRPC.TL_messageEntityItalic) {
                open = close = "_";
            } else if (e instanceof TLRPC.TL_messageEntityStrike) {
                open = close = "~~";
            } else if (e instanceof TLRPC.TL_messageEntityCode) {
                open = close = "`";
            } else if (e instanceof TLRPC.TL_messageEntityPre) {
                String lang = ((TLRPC.TL_messageEntityPre) e).language;
                open = "```" + (lang == null ? "" : lang) + "\n";
                close = "\n```";
            } else if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                open = "[";
                close = "](" + e.url + ")";
            } else if (e instanceof TLRPC.TL_messageEntityBlockquote) {
                open = "> ";
                close = "";
            }
            if (open == null) {
                continue;
            }
            if (before[s] == null) {
                before[s] = new ArrayList<>();
            }
            before[s].add(open);
            if (after[end] == null) {
                after[end] = new ArrayList<>();
            }
            after[end].add(0, close);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= n; i++) {
            if (after[i] != null) {
                for (String s : after[i]) {
                    sb.append(s);
                }
            }
            if (before[i] != null) {
                for (String s : before[i]) {
                    sb.append(s);
                }
            }
            if (i < n) {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString();
    }
}
