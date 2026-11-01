package com.metakamera.core.xmp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, position-preserving XML tokenizer for XMP packets.
 *
 * <p>Produces a flat token list (start/end tags, text, CDATA, comments, processing instructions)
 * with exact character positions so callers can splice the original string, plus element nesting
 * and namespace resolution. Anything that is not well-formed (unterminated markup, mismatched end
 * tags, attributes without quotes...) raises {@link XmlException}; no RuntimeException escapes for
 * any input string.
 */
final class XmlScanner {

    static final int START = 1;
    static final int END = 2;
    static final int TEXT = 3;
    static final int CDATA = 4;
    static final int COMMENT = 5;
    static final int PI = 6;
    static final int DECL = 7;

    static final String NS_XML = "http://www.w3.org/XML/1998/namespace";

    /** Thrown for input that is not well-formed XML. */
    static final class XmlException extends Exception {
        XmlException(String msg) {
            super(msg);
        }
    }

    static final class Attr {
        String qname;
        /** Start of the whitespace that precedes the attribute name. */
        int wsStart;
        int nameStart;
        /** Value content (without quotes): [valueStart, valueEnd). */
        int valueStart;
        int valueEnd;
        /** Position after the closing quote. */
        int end;
        char quote;
        /** Resolved namespace URI (null = no namespace / unbound prefix / xmlns declaration). */
        String uri;
        String local;
    }

    static final class Token {
        int type;
        /** [start, end) including the markup delimiters. */
        int start;
        int end;
        String qname;
        boolean selfClosing;
        List<Attr> attrs;
        /** START: index of the matching END token (itself when self-closing). END: index of its START. */
        int match = -1;
        /** Index of the enclosing START token, -1 at top level. */
        int parent = -1;
        String uri;
        String local;
    }

    /** Linked namespace scope. */
    private static final class Scope {
        final Scope parent;
        Map<String, String> map;

        Scope(Scope parent) {
            this.parent = parent;
        }

        void put(String prefix, String uri) {
            if (map == null) map = new HashMap<String, String>();
            map.put(prefix, uri);
        }

        String lookup(String prefix) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.map != null && s.map.containsKey(prefix)) {
                    String u = s.map.get(prefix);
                    return u == null || u.length() == 0 ? null : u;
                }
            }
            return "xml".equals(prefix) ? NS_XML : null;
        }
    }

    private XmlScanner() {
    }

    static List<Token> parse(String s) throws XmlException {
        List<Token> toks = tokenize(s);
        structure(s, toks);
        return toks;
    }

    // ------------------------------------------------------------------ tokenizer

    private static List<Token> tokenize(String s) throws XmlException {
        List<Token> out = new ArrayList<Token>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (s.charAt(i) != '<') {
                int j = s.indexOf('<', i);
                if (j < 0) j = n;
                out.add(simple(TEXT, i, j));
                i = j;
                continue;
            }
            if (s.startsWith("<!--", i)) {
                int j = s.indexOf("-->", i + 4);
                if (j < 0) throw new XmlException("unterminated comment");
                out.add(simple(COMMENT, i, j + 3));
                i = j + 3;
            } else if (s.startsWith("<![CDATA[", i)) {
                int j = s.indexOf("]]>", i + 9);
                if (j < 0) throw new XmlException("unterminated CDATA");
                out.add(simple(CDATA, i, j + 3));
                i = j + 3;
            } else if (s.startsWith("<?", i)) {
                int j = s.indexOf("?>", i + 2);
                if (j < 0) throw new XmlException("unterminated PI");
                out.add(simple(PI, i, j + 2));
                i = j + 2;
            } else if (s.startsWith("<!", i)) {
                int j = skipDecl(s, i + 2);
                out.add(simple(DECL, i, j));
                i = j;
            } else if (s.startsWith("</", i)) {
                Token t = new Token();
                t.type = END;
                t.start = i;
                int k = i + 2;
                int ns = k;
                k = skipName(s, k);
                if (k == ns) throw new XmlException("bad end tag");
                t.qname = s.substring(ns, k);
                k = skipWs(s, k);
                if (k >= n || s.charAt(k) != '>') throw new XmlException("bad end tag");
                t.end = k + 1;
                out.add(t);
                i = t.end;
            } else {
                Token t = startTag(s, i);
                out.add(t);
                i = t.end;
            }
        }
        return out;
    }

    private static Token startTag(String s, int i) throws XmlException {
        int n = s.length();
        Token t = new Token();
        t.type = START;
        t.start = i;
        t.attrs = new ArrayList<Attr>(4);
        int k = i + 1;
        int ns = k;
        k = skipName(s, k);
        if (k == ns) throw new XmlException("bad start tag");
        t.qname = s.substring(ns, k);
        while (true) {
            int ws = k;
            k = skipWs(s, k);
            if (k >= n) throw new XmlException("unterminated start tag");
            char c = s.charAt(k);
            if (c == '>') {
                t.end = k + 1;
                return t;
            }
            if (c == '/') {
                if (k + 1 < n && s.charAt(k + 1) == '>') {
                    t.selfClosing = true;
                    t.end = k + 2;
                    return t;
                }
                throw new XmlException("bad start tag");
            }
            if (ws == k) throw new XmlException("missing whitespace before attribute");
            Attr a = new Attr();
            a.wsStart = ws;
            a.nameStart = k;
            k = skipName(s, k);
            if (k == a.nameStart) throw new XmlException("bad attribute name");
            a.qname = s.substring(a.nameStart, k);
            k = skipWs(s, k);
            if (k >= n || s.charAt(k) != '=') throw new XmlException("attribute without value");
            k = skipWs(s, k + 1);
            if (k >= n) throw new XmlException("attribute without value");
            char q = s.charAt(k);
            if (q != '"' && q != '\'') throw new XmlException("unquoted attribute");
            a.quote = q;
            a.valueStart = k + 1;
            // single pass over the value (values can be megabytes, e.g. base64 depth maps)
            int close = -1;
            for (int j = a.valueStart; j < n; j++) {
                char ch = s.charAt(j);
                if (ch == q) {
                    close = j;
                    break;
                }
                if (ch == '<') throw new XmlException("'<' in attribute value");
            }
            if (close < 0) throw new XmlException("unterminated attribute");
            a.valueEnd = close;
            a.end = close + 1;
            t.attrs.add(a);
            k = a.end;
        }
    }

    /** Skips a markup declaration (e.g. DOCTYPE) including an internal subset in [...]. */
    private static int skipDecl(String s, int k) throws XmlException {
        int n = s.length();
        int depth = 0;
        char quote = 0;
        while (k < n) {
            char c = s.charAt(k++);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return k;
            }
        }
        throw new XmlException("unterminated declaration");
    }

    private static Token simple(int type, int start, int end) {
        Token t = new Token();
        t.type = type;
        t.start = start;
        t.end = end;
        return t;
    }

    static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int skipWs(String s, int k) {
        int n = s.length();
        while (k < n && isWs(s.charAt(k))) k++;
        return k;
    }

    private static int skipName(String s, int k) {
        int n = s.length();
        while (k < n) {
            char c = s.charAt(k);
            if (isWs(c) || c == '<' || c == '>' || c == '/' || c == '=' || c == '?' || c == '!'
                    || c == '"' || c == '\'' || c < 0x20) {
                break;
            }
            k++;
        }
        return k;
    }

    // ------------------------------------------------------------------ structure + namespaces

    private static void structure(String s, List<Token> toks) throws XmlException {
        int[] stack = new int[16];
        Scope[] scopes = new Scope[16];
        int sp = 0;
        Scope root = new Scope(null);
        for (int idx = 0; idx < toks.size(); idx++) {
            Token t = toks.get(idx);
            t.parent = sp > 0 ? stack[sp - 1] : -1;
            if (t.type == START) {
                Scope parentScope = sp > 0 ? scopes[sp - 1] : root;
                Scope sc = parentScope;
                for (Attr a : t.attrs) {
                    if (a.qname.equals("xmlns") || a.qname.startsWith("xmlns:")) {
                        if (sc == parentScope) sc = new Scope(parentScope);
                        String prefix = a.qname.length() > 6 ? a.qname.substring(6) : "";
                        sc.put(prefix, XmpEditor.unescape(s.substring(a.valueStart, a.valueEnd)));
                    }
                }
                int colon = t.qname.indexOf(':');
                t.uri = sc.lookup(colon >= 0 ? t.qname.substring(0, colon) : "");
                t.local = colon >= 0 ? t.qname.substring(colon + 1) : t.qname;
                for (Attr a : t.attrs) {
                    if (a.qname.equals("xmlns") || a.qname.startsWith("xmlns:")) continue;
                    int c = a.qname.indexOf(':');
                    if (c < 0) {
                        a.local = a.qname;          // unprefixed attributes are in no namespace
                    } else {
                        a.uri = sc.lookup(a.qname.substring(0, c));
                        a.local = a.qname.substring(c + 1);
                    }
                }
                if (t.selfClosing) {
                    t.match = idx;
                } else {
                    if (sp == stack.length) {
                        int[] ns = new int[sp * 2];
                        System.arraycopy(stack, 0, ns, 0, sp);
                        stack = ns;
                        Scope[] nsc = new Scope[sp * 2];
                        System.arraycopy(scopes, 0, nsc, 0, sp);
                        scopes = nsc;
                    }
                    stack[sp] = idx;
                    scopes[sp] = sc;
                    sp++;
                }
            } else if (t.type == END) {
                if (sp == 0) throw new XmlException("unexpected end tag");
                int open = stack[--sp];
                Token o = toks.get(open);
                if (!o.qname.equals(t.qname)) throw new XmlException("mismatched end tag");
                o.match = idx;
                t.match = open;
                t.parent = o.parent;
            }
        }
        if (sp != 0) throw new XmlException("unclosed element");
    }
}
