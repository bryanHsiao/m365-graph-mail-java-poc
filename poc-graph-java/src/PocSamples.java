import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * 測試寄信用的假情資內容。只用文件保留的 IP（198.51.100.0/24）與 example 網域，
 * 不要換成真實情資。
 */
public final class PocSamples {
    private PocSamples() {
    }

    public static String subject(String stamp) {
        return "[情資系統 PoC] Graph 寄信測試（TLP:AMBER 假情資）" + stamp;
    }

    public static String html(String stamp) {
        return "<html><body style=\"font-family:Microsoft JhengHei,sans-serif\">"
                + "<p>這是情資系統 Graph 寄信 PoC 的測試信，寄出時間 " + stamp + "。</p>"
                + "<p><b>TLP:AMBER</b>　以下為測試用假情資，請勿採取行動。</p>"
                + "<table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\">"
                + "<tr><th>指標</th><th>類型</th><th>說明</th></tr>"
                + "<tr><td>198.51.100.23</td><td>IP</td><td>測試用中繼站</td></tr>"
                + "<tr><td>evil.example</td><td>網域</td><td>測試用釣魚網域</td></tr>"
                + "</table>"
                + "<p>詳細內容：中文、全形符號（，。「」）與 HTML 表格顯示正常即通過。</p>"
                + "</body></html>";
    }

    public static List<GraphMailClient.Attachment> attachments() throws UnsupportedEncodingException {
        // 前面加 BOM，Excel 開 UTF-8 CSV 中文才不會亂碼
        String csv = "﻿指標,類型,說明\r\n198.51.100.23,IP,測試用中繼站\r\nevil.example,網域,測試用釣魚網域\r\n";
        List<GraphMailClient.Attachment> list = new ArrayList<GraphMailClient.Attachment>();
        list.add(new GraphMailClient.Attachment("測試附件-假情資.csv", "text/csv", csv.getBytes("UTF-8")));
        return list;
    }
}
