[CmdletBinding()]
param(
    [string]$DatasetPath = "evaluation-data/rag-gold-v1.json",

    [string]$AgentId,

    [string]$KnowledgeBaseId,

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

    [ValidateRange(0, 10)]
    [int]$RateLimitRetries = 3,

    [ValidateRange(0, 60)]
    [int]$MinimumSubmissionIntervalSeconds = 6,

    [switch]$RequireFrozenTest,

    [switch]$AllowDraft,

    [switch]$AllowExposedTestRegression
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
    $response = $null
    for ($attempt = 0; $attempt -le $RateLimitRetries; $attempt++) {
        try {
            $response = Invoke-RestMethod @parameters
            break
        } catch {
            $httpResponse = $_.Exception.Response
            $statusCode = if ($null -eq $httpResponse) { 0 } else { [int]$httpResponse.StatusCode }
            if ($statusCode -ne 429 -or $attempt -ge $RateLimitRetries) {
                throw
            }

            $retryAfterSeconds = 60
            try {
                $headerValues = @($httpResponse.Headers.GetValues("Retry-After"))
                $parsedRetryAfter = 0
                if ($headerValues.Count -gt 0 -and
                    [int]::TryParse([string]$headerValues[0], [ref]$parsedRetryAfter)) {
                    $retryAfterSeconds = [Math]::Max(1, [Math]::Min(120, $parsedRetryAfter))
                }
            } catch {
                # 服务端没有合法 Retry-After 时使用保守的 60 秒退避。
            }
            Write-Warning (
                "API 返回 429：$Method $Path；等待 ${retryAfterSeconds}s 后重试 " +
                "$($attempt + 1)/$RateLimitRetries。"
            )
            Start-Sleep -Seconds $retryAfterSeconds
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

function Get-RepositorySnapshot {
    param([string]$RepositoryRoot)
    $commit = (& git -C $RepositoryRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    $status = @(& git -C $RepositoryRoot status --porcelain -uall 2>$null)
    $trackedDiff = (@(& git -C $RepositoryRoot diff --binary HEAD 2>$null) -join "`n")
    $untrackedRecords = @(& git -C $RepositoryRoot ls-files --others --exclude-standard 2>$null |
        Sort-Object | ForEach-Object {
            $path = Join-Path $RepositoryRoot $_
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                "$($_.Replace('\', '/'))|$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())"
            }
        })
    $trackedDiffSha256 = Get-TextSha256 $trackedDiff
    $untrackedManifestSha256 = Get-TextSha256 ($untrackedRecords -join "`n")
    return [ordered]@{
        commit = $commit
        dirty = $status.Count -gt 0
        trackedDiffSha256 = $trackedDiffSha256
        untrackedManifestSha256 = $untrackedManifestSha256
        workspaceFingerprint = Get-TextSha256 "$commit`n$trackedDiffSha256`n$untrackedManifestSha256"
    }
}

function Get-SourceEvidence {
    param([object[]]$KnowledgeMessages)
    $indexes = [System.Collections.Generic.HashSet[int]]::new()
    $documentIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($message in $KnowledgeMessages) {
        $content = [string]$message.content
        foreach ($match in [regex]::Matches($content, '\[Source\s+(\d+)\s*\|\s*documentId=([^\]\s]+)\]', 'IgnoreCase')) {
            $null = $indexes.Add([int]$match.Groups[1].Value)
            $null = $documentIds.Add($match.Groups[2].Value)
        }
    }
    return [ordered]@{
        sourceIndexes = @($indexes | Sort-Object)
        sourceDocumentIds = @($documentIds | Sort-Object)
    }
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 JWT。请设置 JMINDOPS_TOKEN，或使用 -Token 参数。"
}
if ($AllowExposedTestRegression -and $CaseIds.Count -eq 0) {
    throw "AllowExposedTestRegression 只能与非空 CaseIds 一起使用。"
}
if ($MaxPollIntervalSeconds -lt $PollIntervalSeconds) {
    throw "MaxPollIntervalSeconds 不能小于 PollIntervalSeconds。"
}

$resolvedDatasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$validatorArguments = @(
    (Join-Path $PSScriptRoot "validate-rag-eval-dataset.py"),
    "--dataset", $resolvedDatasetPath,
    "--repository-root", $repositoryRoot
)
if ($AllowDraft) { $validatorArguments += "--allow-draft" }
if ($RequireFrozenTest) { $validatorArguments += "--require-frozen-test" }
if ($AllowExposedTestRegression) { $validatorArguments += "--allow-exposed-test-regression" }
& python @validatorArguments
if ($LASTEXITCODE -ne 0) {
    throw "RAG 题集校验失败，退出码 $LASTEXITCODE。"
}
$datasetSha256 = (Get-FileHash -LiteralPath $resolvedDatasetPath -Algorithm SHA256).Hash.ToLowerInvariant()
$dataset = Get-Content -Raw -LiteralPath $resolvedDatasetPath | ConvertFrom-Json
$cases = @($dataset.testCases)
if ($cases.Count -eq 0) {
    throw "RAG 回答题集至少需要一个 testCase。"
}
$caseIdsInDataset = @($cases | ForEach-Object { [string]$_.id })
if (@($caseIdsInDataset | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
    throw "每个 RAG 回答 testCase 都必须配置稳定 id。"
}
if (@($caseIdsInDataset | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw "RAG 回答题集存在重复 id。"
}
if ($CaseIds.Count -gt 0) {
    $unknownIds = @($CaseIds | Where-Object { $_ -notin $caseIdsInDataset })
    if ($unknownIds.Count -gt 0) {
        throw "未知 CaseIds: $($unknownIds -join ', ')"
    }
    $cases = @($cases | Where-Object { $_.id -in $CaseIds })
}
$annotationStatus = [string]$dataset.annotationStatus
if ($annotationStatus -ne "REVIEWED" -and -not $AllowDraft) {
    throw "题集 annotationStatus=$annotationStatus。试跑 DRAFT 题集请显式加 -AllowDraft。"
}
$queryPrefix = [string]$dataset.answerEvaluationDefaults.queryPrefix
if ([string]::IsNullOrWhiteSpace($queryPrefix)) {
    throw "RAG 回答题集必须在 answerEvaluationDefaults.queryPrefix 中声明显式知识库路由前缀。"
}

if ([string]::IsNullOrWhiteSpace($AgentId)) {
    $AgentId = [string]$dataset.agentId
}
try {
    $parsedAgentId = [Guid]::Parse($AgentId)
    $AgentId = $parsedAgentId.ToString()
} catch {
    throw "AgentId 必须通过参数或题集 agentId 配置为标准 UUID。"
}
$script:BaseUrl = $ApiBaseUrl.TrimEnd("/")
$agentsResponse = Invoke-JMindOpsApi -Method GET -Path "/agents" -Body $null
$agent = @($agentsResponse.agents | Where-Object { [string]$_.id -eq $AgentId }) | Select-Object -First 1
if ($null -eq $agent) {
    throw "当前用户下不存在 AgentId=$AgentId。"
}
$datasetKnowledgeBaseId = [string]$dataset.kbId
$knowledgeBaseId = if ([string]::IsNullOrWhiteSpace($KnowledgeBaseId)) {
    $datasetKnowledgeBaseId
} else {
    $KnowledgeBaseId
}
if ([string]::IsNullOrWhiteSpace($knowledgeBaseId) -or $knowledgeBaseId -like "REPLACE_*") {
    throw "题集未绑定知识库；请通过 -KnowledgeBaseId 传入本轮已复核知识库的 UUID。"
}
try {
    $knowledgeBaseId = [Guid]::Parse($knowledgeBaseId).ToString()
} catch {
    throw "KnowledgeBaseId 必须是标准 UUID。"
}
if ($knowledgeBaseId -notin @($agent.allowedKbs | ForEach-Object { [string]$_ })) {
    throw "Agent 未绑定题集知识库 $knowledgeBaseId，拒绝生成不可比较的结果。"
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

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force
$results = [System.Collections.Generic.List[object]]::new()
$runId = [Guid]::NewGuid().ToString()
$lastSubmissionAt = $null
$ragProvenanceStart = Invoke-JMindOpsApi -Method GET `
    -Path "/rag/evaluation/provenance/$knowledgeBaseId" -Body $null

foreach ($case in $cases) {
    Write-Host "[$($case.id)] 执行 RAG 端到端问答..."
    $submittedQuery = "$queryPrefix$([string]$case.query)"
    $session = Invoke-JMindOpsApi -Method POST -Path "/chat-sessions" -Body @{
        agentId = $AgentId
        title = "RagAnswerEval $($case.id)"
    }
    $sessionId = [string]$session.chatSessionId
    if ($MinimumSubmissionIntervalSeconds -gt 0 -and $null -ne $lastSubmissionAt) {
        $elapsedSinceSubmission = ((Get-Date) - $lastSubmissionAt).TotalSeconds
        $remainingDelay = $MinimumSubmissionIntervalSeconds - $elapsedSinceSubmission
        if ($remainingDelay -gt 0) {
            Start-Sleep -Milliseconds ([Math]::Ceiling($remainingDelay * 1000))
        }
    }
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $generation = Invoke-JMindOpsApi -Method POST -Path "/chat-messages" -Body @{
        requestId = [Guid]::NewGuid().ToString()
        agentId = $AgentId
        sessionId = $sessionId
        content = $submittedQuery
    }
    $lastSubmissionAt = Get-Date
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
    $stopwatch.Stop()

    $trace = Invoke-JMindOpsApi -Method GET -Path "/generation-tasks/$generationId/trace" -Body $null
    $history = Invoke-JMindOpsApi -Method GET -Path "/chat-messages/session/$sessionId" -Body $null
    $assistantMessages = @($history.chatMessages | Where-Object {
        [string]$_.role -eq "assistant" -and -not [string]::IsNullOrWhiteSpace([string]$_.content)
    })
    $finalMessage = if ($assistantMessages.Count -eq 0) { $null } else { $assistantMessages[-1] }
    $finalAnswer = if ($null -eq $finalMessage) { "" } else { [string]$finalMessage.content }
    $knowledgeMessages = @($history.chatMessages | Where-Object {
        [string]$_.role -eq "tool" -and [string]$_.metadata.toolResponse.name -eq "KnowledgeTool"
    })
    $sourceEvidence = Get-SourceEvidence $knowledgeMessages

    $results.Add([ordered]@{
        caseId = [string]$case.id
        query = [string]$case.query
        submittedQuery = $submittedQuery
        generationId = $generationId
        sessionId = $sessionId
        terminalStatus = [string]$task.status
        terminalError = if ([string]::IsNullOrWhiteSpace([string]$task.lastError)) {
            $null
        } else {
            [string]$task.lastError
        }
        route = [string]$trace.routingDecision
        latencyMs = $stopwatch.ElapsedMilliseconds
        totalTokens = [long]$trace.cumulativeTokens
        model = if ($null -eq $finalMessage) { $null } else { [string]$finalMessage.metadata.model }
        answer = [ordered]@{
            content = $finalAnswer
            length = $finalAnswer.Length
            sha256 = Get-TextSha256 $finalAnswer
        }
        retrieval = [ordered]@{
            knowledgeToolCalls = $knowledgeMessages.Count
            sourceIndexes = $sourceEvidence.sourceIndexes
            sourceDocumentIds = $sourceEvidence.sourceDocumentIds
            knowledgeContexts = @($knowledgeMessages | ForEach-Object { [string]$_.content })
        }
    })
}

$ragProvenanceEnd = Invoke-JMindOpsApi -Method GET `
    -Path "/rag/evaluation/provenance/$knowledgeBaseId" -Body $null
if ([string]$ragProvenanceStart.knowledgeBaseSnapshot -ne [string]$ragProvenanceEnd.knowledgeBaseSnapshot -or
    [string]$ragProvenanceStart.retrievalConfigHash -ne [string]$ragProvenanceEnd.retrievalConfigHash) {
    throw "评测期间知识库快照或检索配置发生变化，拒绝生成不可比较的正式报告。"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$observedPath = Join-Path $resolvedOutputDirectory "rag-answer-evaluation-$timestamp-observed.json"
$reportPath = Join-Path $resolvedOutputDirectory "rag-answer-evaluation-$timestamp-report.json"
$markdownPath = Join-Path $resolvedOutputDirectory "rag-answer-evaluation-$timestamp.md"
$gitSnapshot = Get-RepositorySnapshot $repositoryRoot
$observed = [ordered]@{
    schemaVersion = "1.0"
    datasetId = [string]$dataset.datasetId
    datasetSha256 = $datasetSha256
    datasetRole = [string]$dataset.datasetRole
    frozen = [bool]$dataset.frozen
    runType = if ($AllowExposedTestRegression) { "REGRESSION" } else { "EVALUATION" }
    exposedTestRegression = [bool]$AllowExposedTestRegression
    runId = $runId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    agentId = $AgentId
    knowledgeBaseId = $knowledgeBaseId
    datasetKnowledgeBaseId = $datasetKnowledgeBaseId
    knowledgeBaseOverrideApplied = $knowledgeBaseId -ne $datasetKnowledgeBaseId
    agentSnapshot = $agentSnapshot
    apiBaseUrl = $script:BaseUrl
    ragProvenance = $ragProvenanceEnd
    contentStorage = "RAW_LOCAL_GITIGNORED"
    polling = [ordered]@{
        initialIntervalSeconds = $PollIntervalSeconds
        maxIntervalSeconds = $MaxPollIntervalSeconds
        caseTimeoutSeconds = $CaseTimeoutSeconds
        rateLimitRetries = $RateLimitRetries
        minimumSubmissionIntervalSeconds = $MinimumSubmissionIntervalSeconds
    }
    git = [ordered]@{
        commit = $gitSnapshot.commit
        dirty = $gitSnapshot.dirty
        trackedDiffSha256 = $gitSnapshot.trackedDiffSha256
        untrackedManifestSha256 = $gitSnapshot.untrackedManifestSha256
        workspaceFingerprint = $gitSnapshot.workspaceFingerprint
    }
    results = $results
}
$observed | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $observedPath -Encoding utf8

$scorerPath = Join-Path $PSScriptRoot "score-rag-answer-eval.py"
& python $scorerPath --dataset $resolvedDatasetPath --observed $observedPath --output $reportPath --markdown $markdownPath
if ($LASTEXITCODE -ne 0) {
    throw "RAG 回答评分器执行失败，退出码 $LASTEXITCODE。"
}

Write-Host "Observed: $observedPath"
Write-Host "Report:   $reportPath"
Write-Host "Markdown: $markdownPath"
