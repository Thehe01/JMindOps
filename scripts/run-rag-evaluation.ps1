[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,

    [string]$KnowledgeBaseId,

    [string]$ApiBaseUrl = "http://localhost:3000/api",

    [string]$Token = $env:JMINDOPS_TOKEN,

    [string[]]$Modes,

    [ValidateRange(0, 20)]
    [int]$TopK = 0,

    [string]$OutputDirectory,

    [switch]$RequireFrozenTest,

    [switch]$AllowDraft,

    [switch]$AllowExposedTestRegression
)

$ErrorActionPreference = "Stop"

function Format-Number {
    param([object]$Value)
    return [string]::Format(
        [System.Globalization.CultureInfo]::InvariantCulture,
        "{0:0.00}",
        [double]$Value
    )
}

function Format-SignedNumber {
    param([double]$Value)
    return [string]::Format(
        [System.Globalization.CultureInfo]::InvariantCulture,
        "{0:+0.00;-0.00;0.00}",
        $Value
    )
}

function Format-Mrr {
    param([object]$Value)
    return [string]::Format(
        [System.Globalization.CultureInfo]::InvariantCulture,
        "{0:0.0000}",
        [double]$Value
    )
}

function Format-SignedMrr {
    param([double]$Value)
    return [string]::Format(
        [System.Globalization.CultureInfo]::InvariantCulture,
        "{0:+0.0000;-0.0000;0.0000}",
        $Value
    )
}

function Escape-Markdown {
    param([string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return $Value.Replace("|", "\|").
        Replace([string][char]13, " ").
        Replace([string][char]10, " ")
}

function Assert-Close {
    param([string]$Label, [double]$Actual, [double]$Expected, [double]$Tolerance)
    if ([Math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "评测结果完整性校验失败: $Label，接口值=$Actual，明细复算值=$Expected"
    }
}

function Round-LikeJava {
    param([double]$Value, [int]$Digits)
    # 后端聚合使用 Java Math.round（非负指标的中点向上舍入）。
    # PowerShell/.NET 默认采用 ToEven，0.9125 会变成 0.912，导致完整性误报。
    return [Math]::Round($Value, $Digits, [MidpointRounding]::AwayFromZero)
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

function Assert-ResultIntegrity {
    param([object]$Result)
    $status = [string]$Result.status
    if ([string]::IsNullOrWhiteSpace($status)) {
        $status = if ($Result.successful) { "COMPLETED" } else { "FAILED" }
    }
    if ($status -ne "COMPLETED") {
        return
    }

    $details = @($Result.details)
    if ($details.Count -ne [int]$Result.totalQueries) {
        throw "评测结果完整性校验失败: $($Result.mode) 明细数量与 totalQueries 不一致"
    }
    $positiveDetails = @($details | Where-Object { -not $_.expectedNoAnswer })
    $noAnswerDetails = @($details | Where-Object { $_.expectedNoAnswer })
    $hitCount = @($positiveDetails | Where-Object { $_.hit }).Count
    if ($hitCount -ne [int]$Result.hitCount) {
        throw "评测结果完整性校验失败: $($Result.mode) hitCount 不一致"
    }
    $hitRate = if ($positiveDetails.Count -eq 0) { 0 } else { Round-LikeJava (100.0 * $hitCount / $positiveDetails.Count) 2 }
    $recall = if ($positiveDetails.Count -eq 0) { 0 } else {
        Round-LikeJava (($positiveDetails | ForEach-Object {
            if ([int]$_.expectedCount -eq 0) { 0 } else { 100.0 * [int]$_.matchedExpectedCount / [int]$_.expectedCount }
        } | Measure-Object -Average).Average) 2
    }
    $precision = if ($positiveDetails.Count -eq 0) { 0 } else {
        Round-LikeJava (($positiveDetails.precisionAtK | Measure-Object -Average).Average) 2
    }
    $mrr = if ($positiveDetails.Count -eq 0) { 0 } else {
        Round-LikeJava (($positiveDetails.reciprocalRank | Measure-Object -Average).Average) 3
    }
    Assert-Close "$($Result.mode) HitRate" ([double]$Result.hitRate) $hitRate 0.01
    Assert-Close "$($Result.mode) Recall" ([double]$Result.recallAtK) $recall 0.01
    Assert-Close "$($Result.mode) Precision" ([double]$Result.precisionAtK) $precision 0.02
    Assert-Close "$($Result.mode) MRR" ([double]$Result.mrr) $mrr 0.0006
    if ($positiveDetails.Count -ne [int]$Result.positiveQueryCount) {
        throw "评测结果完整性校验失败: $($Result.mode) positiveQueryCount 不一致"
    }
    $noAnswerCorrect = @($noAnswerDetails | Where-Object { $_.noAnswerCorrect }).Count
    if ($noAnswerDetails.Count -ne [int]$Result.noAnswerQueryCount -or
        $noAnswerCorrect -ne [int]$Result.noAnswerCorrectCount) {
        throw "评测结果完整性校验失败: $($Result.mode) no-answer 计数不一致"
    }
    $noAnswerAccuracy = if ($noAnswerDetails.Count -eq 0) { 0 } else {
        Round-LikeJava (100.0 * $noAnswerCorrect / $noAnswerDetails.Count) 2
    }
    Assert-Close "$($Result.mode) NoAnswerAccuracy" ([double]$Result.noAnswerAccuracy) $noAnswerAccuracy 0.01

    if ($details.Count -gt 0) {
        $averageLatency = Round-LikeJava (($details.latencyMs | Measure-Object -Average).Average) 2
        $averageReturned = Round-LikeJava (($details | ForEach-Object {
            @($_.retrievedSourceIds).Count
        } | Measure-Object -Average).Average) 2
        Assert-Close "$($Result.mode) averageLatencyMs" ([double]$Result.averageLatencyMs) $averageLatency 0.01
        Assert-Close "$($Result.mode) averageReturnedSources" ([double]$Result.averageReturnedSources) $averageReturned 0.01
        $latencies = @($details.latencyMs | ForEach-Object { [long]$_ } | Sort-Object)
        $p50 = $latencies[[Math]::Ceiling(0.50 * $latencies.Count) - 1]
        $p95 = $latencies[[Math]::Ceiling(0.95 * $latencies.Count) - 1]
        if ($p50 -ne [long]$Result.p50LatencyMs -or $p95 -ne [long]$Result.p95LatencyMs) {
            throw "评测结果完整性校验失败: $($Result.mode) P50/P95 与明细不一致"
        }
    }
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
$dataset = Get-Content -Raw -LiteralPath $resolvedDatasetPath | ConvertFrom-Json
$datasetHash = (Get-FileHash -LiteralPath $resolvedDatasetPath -Algorithm SHA256).Hash.ToLowerInvariant()

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 JWT。请设置 JMINDOPS_TOKEN，或使用 -Token 参数。"
}
$datasetKnowledgeBaseId = [string]$dataset.kbId
$effectiveKnowledgeBaseId = if ([string]::IsNullOrWhiteSpace($KnowledgeBaseId)) {
    $datasetKnowledgeBaseId
} else {
    $KnowledgeBaseId
}
if ([string]::IsNullOrWhiteSpace($effectiveKnowledgeBaseId) -or
    $effectiveKnowledgeBaseId -like "REPLACE_*") {
    throw "题集未绑定知识库；请通过 -KnowledgeBaseId 传入本轮已复核知识库的 UUID。"
}
try {
    $effectiveKnowledgeBaseId = [Guid]::Parse($effectiveKnowledgeBaseId).ToString()
} catch {
    throw "KnowledgeBaseId 必须是标准 UUID。"
}
if ($null -eq $dataset.testCases -or @($dataset.testCases).Count -eq 0) {
    throw "题集至少需要一个 testCase。"
}
$annotationStatus = [string]$dataset.annotationStatus
if ([string]::IsNullOrWhiteSpace($annotationStatus)) {
    $annotationStatus = "DRAFT"
}
if ($annotationStatus -ne "REVIEWED" -and -not $AllowDraft) {
    throw "题集 annotationStatus=$annotationStatus。正式评测必须人工复核后标记为 REVIEWED；临时试跑请显式加 -AllowDraft。"
}

$selectedModes = @($Modes | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($selectedModes.Count -eq 0) {
    $selectedModes = @($dataset.modes)
}
if ($selectedModes.Count -eq 0) {
    $selectedModes = @("VECTOR", "HYBRID_RRF")
}

$effectiveTopK = $TopK
if ($effectiveTopK -eq 0) {
    $effectiveTopK = [int]$dataset.topK
}
if ($effectiveTopK -lt 1 -or $effectiveTopK -gt 20) {
    throw "topK 必须在 1 到 20 之间。"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force

$retrievalCases = @($dataset.testCases | ForEach-Object {
    $derivedSourceKey = [string]$_.expectedSourceKey
    $sourcePaths = @($_.sourcePaths | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $derivedSourceKeys = @($_.expectedSourceKeys | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($derivedSourceKeys.Count -eq 0 -and $sourcePaths.Count -gt 0) {
        $derivedSourceKeys = @($sourcePaths | ForEach-Object {
            [System.IO.Path]::GetFileName([string]$_)
        } | Select-Object -Unique)
    }
    if ([string]::IsNullOrWhiteSpace($derivedSourceKey) -and $derivedSourceKeys.Count -gt 0) {
        $derivedSourceKey = [string]$derivedSourceKeys[0]
    }
    $derivedExpectedKeywords = @($_.expectedKeywords | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($derivedExpectedKeywords.Count -eq 0 -and
        -not [string]::IsNullOrWhiteSpace([string]$_.expectedKeyword)) {
        $derivedExpectedKeywords = @([string]$_.expectedKeyword)
    }
    if ($derivedExpectedKeywords.Count -eq 0) {
        # 回答层的 expectedConcepts 是别名组；检索接口只接受词项真值。
        # 第一别名已由题集校验器确认存在于第一主证据，故用它代表该概念，
        # 避免把同一概念的多个同义词错误计成多个必须召回的事实。
        $derivedExpectedKeywords = @($_.expectedConcepts | ForEach-Object {
            @($_.anyOf | Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_)
            } | Select-Object -First 1)
        } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    }
    [ordered]@{
        query = $_.query
        expectedDocumentId = $_.expectedDocumentId
        expectedSourceKey = $derivedSourceKey
        expectedKeyword = $_.expectedKeyword
        expectedDocumentIds = $_.expectedDocumentIds
        # 多个 key 表示可接受的替代来源，命中任意一个即满足来源真值。
        expectedSourceKeys = $derivedSourceKeys
        expectedKeywords = $derivedExpectedKeywords
        expectedNoAnswer = $_.expectedNoAnswer
    }
})
$requestBody = @{
    kbId = $effectiveKnowledgeBaseId
    modes = $selectedModes
    topK = $effectiveTopK
    testCases = $retrievalCases
}
$baseUrl = $ApiBaseUrl.TrimEnd("/")
$invokeParameters = @{
    Method = "Post"
    Uri = "$baseUrl/rag/evaluate/compare"
    Headers = @{ Authorization = "Bearer $Token" }
    ContentType = "application/json; charset=utf-8"
    Body = ($requestBody | ConvertTo-Json -Depth 20)
}
$response = Invoke-RestMethod @invokeParameters

if ($null -eq $response -or [int]$response.code -ne 200 -or $null -eq $response.data) {
    $message = if ($null -ne $response) { [string]$response.message } else { "empty response" }
    throw "评测接口调用失败: $message"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $resolvedOutputDirectory "rag-evaluation-$timestamp.json"
$markdownPath = Join-Path $resolvedOutputDirectory "rag-evaluation-$timestamp.md"

$gitSnapshot = Get-RepositorySnapshot $repositoryRoot
$gitCommit = $gitSnapshot.commit
$gitDirty = $gitSnapshot.dirty
$artifact = [ordered]@{
    artifactVersion = 1
    run = [ordered]@{
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        datasetPath = $resolvedDatasetPath
        datasetSha256 = $datasetHash
        datasetKnowledgeBaseId = $datasetKnowledgeBaseId
        effectiveKnowledgeBaseId = $effectiveKnowledgeBaseId
        knowledgeBaseOverrideApplied = $effectiveKnowledgeBaseId -ne $datasetKnowledgeBaseId
        annotationStatus = $annotationStatus
        datasetRole = [string]$dataset.datasetRole
        frozen = [bool]$dataset.frozen
        runType = if ($AllowExposedTestRegression) { "REGRESSION" } else { "EVALUATION" }
        exposedTestRegression = [bool]$AllowExposedTestRegression
        apiBaseUrl = $baseUrl
        requestedModes = $selectedModes
        topK = $effectiveTopK
        gitCommit = $gitCommit
        gitDirty = $gitDirty
        trackedDiffSha256 = $gitSnapshot.trackedDiffSha256
        untrackedManifestSha256 = $gitSnapshot.untrackedManifestSha256
        workspaceFingerprint = $gitSnapshot.workspaceFingerprint
    }
    result = $response.data
}
$results = @($response.data.results)
$results | ForEach-Object { Assert-ResultIntegrity $_ }
$artifact | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath $jsonPath -Encoding utf8
$baseline = $results |
    Where-Object { $_.mode -eq "VECTOR" -and $_.successful } |
    Select-Object -First 1

$report = [System.Collections.Generic.List[string]]::new()
$report.Add("# JMindOps RAG 评测报告")
$report.Add("")
$report.Add("- 题集：$(Escape-Markdown ([string]$dataset.name))")
$report.Add("- 题集 SHA-256：$datasetHash")
$report.Add("- 标注状态：$annotationStatus")
$report.Add("- 题集角色：$($dataset.datasetRole)（frozen=$([bool]$dataset.frozen)）")
$report.Add("- 评测 ID：$($response.data.evaluationId)")
$report.Add("- 知识库：$($response.data.knowledgeBaseId)")
$report.Add("- 知识库快照：$($response.data.knowledgeBaseSnapshot)")
$report.Add("- Embedding 模型：$($response.data.embeddingModel)")
$report.Add("- Reranker Provider：$($response.data.rerankerProvider)")
$report.Add("- Reranker 模型：$($response.data.rerankerModel)")
$report.Add("- 检索流水线版本：$($response.data.retrievalPipelineVersion)")
$report.Add("- 检索配置：$(Escape-Markdown (($response.data.retrievalConfig | ConvertTo-Json -Compress -Depth 5)))")
$report.Add("- 检索配置 Hash：$($response.data.retrievalConfigHash)")
$report.Add("- 相关性标签类型：$($response.data.relevanceLabelType)")
$report.Add("- Git：$gitCommit（dirty=$gitDirty）")
$report.Add("- 问题数：$($response.data.totalQueries)")
$report.Add("- Top-K：$($response.data.topK)")
$report.Add("- 生成时间：$(Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")")
if ($annotationStatus -ne "REVIEWED") {
    $report.Add("")
    $report.Add("> [!WARNING]")
    $report.Add("> 本次使用 DRAFT 题集，仅用于连通性试跑，不得作为简历或正式实验结论。")
}
$report.Add("")
$report.Add("## 模式对照")
$report.Add("")
$report.Add("| 模式 | 状态 | HitRate@K | Expectation Recall@K | Proxy Precision@K | MRR | No-answer Acc | P50(ms) | P95(ms) | HitRate Δ vs Vector | MRR Δ vs Vector |")
$report.Add("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")

foreach ($result in $results) {
    $status = [string]$result.status
    if ([string]::IsNullOrWhiteSpace($status)) {
        $status = if ($result.successful) { "COMPLETED" } else { "FAILED" }
    }
    if ($status -ne "COMPLETED") {
        $errorText = Escape-Markdown ([string]$result.error)
        $report.Add("| $($result.mode) | ${status}: $errorText | - | - | - | - | - | - | - | - | - |")
        continue
    }

    $hitDelta = "-"
    $mrrDelta = "-"
    if ($null -ne $baseline) {
        $hitDelta = Format-SignedNumber ([double]$result.hitRate - [double]$baseline.hitRate)
        $mrrDelta = Format-SignedMrr ([double]$result.mrr - [double]$baseline.mrr)
    }

    $report.Add(
        "| $($result.mode) | OK | $(Format-Number $result.hitRate)% | " +
        "$(Format-Number $result.recallAtK)% | $(Format-Number $result.precisionAtK)% | " +
        "$(Format-Mrr $result.mrr) | $(Format-Number $result.noAnswerAccuracy)% | " +
        "$($result.p50LatencyMs) | $($result.p95LatencyMs) | " +
        "$hitDelta pp | $mrrDelta |"
    )
}

$report.Add("")
$report.Add("## Bad Cases")

foreach ($result in $results) {
    $report.Add("")
    $report.Add("### $($result.mode)")
    $report.Add("")
    $status = [string]$result.status
    if ([string]::IsNullOrWhiteSpace($status)) {
        $status = if ($result.successful) { "COMPLETED" } else { "FAILED" }
    }
    if ($status -ne "COMPLETED") {
        $report.Add("- 模式未产生分数（$status）：$(Escape-Markdown ([string]$result.error))")
        continue
    }

    $misses = @($result.details | Where-Object {
        (-not $_.expectedNoAnswer -and -not $_.hit) -or
        ($_.expectedNoAnswer -and -not $_.noAnswerCorrect)
    } | Select-Object -First 10)
    if ($misses.Count -eq 0) {
        $report.Add("- 当前题集没有未命中样本。")
        continue
    }

    $report.Add("| Query | 类型 | Expected docs/source keys | Expected keywords | Retrieved IDs/source keys | Latency(ms) |")
    $report.Add("| --- | --- | --- | --- | --- | ---: |")
    foreach ($miss in $misses) {
        $query = Escape-Markdown ([string]$miss.query)
        $expectedDocs = Escape-Markdown ((@($miss.expectedDocumentIds) + @($miss.expectedSourceKeys)) -join ", ")
        $expectedKeywords = Escape-Markdown (@($miss.expectedKeywords) -join ", ")
        $sourceIds = Escape-Markdown ((@($miss.retrievedSourceIds) + @($miss.retrievedSourceKeys)) -join ", ")
        $caseType = if ($miss.expectedNoAnswer) { "应无召回" } else { "应命中" }
        $report.Add("| $query | $caseType | $expectedDocs | $expectedKeywords | $sourceIds | $($miss.latencyMs) |")
    }
}

$report.Add("")
$report.Add("## 解释边界")
$report.Add("")
$report.Add("- 本报告评估检索层，不代表最终答案正确性或事实忠实度。")
$report.Add("- relevanceLabelType=KEYWORD_PROXY 时，Hit/Recall/Precision/MRR 基于词项命中，只能作为业务回归代理；标准文档相关性结论应使用 expectedDocumentIds 或 T2Ranking qrels。")
$report.Add("- HYBRID_RERANK 未完成会按 NOT_CONFIGURED/FAILED 单独标记，不会冒充为 Reranker 成绩。")
$report.Add("- provider=none 时 HYBRID_RERANK 标记为 NOT_CONFIGURED；这表示未测试，不是 0 分。")
$report.Add("- 正式对比应固定知识库快照、题集、Embedding 模型和 Top-K，并在预热后重复运行。")

$report | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Host "评测完成"
Write-Host "JSON: $jsonPath"
Write-Host "Markdown: $markdownPath"
