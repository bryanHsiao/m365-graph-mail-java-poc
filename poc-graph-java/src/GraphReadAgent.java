import java.util.List;

import javax.mail.internet.MimeMessage;

import lotus.domino.AgentBase;
import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.Session;
import lotus.domino.View;

/**
 * PoC agent：讀專用信箱收件匣，每封信轉成一份 Notes 文件，再把信搬到已處理資料夾。
 * 正式版要接的地方：建好文件後套用既有的白名單、關鍵字判斷與開單。
 */
public class GraphReadAgent extends AgentBase {
    /** 選用：第一欄依 $MessageID 排序的隱藏檢視，有的話用來避免同一封信轉兩次。 */
    private static final String DEDUP_VIEW = "(GraphMailByMsgId)";

    public void NotesMain() {
        long start = System.currentTimeMillis();
        try {
            Session session = getSession();
            Database db = session.getAgentContext().getCurrentDatabase();
            GraphConfig cfg = GraphConfig.load(GraphNotes.configPath(session));
            GraphMailClient client = new GraphMailClient(cfg);

            String folderId = client.getOrCreateFolderId(cfg.processedFolder);
            List<GraphMailClient.MessageInfo> list = client.listInboxMessages(cfg.maxMessages);
            log("信箱 " + cfg.mailbox + " 收件匣取得 " + list.size() + " 封");

            View dedup = db.getView(DEDUP_VIEW);
            int created = 0, skipped = 0, failed = 0;
            for (GraphMailClient.MessageInfo info : list) {
                try {
                    Document existing = dedup == null || info.internetMessageId == null
                            ? null : dedup.getDocumentByKey(info.internetMessageId, true);
                    if (existing != null) {
                        existing.recycle();
                        skipped++;
                        log("已轉過，只搬移：" + info.subject);
                    } else {
                        MimeMessage msg = MailText.parse(client.getMime(info.id));
                        Document doc = GraphNotes.createMailDocument(session, db, cfg.notesForm, msg, info);
                        log("已建立文件 " + doc.getUniversalID() + "：" + info.subject);
                        doc.recycle();
                        created++;
                    }
                    client.moveMessage(info.id, folderId);
                } catch (Exception e) {
                    failed++;
                    log("處理失敗（信件留在收件匣，下次重試）：" + info.subject + "：" + e);
                    e.printStackTrace();
                }
            }
            log("完成：建立 " + created + "、略過 " + skipped + "、失敗 " + failed
                    + "；申請 token " + client.getTokenRequestCount() + " 次；耗時 "
                    + (System.currentTimeMillis() - start) + " ms");
        } catch (Exception e) {
            log("執行失敗：" + e);
            e.printStackTrace();
        }
    }

    private static void log(String msg) {
        System.out.println("[GraphReadAgent] " + msg);
    }
}
