import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Properties;

/**
 * PoC 設定。本機 CLI 與 Domino agent 共用同一份 properties 檔（UTF-8 存檔）。
 * clientSecret 是密鑰：本機放在 poc-graph-java\local\（不要外流），伺服器上要限制只有 Domino 服務帳號能讀。
 */
public class GraphConfig {
    public String tenantId;
    public String clientId;
    public String clientSecret;
    /** 情資系統專用信箱，寄信與讀信都用它。 */
    public String mailbox;
    /** 處理完的信搬到這個資料夾（收件匣同層），不存在會自動建立。 */
    public String processedFolder = "Processed";
    /** true 只讀未讀信；false 讀收件匣全部（處理完就搬走，所以收件匣只剩未處理的）。 */
    public boolean unreadOnly = false;
    public int maxMessages = 20;
    /** 測試寄信的收件者，多個用逗號分隔。 */
    public String testTo;
    /** 讀信轉成 Notes 文件用的表單，PoC 用 mail 範本建的資料庫，所以預設 Memo。 */
    public String notesForm = "Memo";
    public String proxyHost;
    public int proxyPort = 8080;
    public int connectTimeoutMs = 15000;
    public int readTimeoutMs = 60000;
    /** 一般不用改；離線測試時指向本機模擬伺服器。 */
    public String loginBaseUrl = "https://login.microsoftonline.com";
    public String graphBaseUrl = "https://graph.microsoft.com/v1.0";

    public static GraphConfig load(String path) throws IOException {
        Properties p = new Properties();
        Reader r = new InputStreamReader(new FileInputStream(path), "UTF-8");
        try {
            p.load(r);
        } finally {
            r.close();
        }
        return from(p);
    }

    public static GraphConfig from(Properties p) {
        GraphConfig c = new GraphConfig();
        c.tenantId = required(p, "tenantId");
        c.clientId = required(p, "clientId");
        c.clientSecret = required(p, "clientSecret");
        c.mailbox = required(p, "mailbox");
        c.processedFolder = optional(p, "processedFolder", c.processedFolder);
        c.unreadOnly = Boolean.parseBoolean(optional(p, "unreadOnly", String.valueOf(c.unreadOnly)));
        c.maxMessages = Integer.parseInt(optional(p, "maxMessages", String.valueOf(c.maxMessages)));
        c.testTo = optional(p, "testTo", null);
        c.notesForm = optional(p, "notesForm", c.notesForm);
        c.proxyHost = optional(p, "proxyHost", null);
        c.proxyPort = Integer.parseInt(optional(p, "proxyPort", String.valueOf(c.proxyPort)));
        c.connectTimeoutMs = Integer.parseInt(optional(p, "connectTimeoutMs", String.valueOf(c.connectTimeoutMs)));
        c.readTimeoutMs = Integer.parseInt(optional(p, "readTimeoutMs", String.valueOf(c.readTimeoutMs)));
        c.loginBaseUrl = optional(p, "loginBaseUrl", c.loginBaseUrl);
        c.graphBaseUrl = optional(p, "graphBaseUrl", c.graphBaseUrl);
        return c;
    }

    private static String required(Properties p, String key) {
        String v = optional(p, key, null);
        if (v == null) throw new IllegalArgumentException("設定檔缺少 " + key);
        return v;
    }

    private static String optional(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? def : v.trim();
    }
}
