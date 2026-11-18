import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Vector;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.Item;
import lotus.domino.MIMEEntity;
import lotus.domino.NotesException;
import lotus.domino.Stream;

/**
 * Domino 端的共用程式：讀設定檔路徑、把 Graph 取回的 MIME 轉成 Notes 文件。
 * Body 以 MIME 原樣重建（HTML、內嵌圖片、附件都保留），Notes 用戶端開啟就像一般收到的信；
 * 另外存一份純文字到 BodyText，給白名單、關鍵字比對這類既有邏輯使用。
 */
public final class GraphNotes {
    /** notes.ini 變數名稱，值是設定檔的完整路徑。 */
    public static final String INI_CONFIG_PATH = "GraphPocConfig";
    public static final String DEFAULT_CONFIG_PATH = "C:\\graph-poc\\graph-poc.properties";
    /** Notes 文字欄位上限約 64KB（LMBCS），中文一字最多 3 bytes，保守截在 15000 字。 */
    private static final int MAX_TEXT_CHARS = 15000;
    private static final String[] TOP_HEADERS = {"Subject", "From", "To", "Cc", "Date", "Message-ID", "Reply-To"};

    private GraphNotes() {
    }

    public static String configPath(lotus.domino.Session s) throws NotesException {
        String p = s.getEnvironmentString(INI_CONFIG_PATH, true);
        return p == null || p.trim().length() == 0 ? DEFAULT_CONFIG_PATH : p.trim();
    }

    public static Document createMailDocument(lotus.domino.Session s, Database db, String form,
            MimeMessage msg, GraphMailClient.MessageInfo info) throws NotesException, MessagingException, IOException {
        boolean oldConvert = s.isConvertMime();
        s.setConvertMime(false);
        try {
            Document doc = db.createDocument();
            doc.replaceItemValue("Form", form);
            doc.replaceItemValue("Subject", msg.getSubject() == null ? "" : msg.getSubject());
            doc.replaceItemValue("From", addresses(msg.getFrom()).isEmpty() ? "" : addresses(msg.getFrom()).get(0));
            doc.replaceItemValue("SendTo", addresses(msg.getRecipients(Message.RecipientType.TO)));
            doc.replaceItemValue("CopyTo", addresses(msg.getRecipients(Message.RecipientType.CC)));
            if (msg.getSentDate() != null) doc.replaceItemValue("PostedDate", s.createDateTime(msg.getSentDate()));
            Date received = parseGraphDate(info.receivedDateTime);
            if (received != null) doc.replaceItemValue("DeliveredDate", s.createDateTime(received));
            doc.replaceItemValue("$MessageID", info.internetMessageId == null ? "" : info.internetMessageId);
            doc.replaceItemValue("GraphMessageId", info.id);

            String text = MailText.getText(msg);
            Item bodyText = doc.replaceItemValue("BodyText",
                    text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
            bodyText.setSummary(false);
            doc.replaceItemValue("AttachmentNames", new Vector<String>(MailText.attachmentNames(msg)));

            MIMEEntity root = doc.createMIMEEntity("Body");
            for (String name : TOP_HEADERS) {
                String v = msg.getHeader(name, null);
                if (v != null) root.createHeader(name).setHeaderVal(unfold(v));
            }
            copyPart(s, msg, root);
            doc.closeMIMEEntities(true, "Body");
            doc.save(true, false);
            return doc;
        } finally {
            s.setConvertMime(oldConvert);
        }
    }

    /** 依 JavaMail 解析出的結構逐層建立 Notes MIMEEntity。 */
    private static void copyPart(lotus.domino.Session s, Part p, MIMEEntity e)
            throws NotesException, MessagingException, IOException {
        String contentType = unfold(p.getContentType());
        if (p.isMimeType("multipart/*")) {
            // 只給基本型別，boundary 由 Notes 自己產生
            e.createHeader("Content-Type").setHeaderVal(new ContentType(contentType).getBaseType().toLowerCase());
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) copyPart(s, mp.getBodyPart(i), e.createChildEntity());
            return;
        }
        for (String name : new String[] {"Content-Disposition", "Content-ID"}) {
            String[] v = p.getHeader(name);
            if (v != null && v.length > 0) e.createHeader(name).setHeaderValAndParams(unfold(v[0]));
        }
        byte[] data = GraphMailClient.readAll(p.getInputStream());
        if (data.length == 0) return;
        Stream st = s.createStream();
        try {
            st.write(data);
            st.setPosition(0);
            e.setContentFromBytes(st, contentType, MIMEEntity.ENC_IDENTITY_BINARY);
            e.encodeContent(p.isMimeType("text/*") ? MIMEEntity.ENC_QUOTED_PRINTABLE : MIMEEntity.ENC_BASE64);
        } finally {
            st.close();
            st.recycle();
        }
    }

    private static Vector<String> addresses(Address[] list) {
        Vector<String> v = new Vector<String>();
        if (list == null) return v;
        for (Address a : list) {
            v.add(a instanceof InternetAddress ? ((InternetAddress) a).toUnicodeString() : a.toString());
        }
        return v;
    }

    /** Graph 的時間格式是 UTC，例如 2026-11-02T03:37:55Z。 */
    static Date parseGraphDate(String s) {
        if (s == null) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(s.length() > 19 ? s.substring(0, 19) : s);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String unfold(String header) {
        return header.replaceAll("\\r?\\n[ \\t]+", " ").trim();
    }
}
