# Graph 郵件 PoC 程式（Java 8）

教學頁「[用 Java 透過 Graph 寄信讀信](https://bryanhsiao.github.io/m365-graph-mail-java-poc/)」用到的程式。以應用程式身分（client credentials）透過 Microsoft Graph 寄信、列信、取原始 MIME 交給 JavaMail 解析、搬到「已處理」資料夾；權限用 Exchange 的 RBAC for Applications 限定在一個信箱。

- 只用 JDK 8 內建的 `HttpURLConnection` 與 JavaMail，不另掛 HTTP／JSON 函式庫
- 可以在本機用命令列執行，也可以放進 HCL Domino 11（Java 8）的 Java agent
- 測試只用自編的假資料（IP 用文件保留的 198.51.100.0/24、網域用 example）

## 檔案

| 檔案 | 用途 | 需要 Notes |
|---|---|---|
| `src/GraphConfig.java` | 讀設定檔（UTF-8 properties） | 不用 |
| `src/MiniJson.java` | 百來行的 JSON 解析與輸出 | 不用 |
| `src/GraphMailClient.java` | token 快取、寄信、列信（翻頁）、取 MIME、找／建資料夾、搬移；429／503 依 Retry-After 重試，401 重拿 token | 不用 |
| `src/MailText.java` | MIME 轉純文字、附件檔名解碼 | 不用 |
| `src/PocSamples.java` | 測試信內容（假資料） | 不用 |
| `src/GraphPocCli.java` | 命令列工具：`token`、`send`、`list`、`read`、`process` | 不用 |
| `src/GraphNotes.java` | 把 MIME 轉成 Notes 文件（未在 Domino 上實測） | 要 |
| `src/GraphSendAgent.java`、`src/GraphReadAgent.java` | Domino Java agent 範例 | 要 |
| `test/OfflineTest.java` | 離線測試：本機模擬 Entra ID 與 Graph，不需要租用戶 | 不用 |
| `build.ps1` | 編譯、跑離線測試、打包 `build\graph-poc.jar` | |
| `run-test.ps1` | 依序跑 token、寄信、列信、讀信解析 | |
| `setup/setup-m365-poc.ps1` | 在 Microsoft 365 試用租用戶建立應用程式註冊、共用信箱、Exchange 授權，產生設定檔 | |
| `graph-poc.properties.example` | 設定檔範例 | |

## 需要的東西

- JDK 8 以上（`javac`、`jar`、`java` 在 PATH 裡；要放進 Domino 11 就用 JDK 8 編譯）
- JavaMail：`javax.mail.jar` 1.4 以上，這份用 1.6.2，放到 `lib\javax.mail.jar`

```powershell
New-Item -ItemType Directory -Force lib | Out-Null; Invoke-WebRequest https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar -OutFile lib\javax.mail.jar
```

- 有裝 HCL Notes 時，`build.ps1` 會到 `C:\HCL\NotesR1202\jvm\lib\ext` 找 `Notes.jar` 一起編 Domino agent；路徑不同用 `-NotesExt` 指定，沒有就自動略過

## 建置

在這個資料夾執行（Windows PowerShell 5.1）：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

會編譯、跑離線測試（27 項），產生 `build\graph-poc.jar`，並把 `javax.mail.jar` 複製到同一個資料夾。

## 設定檔

複製 `graph-poc.properties.example` 到 `local\graph-poc.properties`（UTF-8 存檔）填值，或用 `setup\setup-m365-poc.ps1` 在試用租用戶自動產生。`local\` 已列在 `.gitignore`，**設定檔含用戶端密碼，不要放進版本控制**。

租用戶這邊要做的設定（應用程式註冊、用戶端密碼、共用信箱、管理範圍、角色指派）見教學頁「[一步步做](https://bryanhsiao.github.io/m365-graph-mail-java-poc/#walk)」。

## 執行

```powershell
java -jar .\build\graph-poc.jar .\local\graph-poc.properties token
```

```powershell
java -jar .\build\graph-poc.jar .\local\graph-poc.properties send
```

```powershell
java -jar .\build\graph-poc.jar .\local\graph-poc.properties list
```

```powershell
java -jar .\build\graph-poc.jar .\local\graph-poc.properties read .\local\out
```

```powershell
java -jar .\build\graph-poc.jar .\local\graph-poc.properties process .\local\out
```

| 指令 | 做什麼 |
|---|---|
| `token` | 取 token，顯示到期時間與 token 內的權限（用 RBAC for Applications 時 roles 本來就是空的） |
| `send [收件者]` | 用設定的信箱寄一封測試信（中文主旨、HTML 表格、中文檔名附件），沒給收件者就寄給 `testTo` |
| `list` | 列收件匣（由舊到新） |
| `read <資料夾>` | 讀信解析，原始信存成 `.eml`，不搬移 |
| `process <資料夾>` | 讀信解析後搬到 `processedFolder` 指定的資料夾，不存在會自動建立 |

`run-test.ps1` 會依序跑 `token`、`send`、`list`、`read`。

## 放進 Domino（摘要）

1. 先用伺服器的 JVM 跑一次命令列工具，確認防火牆、proxy、憑證信任沒問題：

```powershell
& "<Domino 目錄>\jvm\bin\java.exe" -cp "C:\graph-poc\graph-poc.jar;<javax.mail.jar 路徑>" GraphPocCli C:\graph-poc\graph-poc.properties list
```

   出現 `PKIX path building failed` 表示 JVM 的 cacerts 缺 Microsoft 憑證鏈的根憑證，用 keytool 匯入；不要改成全部信任。

2. 設定檔路徑不是 `C:\graph-poc\graph-poc.properties` 時，在主控台設定 `set config GraphPocConfig=<路徑>`。
3. Designer：Java script library 匯入 `GraphConfig`、`MiniJson`、`GraphMailClient`、`MailText`、`GraphNotes`、`PocSamples`；Java agent 匯入 `GraphSendAgent` 或 `GraphReadAgent`，Edit Project 加入 script library。
4. agent 的 runtime security level 設 **2. Allow restricted operations**（連網路與讀設定檔都需要），簽署者要在伺服器文件「Sign or run restricted LotusScript/Java agents」裡；Java 編譯相容層級設 1.8。

## 已知限制

- 收件者要是 email 地址，Graph 不認 Notes 階層名稱（`CN=xxx/O=xxx`）
- 附件合計超過 3MB 要改用 upload session（這份直接拒絕）
- 用戶端密碼會到期（最長 24 個月），要排更新流程，或改用憑證登入（尚未實作）
- `GraphNotes`（MIME 轉 Notes 文件）與 Domino 11 實機尚未驗證
