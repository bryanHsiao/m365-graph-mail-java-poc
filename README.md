# 用 Java 透過 Microsoft Graph 寄信讀信，權限只開一個信箱

Exchange Online 已停用 IMAP／POP 的帳號密碼登入，用 JavaMail 帳密讀信的舊程式（例如 Domino 上的 Java agent）都得改。這是一份實作紀錄：從申請 Microsoft 365 試用版開始，一步步做到 Java 8 程式以「應用程式身分」寄信、讀外部來信、解析中文內容、搬到「已處理」資料夾，並且權限只限一個專用信箱。

**線上閱讀**：https://bryanhsiao.github.io/m365-graph-mail-java-poc/

## 內容

- 原理：應用程式註冊、token、Exchange 應用程式角色指派（RBAC for Applications）各自負責什麼
- 一步步做：申請試用版 → Entra 註冊應用程式 → Exchange 共用信箱、管理範圍、角色指派 → Java 程式 → 測試 → 反向測試
- 指令與程式片段：PowerShell 5.1（代碼登入、REST 呼叫 Graph、Exchange 設定）、Java 8（token、sendMail、列信、取原始 MIME、搬移、重試）
- 踩過的坑：Graph 讀取請求回 406、新租用戶角色指派被拒、PowerShell 5.1 模組問題、Windows Defender 擋寫入等
- 邊界：用戶端密碼到期、大附件、收件者格式、權限生效延遲、尚未驗證的部分

## 文件

- [docs/廠商設定手冊-Exchange-Graph串接.md](docs/%E5%BB%A0%E5%95%86%E8%A8%AD%E5%AE%9A%E6%89%8B%E5%86%8A-Exchange-Graph%E4%B8%B2%E6%8E%A5.md)：給 Exchange Online／Entra ID 管理者的設定手冊（應用程式註冊、用戶端密碼、信箱、管理範圍、角色指派、檢查、交付值、維運），另有 [Word 版](docs/%E5%BB%A0%E5%95%86%E8%A8%AD%E5%AE%9A%E6%89%8B%E5%86%8A-Exchange-Graph%E4%B8%B2%E6%8E%A5.docx)

## 程式

`poc-graph-java/`：教學頁用到的 Java 8 程式（命令列工具、Domino agent 範例、離線測試、試用租用戶設定腳本）。建置與執行方式見 [poc-graph-java/README.md](poc-graph-java/README.md)。

## 環境

- Microsoft 365 商務基本版（試用）
- Windows PowerShell 5.1、ExchangeOnlineManagement 模組
- Java 8、JavaMail 1.6.2

## 說明

- 文中的租用戶、帳號、信箱與識別碼都已換成範例值（`contoso`），截圖中的個人帳號已遮蔽。
- 測試只用自編的假資料。
- 這是 PoC 紀錄，不是正式產品；導入正式環境前請依自己的資安規範調整，特別是用戶端密碼的保管與更換。

## 授權

MIT，見 [LICENSE](LICENSE)。
