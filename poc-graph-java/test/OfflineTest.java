import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.util.ByteArrayDataSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 離線測試：用本機 HttpServer 模擬 Entra ID token 端點與 Graph，
 * 不需要 M365 租用戶，也不需要 Notes。GraphNotes（MIME 轉 Notes 文件）要到 Domino 上測。
 */
public class OfflineTest {
    private static final String MAILBOX_PATH = "/v1.0/users/poc%40contoso.test";
    private static final String MSG1_ID = "AAMk-1=";
    private static final String MSG2_ID = "AAMk-2=";
    private static final String MSG3_ID = "AAMk-3=";

    private static final AtomicInteger tokenCalls = new AtomicInteger();
    private static final AtomicInteger sendCalls = new AtomicInteger();
    private static final AtomicInteger mimeCalls = new AtomicInteger();
    private static final List<String> sentBodies = new ArrayList<String>();
    private static String folderFilter;
    private static String listQuery;
    private static String moveRawPath;
    private static int port;
    private static int passed;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", OfflineTest::handle);
        server.start();
        try {
            run();
        } finally {
            server.stop(0);
        }
        System.out.println("全部通過（" + passed + " 項）");
    }

    private static void run() throws Exception {
        GraphMailClient client = new GraphMailClient(config("secret-ok"));

        // 寄信：第一次回 429 要自動重試；連寄兩封只申請一次 token
        client.sendMail(listOf("user@example.test"), PocSamples.subject("2026/11/02 10:00:00"),
                PocSamples.html("2026/11/02 10:00:00"), PocSamples.attachments());
        client.sendMail(listOf("user@example.test"), "第二封", "<p>第二封</p>", null);
        check(sendCalls.get() == 3, "sendMail 遇到 429 會重試（共打 3 次：429、202、202）");
        check(client.getTokenRequestCount() == 1 && tokenCalls.get() == 1, "連寄兩封只申請一次 token（快取生效）");

        Map<String, Object> sent = MiniJson.obj(MiniJson.parse(sentBodies.get(0)));
        Map<String, Object> message = MiniJson.obj(sent.get("message"));
        check(MiniJson.str(message, "subject").equals(PocSamples.subject("2026/11/02 10:00:00")), "中文主旨原樣送出");
        check("HTML".equals(MiniJson.str(message, "body.contentType")), "內文格式為 HTML");
        Map<String, Object> att = MiniJson.obj(MiniJson.list(message.get("attachments")).get(0));
        check("測試附件-假情資.csv".equals(att.get("name")), "中文附件檔名原樣送出");
        String csv = new String(Base64.getDecoder().decode((String) att.get("contentBytes")), "UTF-8");
        check(csv.contains("198.51.100.23"), "附件內容 base64 正確");
        check(Boolean.TRUE.equals(sent.get("saveToSentItems")), "寄件備份");

        // 已處理資料夾：找不到就建立
        String folderId = client.getOrCreateFolderId("情資已處理");
        check("folder-new".equals(folderId), "找不到資料夾時自動建立並回傳 id");
        check(folderFilter.contains("displayName eq '情資已處理'"), "資料夾查詢條件含中文名稱");

        // 列信：跟著 nextLink 翻頁，由舊到新
        List<GraphMailClient.MessageInfo> list = client.listInboxMessages(20);
        check(list.size() == 3, "跟著 @odata.nextLink 翻頁取得 3 封");
        check(listQuery.contains("$orderby=receivedDateTime asc"), "依收信時間由舊到新排序");
        check("soc@cti-share.example".equals(list.get(0).from) && list.get(0).hasAttachments, "解析寄件者與附件旗標");

        // 取 MIME：第一次回 401，要丟掉 token 重拿一次
        byte[] raw1 = client.getMime(MSG1_ID);
        check(mimeCalls.get() == 2 && client.getTokenRequestCount() == 2, "遇到 401 會重新申請 token 後重試");

        MimeMessage msg1 = MailText.parse(raw1);
        check(msg1.getSubject().contains("資安威脅情資"), "RFC 2047 編碼的中文主旨解碼正確");
        String text1 = MailText.getText(msg1);
        check(text1.contains("TLP:AMBER") && text1.contains("198.51.100.23"), "multipart/alternative 取出純文字內文");
        check(!text1.contains("<p>"), "有 text/plain 時不重複帶入 HTML");
        check(LegacyMailLike.getSubStr(text1, "詳細內容：", "若有任何問題歡迎再次來信詢問").equals("測試用中繼站 198.51.100.23"),
                "既有 getSubStr 規則可直接套用在解析結果上");
        check(MailText.attachmentNames(msg1).equals(listOf("情資IOC清單.csv")), "中文附件檔名解碼正確");

        MimeMessage msg2 = MailText.parse(client.getMime(MSG2_ID));
        String text2 = MailText.getText(msg2);
        check(text2.contains("只有 HTML 的 Big5 信") && text2.contains("<IOC>") && text2.contains("中"),
                "只有 HTML 的 Big5 信轉成文字（含實體字元）");
        check(!text2.contains("alert"), "HTML 轉文字時去掉 script");

        String movedId = client.moveMessage(MSG1_ID, folderId);
        check(("moved-" + MSG1_ID).equals(movedId), "搬移後回傳新 id");
        check(moveRawPath.contains("AAMk-1%3D"), "信件 id 有做 URL 編碼");

        // Entra ID 錯誤格式
        try {
            new GraphMailClient(config("secret-wrong")).getAccessToken();
            check(false, "密碼錯誤應該丟出例外");
        } catch (GraphMailClient.GraphException e) {
            System.out.println("  錯誤訊息：" + e.getMessage());
            check(e.status == 401 && "invalid_client".equals(e.code) && e.getMessage().contains("AADSTS7000215"),
                    "解析 Entra ID 的錯誤代碼與說明");
        }

        // JSON 跳脫
        Object parsed = MiniJson.parse("{\"a\":\"\\u4e2d\\\"\\n\",\"b\":[1,2.5,true,null],\"c\":{}}");
        check(MiniJson.str(MiniJson.obj(parsed), "a").equals("中\"\n"), "JSON \\u 與跳脫字元解析");
        check(MiniJson.write(parsed).equals("{\"a\":\"中\\\"\\n\",\"b\":[1,2.5,true,null],\"c\":{}}"), "JSON 輸出");
    }

    /** 舊程式常見的 getSubStr 寫法，確認解析結果可以直接沿用。 */
    static class LegacyMailLike {
        static String getSubStr(String str, String startStr, String endStr) {
            if (str.indexOf(startStr) != -1 && str.lastIndexOf(endStr) != -1
                    && str.lastIndexOf(endStr) > str.indexOf(startStr)) {
                return str.substring(str.indexOf(startStr) + startStr.length(), str.lastIndexOf(endStr)).trim();
            }
            return "";
        }
    }

    private static GraphConfig config(String secret) {
        Properties p = new Properties();
        p.setProperty("tenantId", "tenant-1");
        p.setProperty("clientId", "client-1");
        p.setProperty("clientSecret", secret);
        p.setProperty("mailbox", "poc@contoso.test");
        p.setProperty("loginBaseUrl", "http://127.0.0.1:" + port + "/login");
        p.setProperty("graphBaseUrl", "http://127.0.0.1:" + port + "/v1.0");
        return GraphConfig.from(p);
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Exception e) {
            e.printStackTrace();
            reply(ex, 500, "{\"error\":{\"code\":\"MockError\",\"message\":\"" + e + "\"}}");
        }
    }

    private static void route(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        String body = new String(GraphMailClient.readAll(ex.getRequestBody()), "UTF-8");
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String accept = ex.getRequestHeaders().getFirst("Accept");

        // 比照真的 Graph：Accept 不合法（例如 HttpURLConnection 預設值裡單獨的 "*"）回 406
        if (!rawPath.startsWith("/login/") && !"application/json".equals(accept) && !"*/*".equals(accept)) {
            reply(ex, 406, "{\"error\":{\"code\":\"ErrorNotAcceptable\",\"message\":\"bad Accept: " + accept + "\"}}");
            return;
        }

        if (rawPath.equals("/login/tenant-1/oauth2/v2.0/token")) {
            if (!body.contains("grant_type=client_credentials") || !body.contains("scope=https%3A%2F%2Fgraph.microsoft.com%2F.default")) {
                reply(ex, 400, "{\"error\":\"invalid_request\",\"error_description\":\"bad form\"}");
            } else if (!body.contains("client_secret=secret-ok")) {
                reply(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\"}");
            } else {
                int n = tokenCalls.incrementAndGet();
                reply(ex, 200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"tok-" + n + "\"}");
            }
            return;
        }
        if (auth == null || !auth.startsWith("Bearer tok-")) {
            reply(ex, 401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"no token\"}}");
            return;
        }
        if (!rawPath.startsWith(MAILBOX_PATH)) {
            reply(ex, 404, "{\"error\":{\"code\":\"ErrorInvalidUser\",\"message\":\"" + rawPath + "\"}}");
            return;
        }
        String path = rawPath.substring(MAILBOX_PATH.length());

        if (method.equals("POST") && path.equals("/sendMail")) {
            if (sendCalls.incrementAndGet() == 1) {
                ex.getResponseHeaders().set("Retry-After", "1");
                reply(ex, 429, "{\"error\":{\"code\":\"ApplicationThrottled\",\"message\":\"slow down\"}}");
            } else {
                sentBodies.add(body);
                reply(ex, 202, "");
            }
        } else if (method.equals("GET") && path.equals("/mailFolders")) {
            folderFilter = URLDecoder.decode(rawQuery, "UTF-8");
            reply(ex, 200, "{\"value\":[]}");
        } else if (method.equals("POST") && path.equals("/mailFolders")) {
            check("情資已處理".equals(MiniJson.str(MiniJson.obj(MiniJson.parse(body)), "displayName")), "建立資料夾送出中文名稱");
            reply(ex, 201, "{\"id\":\"folder-new\",\"displayName\":\"情資已處理\"}");
        } else if (method.equals("GET") && path.equals("/mailFolders/inbox/messages")) {
            if (rawQuery.contains("$skiptoken=p2")) {
                reply(ex, 200, "{\"value\":[" + info(MSG3_ID, "第三封", false) + "]}");
            } else {
                listQuery = URLDecoder.decode(rawQuery, "UTF-8");
                String next = "http://127.0.0.1:" + port + MAILBOX_PATH + "/mailFolders/inbox/messages?$skiptoken=p2";
                reply(ex, 200, "{\"value\":[" + info(MSG1_ID, "外部情資", true) + "," + info(MSG2_ID, "HTML 信", false)
                        + "],\"@odata.nextLink\":\"" + next + "\"}");
            }
        } else if (method.equals("GET") && path.equals("/messages/AAMk-1%3D/$value")) {
            if (mimeCalls.incrementAndGet() == 1) {
                reply(ex, 401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"Access token has expired.\"}}");
            } else {
                replyBytes(ex, 200, intelMime());
            }
        } else if (method.equals("GET") && path.equals("/messages/AAMk-2%3D/$value")) {
            replyBytes(ex, 200, big5HtmlMime());
        } else if (method.equals("POST") && path.endsWith("/move")) {
            moveRawPath = rawPath;
            String id = URLDecoder.decode(path.substring("/messages/".length(), path.length() - "/move".length()), "UTF-8");
            check("folder-new".equals(MiniJson.str(MiniJson.obj(MiniJson.parse(body)), "destinationId")), "搬移帶入資料夾 id");
            reply(ex, 201, "{\"id\":\"moved-" + id + "\"}");
        } else {
            reply(ex, 404, "{\"error\":{\"code\":\"NotFound\",\"message\":\"" + method + " " + path + "\"}}");
        }
    }

    private static String info(String id, String subject, boolean hasAttachments) {
        return "{\"id\":\"" + id + "\",\"subject\":\"" + subject + "\",\"receivedDateTime\":\"2026-11-02T01:00:00Z\","
                + "\"internetMessageId\":\"<" + id + "@example>\",\"hasAttachments\":" + hasAttachments + ",\"isRead\":false,"
                + "\"from\":{\"emailAddress\":{\"name\":\"情資分享平台\",\"address\":\"soc@cti-share.example\"}}}";
    }

    /** 模擬外部情資通報：multipart/mixed（alternative 純文字＋HTML、中文檔名 CSV 附件）。 */
    private static byte[] intelMime() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("soc@cti-share.example", "情資分享平台 測試", "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, "poc@contoso.test");
        m.setSubject("資安情資分享平台〔資安威脅情資〕發布通知", "UTF-8");
        m.setSentDate(new Date());

        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("TLP:AMBER\n詳細內容：測試用中繼站 198.51.100.23\n若有任何問題歡迎再次來信詢問", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>TLP:AMBER</p><p>詳細內容：測試用中繼站 198.51.100.23</p>", "text/html; charset=UTF-8");
        MimeMultipart alt = new MimeMultipart("alternative");
        alt.addBodyPart(plain);
        alt.addBodyPart(html);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);

        MimeBodyPart att = new MimeBodyPart();
        att.setDataHandler(new DataHandler(new ByteArrayDataSource("IOC,類型\r\n198.51.100.23,IP\r\n".getBytes("UTF-8"), "text/csv")));
        att.setFileName(MimeUtility.encodeText("情資IOC清單.csv", "UTF-8", "B"));
        att.setDisposition(Part.ATTACHMENT);

        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(att);
        m.setContent(mixed);
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toByteArray();
    }

    /** 只有 HTML、Big5 編碼、含 script 與實體字元的信。 */
    private static byte[] big5HtmlMime() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("noreply@vendor.example"));
        m.setSubject("Big5 HTML", "Big5");
        m.setContent("<html><script>alert(1)</script><body><p>只有 HTML 的 Big5 信</p>"
                + "<p>&lt;IOC&gt;&nbsp;&#20013;</p></body></html>", "text/html; charset=Big5");
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toByteArray();
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        if (json.length() > 0) ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        replyBytes(ex, status, json.getBytes("UTF-8"));
    }

    private static void replyBytes(HttpExchange ex, int status, byte[] data) throws IOException {
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        OutputStream os = ex.getResponseBody();
        if (data.length > 0) os.write(data);
        os.close();
    }

    private static List<String> listOf(String... items) {
        List<String> l = new ArrayList<String>();
        for (String s : items) l.add(s);
        return l;
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError("未通過：" + name);
        passed++;
        System.out.println("通過：" + name);
    }
}
