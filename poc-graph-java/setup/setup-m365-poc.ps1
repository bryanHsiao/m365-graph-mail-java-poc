# 情資系統 Graph PoC：一次建好 M365 試用租用戶端的設定
#   1. 安裝 PowerShell 模組（只裝在目前使用者）
#   2. Entra ID：應用程式註冊、服務主體、用戶端密碼
#   3. Exchange Online：共用信箱、限定只能存取該信箱的應用程式授權（RBAC for Applications）
#   4. 產生 Java 程式用的設定檔 poc-graph-java\local\graph-poc.properties（密碼直接寫入檔案，不顯示在畫面上）
# 模組、設定檔、執行狀態都放在 poc-graph-java\local\，不放到專案以外
#
# 用法（Windows PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File .\setup-m365-poc.ps1
# 過程中要登入兩次（Entra 一次、Exchange 一次），都用試用租用戶的全域管理員：
# 畫面會顯示代碼，到 https://login.microsoft.com/device 輸入後登入，完成後腳本自動繼續。
# 可以重複執行：已建立的項目會略過；加 -NewSecret 才會再產生新的用戶端密碼。
param(
    [string]$AppName = "IntelPoc Graph",
    [string]$MailboxAlias = "intel-poc",
    [string]$MailboxDisplayName = "情資系統 PoC",
    [string]$ConfigDir = "",
    [string]$TestTo = "",
    [int]$SecretMonths = 6,
    [switch]$NewSecret,
    [ValidateSet("All", "Modules", "Entra", "Exchange")]
    [string]$Part = "All"
)
$ErrorActionPreference = "Stop"

if (-not $ConfigDir) { $ConfigDir = Join-Path (Split-Path -Parent $PSScriptRoot) "local" }
$statePath = Join-Path $ConfigDir "setup-state.json"
$configPath = Join-Path $ConfigDir "graph-poc.properties"
# 模組放在專案的 local\ps-modules，只在執行本腳本時加進搜尋路徑：不裝進 OneDrive 的「文件」，也不改使用者環境設定
$moduleDir = Join-Path $ConfigDir "ps-modules"
$env:PSModulePath = "$moduleDir;$env:PSModulePath"
$scopeName = "IntelPocMailbox"
$roles = @("Application Mail.Send", "Application Mail.ReadWrite")

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "== $msg" -ForegroundColor Cyan
}

function Save-Utf8NoBom([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}

# 剛建立的物件要一段時間才同步到 Entra／Exchange 各處，失敗就等一下再試
function Invoke-WithRetry([string]$what, [scriptblock]$action, [int]$tries = 6, [int]$waitSeconds = 20) {
    for ($i = 1; $i -le $tries; $i++) {
        try {
            return & $action
        } catch {
            if ($i -eq $tries) { throw }
            Write-Host "$what 失敗（第 $i 次）：$($_.Exception.Message)" -ForegroundColor Yellow
            Write-Host "等 $waitSeconds 秒後重試……"
            Start-Sleep -Seconds $waitSeconds
        }
    }
}

function Read-State {
    if (-not (Test-Path -LiteralPath $statePath)) { throw "找不到 $statePath，請先執行 -Part Entra" }
    return Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Install-PocModules {
    Write-Step "檢查 PowerShell 模組"
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $nuget = Get-PackageProvider -ListAvailable -Name NuGet -ErrorAction SilentlyContinue |
        Where-Object { $_.Version -ge [version]"2.8.5.201" }
    if (-not $nuget) {
        Write-Host "安裝 NuGet 套件提供者"
        Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Scope CurrentUser -Force | Out-Null
    }
    if (-not (Test-Path -LiteralPath $moduleDir)) { New-Item -ItemType Directory -Path $moduleDir | Out-Null }
    # Entra 段改用 REST 直接呼叫，不需要 Microsoft.Graph 模組（它在 Windows PowerShell 5.1 登入時會出 EventSourceException）
    foreach ($m in @("ExchangeOnlineManagement")) {
        if (Get-Module -ListAvailable -Name $m) {
            Write-Host "已安裝 $m"
        } else {
            Write-Host "下載 $m 到 $moduleDir（約需 1～3 分鐘）"
            Save-Module -Name $m -Path $moduleDir -Repository PSGallery -Force
        }
    }
}

# 兩段都用 Microsoft 官方的公用用戶端，以管理員身分代碼登入（不跳登入視窗，避免被藏在其他視窗後面）
$graphCliClientId = "14d82eec-204b-4c2f-b7e8-296a70dab67e"   # Microsoft Graph Command Line Tools
$exoPsClientId = "fb78d390-0c51-40cd-8e17-fdbfab77341b"      # Exchange Online PowerShell
$script:graphToken = $null

function Get-ErrorCode($err) {
    try {
        return ($err.ErrorDetails.Message | ConvertFrom-Json).error
    } catch {
        return $null
    }
}

# 回傳 @{ token = 存取權杖; claims = 權杖內容（tid、upn 等） }
function Get-TokenByDeviceCode([string]$clientId, [string]$scope) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $dc = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/organizations/oauth2/v2.0/devicecode" `
        -Body @{ client_id = $clientId; scope = $scope }
    Write-Host ""
    Write-Host "請在瀏覽器開啟 $($dc.verification_uri) ，輸入代碼：$($dc.user_code)" -ForegroundColor Yellow
    Write-Host "（代碼 $([int]($dc.expires_in / 60)) 分鐘內有效，登入完成後這裡會自動繼續）"
    $interval = [int]$dc.interval
    $deadline = (Get-Date).AddSeconds([int]$dc.expires_in)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $interval
        try {
            $tok = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/organizations/oauth2/v2.0/token" -Body @{
                grant_type = "urn:ietf:params:oauth:grant-type:device_code"
                client_id = $clientId
                device_code = $dc.device_code
            }
            # 從 token 取出租用戶與登入帳號（JWT 第二段是 base64url 編碼的 JSON）
            $payload = $tok.access_token.Split(".")[1].Replace("-", "+").Replace("_", "/")
            while ($payload.Length % 4) { $payload += "=" }
            $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
            return @{ token = $tok.access_token; claims = $claims }
        } catch {
            $code = Get-ErrorCode $_
            if ($code -eq "authorization_pending") { continue }
            if ($code -eq "slow_down") { $interval += 5; continue }
            throw
        }
    }
    throw "代碼已過期，請重新執行"
}

function Invoke-Graph([string]$method, [string]$uri, $body) {
    $headers = @{ Authorization = "Bearer $script:graphToken" }
    $url = "https://graph.microsoft.com$uri"
    if ($null -eq $body) {
        return Invoke-RestMethod -Method $method -Uri $url -Headers $headers
    }
    # Windows PowerShell 5.1 送字串 body 會用 ISO-8859-1，中文會壞掉，所以自己轉成 UTF-8 bytes
    $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 5))
    return Invoke-RestMethod -Method $method -Uri $url -Headers $headers -Body $bytes -ContentType "application/json; charset=utf-8"
}

function Invoke-EntraPart {
    Write-Step "登入 Entra ID（請用試用租用戶的全域管理員；第一次會要求同意 Microsoft Graph Command Line Tools 的權限）"
    $login = Get-TokenByDeviceCode $graphCliClientId "https://graph.microsoft.com/Application.ReadWrite.All https://graph.microsoft.com/Domain.Read.All"
    $script:graphToken = $login.token
    $ctx = [pscustomobject]@{ Account = $login.claims.upn; TenantId = $login.claims.tid }
    Write-Host "已登入：$($ctx.Account)，租用戶 $($ctx.TenantId)"

    $domains = (Invoke-Graph GET "/v1.0/domains" $null).value
    $domain = ($domains | Where-Object { $_.isDefault } | Select-Object -First 1).id
    if (-not $domain) { throw "找不到預設網域" }
    $mailbox = "$MailboxAlias@$domain"

    Write-Step "應用程式註冊：$AppName"
    $filter = [uri]::EscapeDataString("displayName eq '$AppName'")
    $app = (Invoke-Graph GET "/v1.0/applications?`$filter=$filter" $null).value | Select-Object -First 1
    if ($app) {
        Write-Host "已存在，沿用（appId $($app.appId)）"
    } else {
        $app = Invoke-Graph POST "/v1.0/applications" @{ displayName = $AppName; signInAudience = "AzureADMyOrg" }
        Write-Host "已建立（appId $($app.appId)）"
    }

    Write-Step "服務主體（企業應用程式）"
    $spFilter = [uri]::EscapeDataString("appId eq '$($app.appId)'")
    $sp = (Invoke-Graph GET "/v1.0/servicePrincipals?`$filter=$spFilter" $null).value | Select-Object -First 1
    if ($sp) {
        Write-Host "已存在，沿用（物件識別碼 $($sp.id)）"
    } else {
        $appId = $app.appId
        $sp = Invoke-WithRetry "建立服務主體" { Invoke-Graph POST "/v1.0/servicePrincipals" @{ appId = $appId } } 6 10
        Write-Host "已建立（物件識別碼 $($sp.id)）"
    }

    # 不在 Entra 加 Graph 的 Mail.* 應用程式權限：那會開放整個租用戶所有信箱，改由 Exchange RBAC 只授權專用信箱
    $hasSecret = (Test-Path -LiteralPath $configPath) -and
        ((Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -match "(?m)^clientSecret=\S+") -and
        ((Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -match "(?m)^clientId=$([regex]::Escape($app.appId))\s*$")
    Write-Step "用戶端密碼"
    if ($hasSecret -and -not $NewSecret) {
        Write-Host "設定檔已有這個應用程式的密碼，略過（要重新產生請加 -NewSecret）"
        $secretText = $null
    } else {
        $end = (Get-Date).AddMonths($SecretMonths).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        $credential = Invoke-Graph POST "/v1.0/applications/$($app.id)/addPassword" @{
            passwordCredential = @{ displayName = "graph-poc $(Get-Date -Format 'yyyy-MM-dd')"; endDateTime = $end }
        }
        $secretText = $credential.secretText
        Write-Host "已產生，到期日 $end（只寫進設定檔，不顯示）"
    }

    if (-not (Test-Path -LiteralPath $ConfigDir)) { New-Item -ItemType Directory -Path $ConfigDir | Out-Null }
    if ($secretText) {
        $lines = @(
            "# 由 setup-m365-poc.ps1 產生於 $(Get-Date -Format 'yyyy-MM-dd HH:mm')；含用戶端密碼，請勿外流",
            "tenantId=$($ctx.TenantId)",
            "clientId=$($app.appId)",
            "clientSecret=$secretText",
            "mailbox=$mailbox",
            "processedFolder=情資已處理",
            "unreadOnly=false",
            "maxMessages=20",
            "testTo=$(if ($TestTo) { $TestTo } else { $mailbox })",
            "notesForm=Memo"
        )
        Save-Utf8NoBom $configPath (($lines -join "`r`n") + "`r`n")
        Write-Host "已寫入 $configPath"
    }

    $state = [ordered]@{
        tenantId = $ctx.TenantId
        appId = $app.appId
        appObjectId = $app.id
        spObjectId = $sp.id
        mailbox = $mailbox
        appName = $AppName
    }
    Save-Utf8NoBom $statePath ($state | ConvertTo-Json)
    $script:graphToken = $null
}

function Test-ExoObject([scriptblock]$get) {
    try {
        $null = & $get
        return $true
    } catch {
        return $false
    }
}

function Invoke-ExchangePart {
    $state = Read-State
    Import-Module ExchangeOnlineManagement
    Write-Step "登入 Exchange Online（同一個全域管理員）"
    # Windows PowerShell 5.1 的 Connect-ExchangeOnline 沒有 -Device，自己用代碼登入取得 token 再交給它
    $login = Get-TokenByDeviceCode $exoPsClientId "https://outlook.office365.com/.default"
    Connect-ExchangeOnline -AccessToken $login.token -UserPrincipalName $login.claims.upn -ShowBanner:$false
    Write-Host "已登入 Exchange Online：$($login.claims.upn)"

    Write-Step "組織自訂設定"
    if ((Get-OrganizationConfig).IsDehydrated) {
        # 新租用戶要先執行，才能建立自訂的管理範圍與角色指派
        Write-Host "新租用戶第一次自訂，執行 Enable-OrganizationCustomization（可能需要幾分鐘）"
        Enable-OrganizationCustomization
    } else {
        Write-Host "已啟用"
    }

    Write-Step "共用信箱：$($state.mailbox)"
    if (Test-ExoObject { Get-EXOMailbox -Identity $state.mailbox -ErrorAction Stop }) {
        Write-Host "已存在，沿用"
    } else {
        New-Mailbox -Shared -Name $MailboxAlias -Alias $MailboxAlias -DisplayName $MailboxDisplayName -PrimarySmtpAddress $state.mailbox | Out-Null
        Write-Host "已建立（新信箱可能要幾分鐘後才能收信）"
    }

    Write-Step "Exchange 服務主體"
    if (Test-ExoObject { Get-ServicePrincipal -Identity $state.appId -ErrorAction Stop }) {
        Write-Host "已存在，沿用"
    } else {
        Invoke-WithRetry "New-ServicePrincipal" {
            New-ServicePrincipal -AppId $state.appId -ObjectId $state.spObjectId -DisplayName $state.appName | Out-Null
        }
        Write-Host "已建立"
    }

    Write-Step "管理範圍：只包含 $($state.mailbox)"
    if (Test-ExoObject { Get-ManagementScope -Identity $scopeName -ErrorAction Stop }) {
        Write-Host "已存在，沿用"
    } else {
        Invoke-WithRetry "New-ManagementScope" {
            New-ManagementScope -Name $scopeName -RecipientRestrictionFilter "PrimarySmtpAddress -eq '$($state.mailbox)'" | Out-Null
        } 10 30
        Write-Host "已建立"
    }

    # 指派 Application 角色需要 Organization Management 的委派權限。全域管理員是透過 TenantAdmins_xxx 群組算在裡面；
    # 新租用戶剛 Enable-OrganizationCustomization 時委派權限可能還沒同步，指派會回「沒有…委派角色指派」，只能等一段時間再跑
    Write-Step "Organization Management 與委派權限（供排查用）"
    $members = @(Get-RoleGroupMember -Identity "Organization Management" -ResultSize Unlimited)
    Write-Host "Organization Management 成員：$(($members | ForEach-Object { $_.Name }) -join ', ')"
    foreach ($role in $roles) {
        $delegating = @(Get-ManagementRoleAssignment -Role $role -Delegating $true -ErrorAction SilentlyContinue)
        Write-Host "$role 的委派指派：$(($delegating | ForEach-Object { $_.RoleAssigneeName }) -join ', ')"
    }

    Write-Step "應用程式角色指派"
    foreach ($role in $roles) {
        $name = "IntelPoc-" + $role.Replace("Application ", "")
        if (Test-ExoObject { Get-ManagementRoleAssignment -Identity $name -ErrorAction Stop }) {
            Write-Host "$role 已指派，沿用"
        } else {
            Invoke-WithRetry "指派 $role" {
                New-ManagementRoleAssignment -Name $name -App $state.appId -Role $role -CustomResourceScope $scopeName | Out-Null
            }
            Write-Host "$role 已指派"
        }
    }

    Write-Step "檢查授權（InScope 為 True 表示可以存取該信箱）"
    Test-ServicePrincipalAuthorization -Identity $state.appId -Resource $state.mailbox |
        Format-Table RoleName, GrantedPermissions, InScope -AutoSize | Out-String | Write-Host

    Disconnect-ExchangeOnline -Confirm:$false
}

function Invoke-Child([string]$childPart) {
    $childArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PSCommandPath, "-Part", $childPart,
        "-AppName", $AppName, "-MailboxAlias", $MailboxAlias, "-MailboxDisplayName", $MailboxDisplayName,
        "-ConfigDir", $ConfigDir, "-SecretMonths", $SecretMonths)
    if ($TestTo) { $childArgs += @("-TestTo", $TestTo) }
    if ($NewSecret) { $childArgs += "-NewSecret" }
    & powershell.exe @childArgs
    if ($LASTEXITCODE -ne 0) { throw "$childPart 步驟失敗，請看上面的錯誤訊息；修正後可以重新執行，已完成的項目會略過" }
}

switch ($Part) {
    "Modules" { Install-PocModules }
    "Entra" { Invoke-EntraPart }
    "Exchange" { Invoke-ExchangePart }
    "All" {
        Install-PocModules
        # Graph 與 Exchange 模組的登入元件版本會互相衝突，分開在兩個 PowerShell 程序執行
        Invoke-Child "Entra"
        Invoke-Child "Exchange"
        $state = Read-State
        Write-Step "完成"
        Write-Host "設定檔：$configPath"
        Write-Host "專用信箱：$($state.mailbox)"
        Write-Host "Exchange 授權生效可能要 30 分鐘到 2 小時，期間 Java 程式會收到 ErrorAccessDenied，稍後再試即可。"
        Write-Host "下一步：執行 ..\run-test.ps1"
    }
}
