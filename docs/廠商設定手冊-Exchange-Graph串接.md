# 情資管理系統串接 Exchange Online：廠商設定手冊

> 對象：Exchange Online／Entra ID 的管理者
> 目的：讓情資管理系統（Domino 上的 Java 程式）用 Microsoft Graph API **寄信**與**讀信**，而且權限只限指定信箱
> 文中的 `contoso.com`、`<AppId>` 等都是範例，請換成實際值。這些指令已在 Microsoft 365 試用租用戶實際走過一遍（當時讀信、寄件用同一個信箱；分成兩個範圍是同樣的指令多做一次）。

---

## 0. 開始前

### 需要的管理權限

| 做什麼 | 需要的角色 |
|---|---|
| 註冊應用程式、建立用戶端密碼 | Entra ID 的「應用程式系統管理員」或「雲端應用程式系統管理員」（全域管理員也可以） |
| 建立信箱、管理範圍、角色指派 | Exchange Online 的 **Organization Management** 角色群組成員（應用程式角色的委派指派在這個群組上），在 Entra ID 需要「Exchange 系統管理員」角色 |

### 先決定的值

| 項目 | 範例 | 說明 |
|---|---|---|
| 應用程式名稱 | `情資管理系統 Graph` | 只是顯示用，看得出用途即可 |
| 讀信信箱 | `intel@contoso.com` | 外部情資信寄到這裡，系統讀取後搬到「已處理」資料夾 |
| 寄件信箱 | `intel-notify@contoso.com` | 系統通知信由它寄出，顯示名稱「情資管理系統」。可以和讀信信箱是同一個 |
| 用戶端密碼有效期限 | 12 個月 | 最長 24 個月，Microsoft 建議 12 個月內 |
| 情資系統主機的對外 IP | （主機管理者提供） | 防火牆與條件式存取會用到 |

### 工具

- 網頁：Microsoft Entra 系統管理中心 `https://entra.microsoft.com`
- PowerShell：`ExchangeOnlineManagement` 模組 3.x，建議在 **PowerShell 7** 執行

```powershell
Install-Module ExchangeOnlineManagement -Scope CurrentUser
```

### 整體做法（為什麼不在 Entra 勾 Mail 權限）

權限用 Exchange 的 **RBAC for Applications**（應用程式角色指派）給，範圍限定在上面兩個信箱。

**不要**在 Entra 應用程式的「API 權限」加 Microsoft Graph 的 `Mail.Send`、`Mail.ReadWrite` 等**應用程式權限**：那是整個租用戶**所有信箱**的權限，而且兩邊的權限是取聯集，Exchange 這邊限定得再嚴也會失效。舊做法「應用程式存取原則」（Application Access Policy）已由 RBAC for Applications 取代，也不需要再設。

---

## 1. 註冊應用程式（Entra 系統管理中心）

1. 身分識別 → 應用程式 → **應用程式註冊** → **新增註冊**
2. 名稱：`情資管理系統 Graph`
3. 支援的帳戶類型：**僅此組織目錄中的帳戶（單一租用戶）**
4. 重新導向 URI：留空 → **註冊**
5. 在「概觀」頁記下：
   - **應用程式（用戶端）識別碼**（GUID，以下稱 `<AppId>`）
   - **目錄（租用戶）識別碼**（GUID）
6. 「API 權限」**不用加任何權限**。預設的 Microsoft Graph `User.Read`（委派）可以留著或移除，不影響。

## 2. 建立用戶端密碼

1. 同一個應用程式 → **憑證及祕密** → **用戶端密碼** → **新增用戶端密碼**
2. 描述：例如 `情資系統 2026-11`；到期：選定的期限 → **新增**
3. 立刻複製清單上「**值**」那一欄（約 40 字元，例如 `Abc8Q~...`）
   - 這個值**只在建立當下顯示一次**，離開頁面就看不到，忘了複製只能刪掉重建
   - 旁邊的「**祕密識別碼**」是 GUID，**不是**密碼，不要交錯
   - 建立和複製的時候**不要分享畫面或錄影**，值一旦出現在畫面上就要刪掉重建
4. 記下到期日

## 3. 查企業應用程式的物件識別碼

Exchange 的服務主體要用**企業應用程式**的物件識別碼，**不是**應用程式註冊頁上的「物件識別碼」，這裡最常填錯。

1. 身分識別 → 應用程式 → **企業應用程式**
2. 搜尋 `情資管理系統 Graph` → 點進去 → 「概觀」或「屬性」
3. 記下「**物件識別碼**」（以下稱 `<ObjectId>`）

| 頁面 | 應用程式識別碼 | 物件識別碼 |
|---|---|---|
| 應用程式註冊 | `<AppId>` | 不是這個 |
| 企業應用程式 | 同一個 `<AppId>` | **用這個** `<ObjectId>` |

## 4. 連線 Exchange Online

```powershell
Connect-ExchangeOnline -UserPrincipalName admin@contoso.com
```

> 若出現「需要先執行 Enable-OrganizationCustomization」的錯誤（通常只有很新的租用戶），先執行一次：
>
> ```powershell
> Enable-OrganizationCustomization
> ```

## 5. 建立信箱（已有現成信箱就跳過）

建議用**共用信箱**：不需要授權、沒有密碼、不能直接登入。

```powershell
# 讀信信箱
New-Mailbox -Shared -Name intel -Alias intel -DisplayName "情資信箱" -PrimarySmtpAddress intel@contoso.com
```

```powershell
# 寄件信箱（和讀信信箱不同時才需要）；收件者看到的寄件者名稱就是 DisplayName
New-Mailbox -Shared -Name intel-notify -Alias intel-notify -DisplayName "情資管理系統" -PrimarySmtpAddress intel-notify@contoso.com
```

已有的信箱要改顯示名稱：

```powershell
Set-Mailbox -Identity intel-notify@contoso.com -DisplayName "情資管理系統"
```

## 6. 在 Exchange 建立服務主體

```powershell
New-ServicePrincipal -AppId <AppId> -ObjectId <ObjectId> -DisplayName "情資管理系統 Graph"
```

確認：

```powershell
Get-ServicePrincipal -Identity <AppId>
```

## 7. 建立管理範圍（限定信箱）

讀信、寄件分開兩個範圍，各自只包含一個信箱：

```powershell
New-ManagementScope -Name "IntelMail-Read" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'intel@contoso.com'"
```

```powershell
New-ManagementScope -Name "IntelMail-Send" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'intel-notify@contoso.com'"
```

> 讀信和寄件用**同一個信箱**時，只建一個範圍，第 8 步兩個指派都用它即可。

## 8. 角色指派

```powershell
# 讀信：列信、取原始信件、搬到「已處理」資料夾
New-ManagementRoleAssignment -Name "IntelMail-ReadWrite" -App <AppId> -Role "Application Mail.ReadWrite" -CustomResourceScope "IntelMail-Read"
```

```powershell
# 寄信
New-ManagementRoleAssignment -Name "IntelMail-Send" -App <AppId> -Role "Application Mail.Send" -CustomResourceScope "IntelMail-Send"
```

- 要用 **Organization Management** 成員的帳號執行
- 指派後通常 **30 分鐘到 2 小時**才生效（應用程式的權限有快取；第 9 步的檢查指令不受快取影響，會立即反映）

## 9. 檢查

```powershell
Test-ServicePrincipalAuthorization -Identity <AppId> -Resource intel@contoso.com
```

```powershell
Test-ServicePrincipalAuthorization -Identity <AppId> -Resource intel-notify@contoso.com
```

預期結果：對應的角色 `InScope` 為 `True`。

```text
RoleName                   GrantedPermissions AllowedResourceScope ScopeType            InScope
--------                   ------------------ -------------------- ---------            -------
Application Mail.ReadWrite Mail.ReadWrite     IntelMail-Read       CustomRecipientScope True
Application Mail.Send      Mail.Send          IntelMail-Send       CustomRecipientScope False
```

（上例是讀信信箱：只有 Mail.ReadWrite 在範圍內，Mail.Send 不在，這是正確的。寄件信箱則相反。）

再拿一個**不相關的信箱**測一次，兩個角色都應該是 `False`：

```powershell
Test-ServicePrincipalAuthorization -Identity <AppId> -Resource someone@contoso.com
```

生效後，我方會實際呼叫 API 測試寄信、讀信，並確認對其他信箱回 `403 ErrorAccessDenied`。

## 10. 防火牆與網路

情資系統主機只需要**主動對外**連線，不用開任何對內的 port：

| 目的地 | port | 用途 |
|---|---|---|
| `login.microsoftonline.com` | 443 | 取得 token |
| `graph.microsoft.com` | 443 | 寄信、讀信 |

- 若經過 proxy：請提供 proxy 位址、是否需要驗證、是否做 SSL 解密檢查（會解密的話，主機的 Java 要另外信任 proxy 的根憑證）
- 若租用戶有**條件式存取**限制應用程式登入的來源 IP，請把情資系統主機的對外 IP 加進允許清單

## 11. 交付給我方的值

| 值 | 範例格式 | 備註 |
|---|---|---|
| 目錄（租用戶）識別碼 | `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | 第 1 步 |
| 應用程式（用戶端）識別碼 | `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | 第 1 步 |
| 用戶端密碼的「值」 | 約 40 字元，例如 `Abc8Q~...` | 第 2 步。**不要用 email 明文傳**，請約定安全的交付方式（例如分開傳送、加密壓縮檔的密碼另行告知） |
| 密碼到期日 | `2027-11-19` | 排入更新提醒 |
| 讀信信箱地址 | `intel@contoso.com` | 第 5 步 |
| 寄件信箱地址與顯示名稱 | `intel-notify@contoso.com`、「情資管理系統」 | 第 5 步 |

## 12. 日後維運

### 用戶端密碼到期前更換（不停機）

1. 到期前一個月：在「憑證及祕密」**再新增一組**用戶端密碼（一個應用程式可以同時有兩組，舊的照常能用）
2. 把新的值交給我方，我方更新主機設定並實測
3. 我方確認後，再**刪除舊的**那組

到期沒換的話，系統拿不到 token，寄信與讀信會同時停止（錯誤代碼 `AADSTS7000222`）。

### 查目前的設定

```powershell
Get-ManagementRoleAssignment -Identity "IntelMail-ReadWrite" | Format-List Name, Role, CustomResourceScope
Get-ManagementRoleAssignment -Identity "IntelMail-Send" | Format-List Name, Role, CustomResourceScope
```

```powershell
Get-ManagementScope -Identity "IntelMail-Read" | Format-List Name, RecipientFilter
```

### 收回權限

```powershell
Remove-ManagementRoleAssignment -Identity "IntelMail-ReadWrite"
Remove-ManagementRoleAssignment -Identity "IntelMail-Send"
Remove-ManagementScope -Identity "IntelMail-Read"
Remove-ManagementScope -Identity "IntelMail-Send"
Remove-ServicePrincipal -Identity <AppId>
```

最後在 Entra 系統管理中心刪除該應用程式註冊。（直接刪除 Entra 的應用程式時，Exchange 端的服務主體與角色指派也會自動移除，但管理範圍會留著，要另外刪。）

---

## 常見問題

| 狀況 | 原因與處理 |
|---|---|
| `New-ServicePrincipal` 成功，但之後指派或測試找不到 | `-ObjectId` 填成應用程式註冊頁的物件識別碼。請改用**企業應用程式**的物件識別碼，刪掉重建服務主體 |
| `New-ManagementRoleAssignment` 回「您沒有建立、變更或移除管理角色指派的存取權…」 | 執行的帳號不是 Organization Management 成員；或租用戶剛執行 `Enable-OrganizationCustomization`，委派權限還沒同步（實測要等到隔天） |
| `Test-ServicePrincipalAuthorization` 的 `InScope` 是 `False` | 指派還沒生效（等 30 分鐘到 2 小時），或範圍的 `PrimarySmtpAddress` 打錯 |
| Entra 權限畫面看不到任何 Mail 權限、token 裡也沒有 roles | 正常。[RBAC for Applications](https://learn.microsoft.com/zh-tw/exchange/permissions-exo/application-rbac) 的權限存在 Exchange Online、由 Exchange 判斷，不會出現在 Entra 與 token 裡；Entra 後台合併顯示這些權限的功能，微軟文件寫的是「即將推出」。目前只能用第 12 步的指令查 |
| 已在 Entra 加過 Graph 的 Mail.* 應用程式權限 | 請移除並撤銷管理員同意，否則應用程式能存取全租用戶的信箱 |

## 參考文件（Microsoft Learn）

- [Exchange Online 的應用程式角色型存取控制（RBAC for Applications）](https://learn.microsoft.com/zh-tw/exchange/permissions-exo/application-rbac)
- [在 Microsoft Entra ID 註冊應用程式](https://learn.microsoft.com/zh-tw/entra/identity-platform/quickstart-register-app)
- [新增用戶端密碼](https://learn.microsoft.com/zh-tw/entra/identity-platform/how-to-add-credentials)
