import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.mail.internet.MimeMessage;

/**
 * 本機（或 Domino 伺服器上用 Domino 的 JVM）直接測試 Graph，不需要 Notes。
 * 先用這支確認試用租用戶的權限、憑證信任、proxy 都正確，再部署 agent。
 */
public class GraphPocCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        GraphConfig cfg = GraphConfig.load(args[0]);
        GraphMailClient client = new GraphMailClient(cfg);
        String cmd = args[1];
        try {
            if (cmd.equals("token")) token(client);
            else if (cmd.equals("send")) send(client, cfg, args.length > 2 ? args[2] : cfg.testTo);
            else if (cmd.equals("list")) list(client, cfg);
            else if (cmd.equals("read")) read(client, cfg, args.length > 2 ? args[2] : "out", false);
            else if (cmd.equals("process")) read(client, cfg, args.length > 2 ? args[2] : "out", true);
            else usage();
        } catch (GraphMailClient.GraphException e) {
            System.out.println("失敗：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("用法：java -jar graph-poc.jar <設定檔> <指令> [參數]");
        System.out.println("  token              取得 token，顯示有效期限與 token 內的權限");
        System.out.println("  send [收件者]      寄一封測試信（中文主旨、HTML、中文檔名附件）");
        System.out.println("  list               列出收件匣");
        System.out.println("  read [輸出目錄]    讀收件匣每封信的 MIME、解析內文與附件，存成 .eml，不搬移");
        System.out.println("  process [輸出目錄] 同 read，之後把信搬到已處理資料夾（模擬 agent，但不建 Notes 文件）");
    }

    private static void token(GraphMailClient client) throws Exception {
        String token = client.getAccessToken();
        long minutes = (client.getTokenExpiresAt() - System.currentTimeMillis()) / 60000;
        System.out.println("取得 token 成功，長度 " + token.length() + "，約 " + minutes + " 分鐘後到期");
        String[] parts = token.split("\\.");
        if (parts.length < 2) return;
        Map<String, Object> claims = MiniJson.obj(MiniJson.parse(
                new String(Base64.getUrlDecoder().decode(parts[1]), "UTF-8")));
        System.out.println("應用程式：" + claims.get("app_displayname") + "（appid " + claims.get("appid") + "）");
        System.out.println("租用戶：" + claims.get("tid"));
        Object roles = claims.get("roles");
        System.out.println("token 內的權限（roles）：" + (roles == null ? "無" : roles));
        if (roles == null) {
            System.out.println("  用 Exchange RBAC for Applications 授權時 roles 本來就是空的，實際權限以 list／send 測試為準");
        }
    }

    private static void send(GraphMailClient client, GraphConfig cfg, String to) throws Exception {
        if (to == null) throw new IllegalArgumentException("沒有收件者：請在指令後面加收件者，或在設定檔填 testTo");
        String stamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date());
        List<String> list = Arrays.asList(to.split(","));
        client.sendMail(list, PocSamples.subject(stamp), PocSamples.html(stamp), PocSamples.attachments());
        System.out.println("已由 " + cfg.mailbox + " 寄出測試信給 " + to);
    }

    private static void list(GraphMailClient client, GraphConfig cfg) throws Exception {
        List<GraphMailClient.MessageInfo> list = client.listInboxMessages(cfg.maxMessages);
        System.out.println(cfg.mailbox + " 收件匣 " + list.size() + " 封（由舊到新，最多 " + cfg.maxMessages + "）");
        for (GraphMailClient.MessageInfo m : list) {
            System.out.println("  " + m.receivedDateTime + (m.isRead ? "  已讀" : "  未讀")
                    + (m.hasAttachments ? "  附件" : "      ") + "  " + m.from + "  " + m.subject);
        }
    }

    private static void read(GraphMailClient client, GraphConfig cfg, String outDir, boolean move) throws Exception {
        File dir = new File(outDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("無法建立輸出目錄 " + dir);
        String folderId = move ? client.getOrCreateFolderId(cfg.processedFolder) : null;
        List<GraphMailClient.MessageInfo> list = client.listInboxMessages(cfg.maxMessages);
        System.out.println("收件匣 " + list.size() + " 封");
        int n = 0;
        for (GraphMailClient.MessageInfo info : list) {
            n++;
            byte[] raw = client.getMime(info.id);
            File eml = new File(dir, String.format("%03d-%s.eml", n, safeName(info.subject)));
            OutputStream os = new FileOutputStream(eml);
            try {
                os.write(raw);
            } finally {
                os.close();
            }
            MimeMessage msg = MailText.parse(raw);
            String text = MailText.getText(msg);
            System.out.println("== " + n + ". " + msg.getSubject());
            System.out.println("   寄件者：" + info.from + "　收到：" + info.receivedDateTime + "　MIME " + raw.length + " bytes");
            System.out.println("   附件：" + MailText.attachmentNames(msg));
            System.out.println("   內文：" + (text.length() > 300 ? text.substring(0, 300) + "…" : text).replace("\n", "\n         "));
            System.out.println("   已存成 " + eml.getPath());
            if (move) {
                client.moveMessage(info.id, folderId);
                System.out.println("   已搬到「" + cfg.processedFolder + "」");
            }
        }
        System.out.println("申請 token " + client.getTokenRequestCount() + " 次");
    }

    private static String safeName(String s) {
        String name = s == null ? "no-subject" : s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return name.length() > 40 ? name.substring(0, 40) : name;
    }
}
