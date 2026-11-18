import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 用 Microsoft Graph 寄信、讀信的最小用戶端（client credentials，應用程式身分）。
 * 只用 JDK 內建的 HttpURLConnection，Domino 11 的 Java 8 就能跑，不用另掛 HTTP／JSON jar。
 */
public class GraphMailClient {
    /** sendMail 單次請求上限約 4MB（附件 base64 後會變大約 1.37 倍），超過要改用 upload session。 */
    public static final int MAX_INLINE_ATTACHMENT_BYTES = 3 * 1024 * 1024;
    private static final int MAX_RETRY = 3;
    private static final long TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final GraphConfig cfg;
    private final Proxy proxy;
    private SSLSocketFactory sslFactory;
    private String accessToken;
    private long tokenExpiresAt;
    private int tokenRequestCount;

    public GraphMailClient(GraphConfig cfg) {
        this.cfg = cfg;
        this.proxy = cfg.proxyHost == null
                ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(cfg.proxyHost, cfg.proxyPort));
    }

    public static class Attachment {
        public final String name;
        public final String contentType;
        public final byte[] bytes;

        public Attachment(String name, String contentType, byte[] bytes) {
            this.name = name;
            this.contentType = contentType;
            this.bytes = bytes;
        }
    }

    public static class MessageInfo {
        public String id;
        public String subject;
        public String from;
        public String receivedDateTime;
        public String internetMessageId;
        public boolean hasAttachments;
        public boolean isRead;

        static MessageInfo from(Map<String, Object> m) {
            MessageInfo info = new MessageInfo();
            info.id = MiniJson.str(m, "id");
            info.subject = MiniJson.str(m, "subject");
            info.from = MiniJson.str(m, "from.emailAddress.address");
            info.receivedDateTime = MiniJson.str(m, "receivedDateTime");
            info.internetMessageId = MiniJson.str(m, "internetMessageId");
            info.hasAttachments = Boolean.TRUE.equals(m.get("hasAttachments"));
            info.isRead = Boolean.TRUE.equals(m.get("isRead"));
            return info;
        }
    }

    /** 取得 token；到期前 5 分鐘內才重新申請，同一個 client 物件內重複使用。 */
    public synchronized String getAccessToken() throws IOException, GraphException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS) {
            return accessToken;
        }
        String form = "client_id=" + enc(cfg.clientId)
                + "&client_secret=" + enc(cfg.clientSecret)
                + "&scope=" + enc("https://graph.microsoft.com/.default")
                + "&grant_type=client_credentials";
        HttpResult r = send("POST", cfg.loginBaseUrl + "/" + enc(cfg.tenantId) + "/oauth2/v2.0/token",
                "application/x-www-form-urlencoded", form.getBytes("UTF-8"), false);
        Map<String, Object> m = MiniJson.obj(MiniJson.parse(r.text()));
        accessToken = (String) m.get("access_token");
        tokenExpiresAt = System.currentTimeMillis() + ((Number) m.get("expires_in")).longValue() * 1000L;
        tokenRequestCount++;
        return accessToken;
    }

    public long getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    /** 這個 client 物件實際向 Entra ID 申請 token 的次數，用來確認快取有沒有生效。 */
    public int getTokenRequestCount() {
        return tokenRequestCount;
    }

    /** 以設定的信箱寄出 HTML 信，寄件備份會存到該信箱的寄件備份。 */
    public void sendMail(List<String> to, String subject, String html, List<Attachment> attachments)
            throws IOException, GraphException {
        List<Object> recipients = new ArrayList<Object>();
        for (String addr : to) {
            if (addr.trim().length() > 0) {
                recipients.add(MiniJson.map("emailAddress", MiniJson.map("address", addr.trim())));
            }
        }
        if (recipients.isEmpty()) throw new IllegalArgumentException("沒有收件者");

        Map<String, Object> message = MiniJson.map(
                "subject", subject,
                "body", MiniJson.map("contentType", "HTML", "content", html),
                "toRecipients", recipients);

        if (attachments != null && !attachments.isEmpty()) {
            long total = 0;
            List<Object> list = new ArrayList<Object>();
            for (Attachment a : attachments) {
                total += a.bytes.length;
                list.add(MiniJson.map(
                        "@odata.type", "#microsoft.graph.fileAttachment",
                        "name", a.name,
                        "contentType", a.contentType,
                        "contentBytes", Base64.getEncoder().encodeToString(a.bytes)));
            }
            if (total > MAX_INLINE_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("附件合計 " + total + " bytes，超過 PoC 直接夾帶的上限 "
                        + MAX_INLINE_ATTACHMENT_BYTES + "，大附件要改用 upload session");
            }
            message.put("attachments", list);
        }

        String json = MiniJson.write(MiniJson.map("message", message, "saveToSentItems", Boolean.TRUE));
        call("POST", "/users/" + enc(cfg.mailbox) + "/sendMail", "application/json; charset=utf-8",
                json.getBytes("UTF-8"));
    }

    /** 由舊到新列出收件匣的信，最多 max 封（自動跟著 @odata.nextLink 翻頁）。 */
    public List<MessageInfo> listInboxMessages(int max) throws IOException, GraphException {
        String query = "$select=id,subject,from,receivedDateTime,internetMessageId,hasAttachments,isRead"
                + "&$top=" + Math.min(max, 50)
                + "&$orderby=" + enc("receivedDateTime asc");
        if (cfg.unreadOnly) {
            // $orderby 的欄位必須先出現在 $filter 裡，否則 Graph 回 InefficientFilter
            query += "&$filter=" + enc("receivedDateTime ge 1900-01-01T00:00:00Z and isRead eq false");
        }
        String next = "/users/" + enc(cfg.mailbox) + "/mailFolders/inbox/messages?" + query;
        List<MessageInfo> out = new ArrayList<MessageInfo>();
        while (next != null && out.size() < max) {
            Map<String, Object> page = MiniJson.obj(MiniJson.parse(call("GET", next, null, null).text()));
            for (Object v : MiniJson.list(page.get("value"))) {
                if (out.size() >= max) break;
                out.add(MessageInfo.from(MiniJson.obj(v)));
            }
            next = (String) page.get("@odata.nextLink");
        }
        return out;
    }

    /** 取得整封信的原始 MIME（RFC 822），交給 JavaMail 解析，沿用既有的內文解析邏輯。 */
    public byte[] getMime(String messageId) throws IOException, GraphException {
        return call("GET", "/users/" + enc(cfg.mailbox) + "/messages/" + enc(messageId) + "/$value", null, null).body;
    }

    /** 找收件匣同層的資料夾，沒有就建立，回傳資料夾 id。 */
    public String getOrCreateFolderId(String displayName) throws IOException, GraphException {
        String filter = "displayName eq '" + displayName.replace("'", "''") + "'";
        String path = "/users/" + enc(cfg.mailbox) + "/mailFolders";
        Map<String, Object> found = MiniJson.obj(MiniJson.parse(
                call("GET", path + "?$filter=" + enc(filter) + "&$select=id,displayName", null, null).text()));
        List<Object> value = MiniJson.list(found.get("value"));
        if (!value.isEmpty()) return MiniJson.str(MiniJson.obj(value.get(0)), "id");

        String json = MiniJson.write(MiniJson.map("displayName", displayName));
        Map<String, Object> created = MiniJson.obj(MiniJson.parse(
                call("POST", path, "application/json; charset=utf-8", json.getBytes("UTF-8")).text()));
        return MiniJson.str(created, "id");
    }

    /** 搬移信件，回傳搬移後的新 id（Graph 搬移後 id 會變）。 */
    public String moveMessage(String messageId, String folderId) throws IOException, GraphException {
        String json = MiniJson.write(MiniJson.map("destinationId", folderId));
        Map<String, Object> moved = MiniJson.obj(MiniJson.parse(call("POST",
                "/users/" + enc(cfg.mailbox) + "/messages/" + enc(messageId) + "/move",
                "application/json; charset=utf-8", json.getBytes("UTF-8")).text()));
        return MiniJson.str(moved, "id");
    }

    private HttpResult call(String method, String pathOrUrl, String contentType, byte[] body)
            throws IOException, GraphException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : cfg.graphBaseUrl + pathOrUrl;
        try {
            return send(method, url, contentType, body, true);
        } catch (GraphException e) {
            if (e.status != 401) throw e;
            // token 被撤銷或提早失效：丟掉快取重拿一次
            accessToken = null;
            return send(method, url, contentType, body, true);
        }
    }

    private HttpResult send(String method, String url, String contentType, byte[] body, boolean auth)
            throws IOException, GraphException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(url);
            c.setRequestMethod(method);
            // 一定要明確設定：HttpURLConnection 預設的 Accept 含有單獨的 "*"，Graph 的 GET 會回 406 ErrorNotAcceptable
            c.setRequestProperty("Accept", url.contains("/$value") ? "*/*" : "application/json");
            if (auth) c.setRequestProperty("Authorization", "Bearer " + getAccessToken());
            if (body != null) {
                // 不用 setFixedLengthStreamingMode：串流模式下遇到 401，JDK 會丟掉回應內容，
                // 讀不到 Entra ID 的 AADSTS 錯誤說明
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body);
                } finally {
                    os.close();
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            String retryAfter = c.getHeaderField("Retry-After");
            c.disconnect();

            if ((status == 429 || status == 503 || status == 504) && attempt < MAX_RETRY) {
                sleepBeforeRetry(retryAfter, attempt);
                continue;
            }
            if (status >= 400) throw GraphException.from(status, method + " " + url, data);
            return new HttpResult(status, data);
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(tls12());
        c.setConnectTimeout(cfg.connectTimeoutMs);
        c.setReadTimeout(cfg.readTimeoutMs);
        c.setUseCaches(false);
        return c;
    }

    /** IBM J9 的 "TLS" 預設不一定啟用 TLS 1.2，明確指定；憑證信任沿用 JVM 的 cacerts，不做全部信任。 */
    private synchronized SSLSocketFactory tls12() throws IOException {
        if (sslFactory == null) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                ctx.init(null, null, null);
                sslFactory = ctx.getSocketFactory();
            } catch (GeneralSecurityException e) {
                throw new IOException("無法建立 TLS 1.2 連線：" + e, e);
            }
        }
        return sslFactory;
    }

    private static void sleepBeforeRetry(String retryAfter, int attempt) throws IOException {
        long seconds;
        try {
            seconds = Long.parseLong(retryAfter.trim());
        } catch (Exception noHeader) {
            seconds = 1L << attempt;
        }
        try {
            Thread.sleep(Math.min(seconds, 30) * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待重試時被中斷", e);
        }
    }

    /** URL 編碼，空白一律編成 %20（OData 查詢不吃 +）。 */
    static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static class HttpResult {
        final int status;
        final byte[] body;

        HttpResult(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        String text() throws UnsupportedEncodingException {
            return new String(body, "UTF-8");
        }
    }

    /** Graph 或 Entra ID 回傳的錯誤，code 例如 ErrorAccessDenied、invalid_client。 */
    public static class GraphException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;

        GraphException(int status, String code, String message) {
            super("HTTP " + status + (code == null ? "" : " " + code) + "：" + message);
            this.status = status;
            this.code = code;
        }

        static GraphException from(int status, String request, byte[] data) {
            String text;
            try {
                text = new String(data, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                text = "";
            }
            String code = null;
            String message = text.length() > 500 ? text.substring(0, 500) : text;
            try {
                Map<String, Object> m = MiniJson.obj(MiniJson.parse(text));
                Object error = m.get("error");
                if (error instanceof Map) {
                    // Graph：{"error":{"code":"...","message":"..."}}
                    code = MiniJson.str(MiniJson.obj(error), "code");
                    message = MiniJson.str(MiniJson.obj(error), "message");
                } else if (error != null) {
                    // Entra ID token 端點：{"error":"invalid_client","error_description":"AADSTS..."}
                    code = error.toString();
                    message = MiniJson.str(m, "error_description");
                }
            } catch (RuntimeException notJson) {
                // 保留原始內容
            }
            return new GraphException(status, code, message + "（" + request + "）");
        }
    }
}
