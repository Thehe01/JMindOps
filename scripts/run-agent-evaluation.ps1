[CmdletBinding()]
param(
    [string]$DatasetPath = "evaluation-data/agent-eval-v1.json",

    [Parameter(Mandatory = $true)]
    [string]$AgentId,

    [string]$ApiBaseUrl = "http://localhost:3000/api",

    [string]$Token = $env:JMINDOPS_TOKEN,

    [string]$OutputDirectory,

    [string[]]$CaseIds,

    [ValidateRange(10, 1800)]
    [int]$CaseTimeoutSeconds = 180,

    [ValidateRange(1, 30)]
    [int]$PollIntervalSeconds = 2,

    [ValidateRange(2, 120)]
    [int]$MaxPollIntervalSeconds = 15,

    [ValidateRange(0, 20)]
    [int]$MaxRateLimitRetries = 6,

    [ValidateRange(1, 300)]
    [int]$RateLimitRetrySeconds = 61,

    [switch]$AllowDraft
)

$ErrorActionPreference = "Stop"

function Invoke-JMindOpsApi {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body
    )

    $parameters = @{
        Method = $Method
        Uri = "$script:BaseUrl$Path"
        Headers = @{ Authorization = "Bearer $Token" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 20
    }
    $attempt = 0
    while ($true) {
        try {
            $response = Invoke-RestMethod @parameters
            break
        } catch {
            $statusCode = 0
            if ($null -ne $_.Exception.Response) {
                try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { $statusCode = 0 }
            }
            if ($statusCode -ne 429 -or $attempt -ge $MaxRateLimitRetries) {
                throw
            }
            $attempt++
            Write-Host "API 触发 429 限流，${RateLimitRetrySeconds}s 后重试（$attempt/$MaxRateLimitRetries）：$Method $Path"
            Start-Sleep -Seconds $RateLimitRetrySeconds
        }
    }
    if ($null -eq $response -or [int]$response.code -ne 200) {
        $message = if ($null -eq $response) { "empty response" } else { [string]$response.message }
        throw "接口调用失败: $Method $Path, $message"
    }
    return $response.data
}

function Get-TextSha256 {
    param([AllowEmptyString()][string]$Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Test-ContainsText {
    param([string]$Content, [string]$Expected)
    return $Content.IndexOf($Expected, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function New-AnswerAssertions {
    param([object]$Expected, [AllowEmptyString()][string]$FinalAnswer)
    $propertyNames = @($Expected.PSObject.Properties.Name)
    $configured = @(
        "requireFinalAnswer",
        "answerMustContain",
        "answerMustNotContain",
        "minimumAnswerLength"
    ) | Where-Object { $_ -in $propertyNames }
    $mustContain = @($Expected.answerMustContain | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $mustNotContain = @($Expected.answerMustNotContain | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $requireAnswer = $Expected.requireFinalAnswer -eq $true
    $minimumLength = if ($null -eq $Expected.minimumAnswerLength) { 0 } else { [int]$Expected.minimumAnswerLength }
    $present = -not [string]::IsNullOrWhiteSpace($FinalAnswer)
    $mustContainOk = @($mustContain | Where-Object { -not (Test-ContainsText $FinalAnswer ([string]$_)) }).Count -eq 0
    $mustNotContainOk = @($mustNotContain | Where-Object { Test-ContainsText $FinalAnswer ([string]$_) }).Count -eq 0
    $minimumLengthOk = $FinalAnswer.Length -ge $minimumLength
    $passed = $configured.Count -eq 0 -or (
        (-not $requireAnswer -or $present) -and
        $mustContainOk -and
        $mustNotContainOk -and
        $minimumLengthOk
    )
    return [ordered]@{
        configured = $configured.Count -gt 0
        passed = $passed
        answerPresent = $present
        answerLength = $FinalAnswer.Length
        answerHash = Get-TextSha256 $FinalAnswer
        mustContainOk = $mustContainOk
        mustNotContainOk = $mustNotContainOk
        minimumLengthOk = $minimumLengthOk
    }
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 JWT。请设置 JMINDOPS_TOKEN，或使用 -Token 参数。"
}
try {
    $parsedAgentId = [Guid]::Parse($AgentId)
    $AgentId = $parsedAgentId.ToString()
} catch {
    throw "AgentId 必须是标准 UUID。"
}
if ($MaxPollIntervalSeconds -lt $PollIntervalSeconds) {
    throw "MaxPollIntervalSeconds 不能小于 PollIntervalSeconds。"
}

$resolvedDatasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
$datasetSha256 = (Get-FileHash -LiteralPath $resolvedDatasetPath -Algorithm SHA256).Hash.ToLowerInvariant()
$dataset = Get-Content -Raw -LiteralPath $resolvedDatasetPath | ConvertFrom-Json
$cases = @($dataset.cases)
if ($cases.Count -eq 0) {
    throw "AgentEval 题集至少需要一个 case。"
}
if ($CaseIds.Count -gt 0) {
    $knownIds = @($cases | ForEach-Object { [string]$_.id })
    $unknownIds = @($CaseIds | Where-Object { $_ -notin $knownIds })
    if ($unknownIds.Count -gt 0) {
        throw "未知 CaseIds: $($unknownIds -join ', ')"
    }
    $cases = @($cases | Where-Object { $_.id -in $CaseIds })
}
$annotationStatus = [string]$dataset.annotationStatus
if ($annotationStatus -ne "REVIEWED" -and -not $AllowDraft) {
    throw "题集 annotationStatus=$annotationStatus。试跑 DRAFT 题集请显式加 -AllowDraft。"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force
$script:BaseUrl = $ApiBaseUrl.TrimEnd("/")
$agentsResponse = Invoke-JMindOpsApi -Method GET -Path "/agents" -Body $null
$agent = @($agentsResponse.agents | Where-Object { [string]$_.id -eq $AgentId }) | Select-Object -First 1
if ($null -eq $agent) {
    throw "当前用户下不存在 AgentId=$AgentId。"
}
$agentSnapshot = [ordered]@{
    id = $AgentId
    name = [string]$agent.name
    model = [string]$agent.model
    systemPromptSha256 = Get-TextSha256 ([string]$agent.systemPrompt)
    allowedTools = @($agent.allowedTools | ForEach-Object { [string]$_ } | Sort-Object)
    allowedKbs = @($agent.allowedKbs | ForEach-Object { [string]$_ } | Sort-Object)
    chatOptions = $agent.chatOptions
}
$results = [System.Collections.Generic.List[object]]::new()
$runId = [Guid]::NewGuid().ToString()

foreach ($case in $cases) {
    Write-Host "[$($case.id)] 创建隔离会话并执行..."
    $session = Invoke-JMindOpsApi -Method POST -Path "/chat-sessions" -Body @{
        agentId = $AgentId
        title = "AgentEval $($case.id)"
    }
    $sessionId = [string]$session.chatSessionId
    $generation = Invoke-JMindOpsApi -Method POST -Path "/chat-messages" -Body @{
        requestId = [Guid]::NewGuid().ToString()
        agentId = $AgentId
        sessionId = $sessionId
        content = [string]$case.input
    }
    $generationId = [string]$generation.generationId
    if ([string]::IsNullOrWhiteSpace($generationId)) {
        throw "[$($case.id)] 后端未返回 generationId。"
    }

    $deadline = (Get-Date).AddSeconds($CaseTimeoutSeconds)
    $currentPollInterval = $PollIntervalSeconds
    $lastProgressKey = ""
    do {
        Start-Sleep -Seconds $currentPollInterval
        $task = Invoke-JMindOpsApi -Method GET -Path "/generation-tasks/$generationId" -Body $null
        $progressKey = "$($task.status)|$($task.updatedAt)"
        if ($progressKey -ne $lastProgressKey) {
            $currentPollInterval = $PollIntervalSeconds
            $lastProgressKey = $progressKey
        } else {
            $currentPollInterval = [Math]::Min(
                $MaxPollIntervalSeconds,
                [Math]::Max($PollIntervalSeconds, [Math]::Ceiling($currentPollInterval * 1.5))
            )
        }
        if ((Get-Date) -ge $deadline -and $task.status -notin @("SUCCEEDED", "FAILED")) {
            throw "[$($case.id)] 超过 ${CaseTimeoutSeconds}s 仍未到达终态。"
        }
    } while ($task.status -notin @("SUCCEEDED", "FAILED"))

    $trace = Invoke-JMindOpsApi -Method GET -Path "/generation-tasks/$generationId/trace" -Body $null
    $messageHistory = Invoke-JMindOpsApi -Method GET -Path "/chat-messages/session/$sessionId" -Body $null
    $assistantMessages = @($messageHistory.chatMessages | Where-Object { [string]$_.role -eq "assistant" })
    $finalAnswer = if ($assistantMessages.Count -eq 0) { "" } else { [string]$assistantMessages[-1].content }
    $results.Add([ordered]@{
        caseId = [string]$case.id
        generationId = $generationId
        sessionId = $sessionId
        route = $trace.routingDecision
        terminalStatus = [string]$task.status
        answerAssertions = New-AnswerAssertions $case.expected $finalAnswer
        steps = @($trace.steps)
    })
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$observedPath = Join-Path $resolvedOutputDirectory "agent-evaluation-$timestamp-observed.json"
$reportPath = Join-Path $resolvedOutputDirectory "agent-evaluation-$timestamp-report.json"
$observed = [ordered]@{
    datasetId = [string]$dataset.datasetId
    datasetSha256 = $datasetSha256
    runId = $runId
    evidenceType = "SINGLE_RUN"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    agentId = $AgentId
    agentSnapshot = $agentSnapshot
    apiBaseUrl = $script:BaseUrl
    polling = [ordered]@{
        initialIntervalSeconds = $PollIntervalSeconds
        maxIntervalSeconds = $MaxPollIntervalSeconds
        caseTimeoutSeconds = $CaseTimeoutSeconds
    }
    git = [ordered]@{
        commit = (& git rev-parse HEAD 2>$null | Select-Object -First 1)
        dirty = @(& git status --porcelain -uall 2>$null).Count -gt 0
    }
    results = $results
}
$observed | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $observedPath -Encoding utf8

$scorerPath = Join-Path $PSScriptRoot "score-agent-eval.py"
& python $scorerPath --dataset $resolvedDatasetPath --observed $observedPath --output $reportPath
if ($LASTEXITCODE -ne 0) {
    throw "AgentEval 评分器执行失败，退出码 $LASTEXITCODE。"
}

Write-Host "Observed: $observedPath"
Write-Host "Report:   $reportPath"
