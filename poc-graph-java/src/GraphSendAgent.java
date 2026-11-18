import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import lotus.domino.AgentBase;
import lotus.domino.Session;

/**
 * PoC agent：用 Graph 寄兩封測試信（中文主旨、HTML 表格、中文檔名附件）。
 * 同一個 client 連寄兩封，第二封應該沿用第一封的 token，log 會顯示申請 token 1 次。
 */
public class GraphSendAgent extends AgentBase {

    public void NotesMain() {
        try {
            Session session = getSession();
            GraphConfig cfg = GraphConfig.load(GraphNotes.configPath(session));
            if (cfg.testTo == null) {
                log("設定檔沒有 testTo，不寄信");
                return;
            }
            GraphMailClient client = new GraphMailClient(cfg);
            List<String> to = Arrays.asList(cfg.testTo.split(","));
            String stamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date());

            long t0 = System.currentTimeMillis();
            client.sendMail(to, PocSamples.subject(stamp), PocSamples.html(stamp), PocSamples.attachments());
            long t1 = System.currentTimeMillis();
            client.sendMail(to, PocSamples.subject(stamp) + "（第二封）", PocSamples.html(stamp), null);
            long t2 = System.currentTimeMillis();

            log("已寄出 2 封給 " + cfg.testTo + "；第一封 " + (t1 - t0) + " ms（含取 token）、第二封 "
                    + (t2 - t1) + " ms；申請 token " + client.getTokenRequestCount() + " 次");
        } catch (Exception e) {
            log("執行失敗：" + e);
            e.printStackTrace();
        }
    }

    private static void log(String msg) {
        System.out.println("[GraphSendAgent] " + msg);
    }
}
