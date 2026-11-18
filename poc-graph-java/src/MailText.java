import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

/**
 * 把 Graph 取回的原始 MIME 交給 JavaMail 解析，取出純文字內文與附件檔名。
 * getText 沿用一般 JavaMail 讀信程式的規則：
 * multipart/alternative 先取 text/plain，沒有才把 HTML 轉成文字。
 * 只用 JavaMail 1.4 就有的 API，Domino 內建的 mail.jar 也能跑。
 */
public final class MailText {
    private MailText() {
    }

    public static MimeMessage parse(byte[] raw) throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
    }

    public static String getText(Part p) throws MessagingException, IOException {
        if (p.isMimeType("multipart/alternative")) {
            Multipart mp = (Multipart) p.getContent();
            Part fallback = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                if (child.isMimeType("text/plain")) return getText(child);
                if (fallback == null) fallback = child;
            }
            return fallback == null ? "" : getText(fallback);
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                if (isAttachment(child)) continue;
                String t = getText(child);
                if (t.length() > 0) {
                    if (b.length() > 0) b.append('\n');
                    b.append(t);
                }
            }
            return b.toString();
        }
        if (p.isMimeType("message/rfc822")) return getText((Part) p.getContent());
        if (isAttachment(p)) return "";
        if (p.isMimeType("text/plain")) return contentAsString(p);
        if (p.isMimeType("text/html")) return htmlToText(contentAsString(p));
        return "";
    }

    /** 列出附件檔名（已解碼中文檔名），包含內嵌圖片。 */
    public static List<String> attachmentNames(Part p) throws MessagingException, IOException {
        List<String> names = new ArrayList<String>();
        collectAttachmentNames(p, names);
        return names;
    }

    private static void collectAttachmentNames(Part p, List<String> names) throws MessagingException, IOException {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) collectAttachmentNames(mp.getBodyPart(i), names);
        } else if (isAttachment(p)) {
            names.add(fileName(p));
        }
    }

    public static boolean isAttachment(Part p) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || p.getFileName() != null;
    }

    /** Outlook 常用 RFC 2047（=?UTF-8?B?...?=）編碼檔名，JavaMail 1.4 預設不會解，這裡自己解。 */
    public static String fileName(Part p) throws MessagingException {
        String name = p.getFileName();
        if (name == null) return "(未命名附件)";
        try {
            return MimeUtility.decodeText(name);
        } catch (UnsupportedEncodingException e) {
            return name;
        }
    }

    private static String contentAsString(Part p) throws MessagingException, IOException {
        Object content = p.getContent();
        if (content instanceof String) return (String) content;
        if (content instanceof InputStream) {
            return new String(GraphMailClient.readAll((InputStream) content), "UTF-8");
        }
        return String.valueOf(content);
    }

    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** 有 jsoup（舊程式在用的 1.7.x）就用 jsoup，沒有就用簡單的規則轉。 */
    static String htmlToText(String html) {
        try {
            Class<?> jsoup = Class.forName("org.jsoup.Jsoup");
            Object doc = jsoup.getMethod("parse", String.class).invoke(null, html);
            return (String) doc.getClass().getMethod("text").invoke(doc);
        } catch (Throwable noJsoup) {
            String t = SCRIPT_STYLE.matcher(html).replaceAll("");
            t = LINE_BREAK.matcher(t).replaceAll("\n");
            t = TAG.matcher(t).replaceAll("");
            t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'");
            StringBuffer b = new StringBuffer();
            Matcher m = NUMERIC_ENTITY.matcher(t);
            while (m.find()) {
                int code = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10);
                m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(code))));
            }
            m.appendTail(b);
            return b.toString().replace("&amp;", "&").replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        }
    }
}
