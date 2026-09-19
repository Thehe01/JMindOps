[CmdletBinding()]
param(
    [string]$DatasetPath = "evaluation-data/agent-eval-v1.json",

    [Parameter(Mandatory = $true)]
    [string[]]$ObservedPaths,

    [string]$OutputDirectory,

    [switch]$AllowMixedAgentIds,

    [switch]$AllowMixedAgentSnapshots,

    [switch]$AllowMixedApiBaseUrls
)

$ErrorActionPreference = "Stop"

function Get-TextSha256 {
    param([AllowEmptyString()][string]$Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

$resolvedDatasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
$dataset = Get-Content -Raw -LiteralPath $resolvedDatasetPath | ConvertFrom-Json
$currentDatasetHash = (Get-FileHash -LiteralPath $resolvedDatasetPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force

$resultsByCaseId = @{}
$sourceRuns = [System.Collections.Generic.List[object]]::new()
$replacements = [System.Collections.Generic.List[object]]::new()
$agentIds = [System.Collections.Generic.HashSet[string]]::new()
$apiBaseUrls = [System.Collections.Generic.HashSet[string]]::new()
$datasetHashes = [System.Collections.Generic.HashSet[string]]::new()
$agentSnapshotHashes = [System.Collections.Generic.HashSet[string]]::new()
$agentSnapshotsByHash = @{}
$missingAgentSnapshots = 0
foreach ($path in $ObservedPaths) {
    $resolvedPath = (Resolve-Path -LiteralPath $path).Path
    $observed = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
    if ([string]$observed.datasetId -ne [string]$dataset.datasetId) {
        throw "datasetId 不一致: $resolvedPath"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$observed.agentId)) {
        $null = $agentIds.Add([string]$observed.agentId)
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$observed.apiBaseUrl)) {
        $null = $apiBaseUrls.Add(([string]$observed.apiBaseUrl).TrimEnd("/"))
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$observed.datasetSha256)) {
        $null = $datasetHashes.Add([string]$observed.datasetSha256)
    }
    $agentSnapshotHash = $null
    if ($null -eq $observed.agentSnapshot) {
        $missingAgentSnapshots++
    } else {
        $agentSnapshotJson = $observed.agentSnapshot | ConvertTo-Json -Depth 20 -Compress
        $agentSnapshotHash = Get-TextSha256 $agentSnapshotJson
        $null = $agentSnapshotHashes.Add($agentSnapshotHash)
        $agentSnapshotsByHash[$agentSnapshotHash] = $observed.agentSnapshot
    }
    $sourceRuns.Add([ordered]@{
        path = $resolvedPath
        runId = [string]$observed.runId
        generatedAt = [string]$observed.generatedAt
        agentId = [string]$observed.agentId
        apiBaseUrl = [string]$observed.apiBaseUrl
        datasetSha256 = [string]$observed.datasetSha256
        agentSnapshotSha256 = $agentSnapshotHash
    })
    $seenInSource = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($result in @($observed.results)) {
        $caseId = [string]$result.caseId
        if ([string]::IsNullOrWhiteSpace($caseId) -or -not $seenInSource.Add($caseId)) {
            throw "同一 observed 文件包含重复或空 caseId: $resolvedPath"
        }
        if ($caseId -notin @($dataset.cases.id)) {
            throw "observed 文件包含题集之外的 caseId=${caseId}: $resolvedPath"
        }
        # 后传入的分片覆盖同 case 的早期结果，便于合并修复后的回归分片。
        if ($resultsByCaseId.ContainsKey($caseId)) {
            $replacements.Add([ordered]@{
                caseId = $caseId
                replacementSource = $resolvedPath
            })
        }
        $resultsByCaseId[$caseId] = $result
    }
}
if ($agentIds.Count -gt 1 -and -not $AllowMixedAgentIds) {
    throw "检测到多个 AgentId，默认禁止拼成一个总分；如只需回归证据请显式使用 -AllowMixedAgentIds。"
}
$mixedAgentSnapshots = $agentSnapshotHashes.Count -gt 1 -or
    ($agentSnapshotHashes.Count -gt 0 -and $missingAgentSnapshots -gt 0)
if ($mixedAgentSnapshots -and -not $AllowMixedAgentSnapshots) {
    throw "Agent 配置快照不一致或部分缺失，默认禁止拼成一个总分；如仅用于诊断请显式使用 -AllowMixedAgentSnapshots。"
}
if ($apiBaseUrls.Count -gt 1 -and -not $AllowMixedApiBaseUrls) {
    throw "检测到多个 ApiBaseUrl，默认禁止拼成一个总分；如确有需要请显式使用 -AllowMixedApiBaseUrls。"
}
if ($datasetHashes.Count -gt 1) {
    throw "source run 的 datasetSha256 不一致，不能合并。"
}
if ($datasetHashes.Count -eq 1 -and @($datasetHashes)[0] -ne $currentDatasetHash) {
    throw "source run 的 datasetSha256 与当前题集不一致，不能按新题集重新解释旧结果。"
}

$orderedResults = [System.Collections.Generic.List[object]]::new()
foreach ($case in @($dataset.cases)) {
    $caseId = [string]$case.id
    if ($resultsByCaseId.ContainsKey($caseId)) {
        $orderedResults.Add($resultsByCaseId[$caseId])
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$observedPath = Join-Path $resolvedOutputDirectory "agent-evaluation-$timestamp-combined-observed.json"
$reportPath = Join-Path $resolvedOutputDirectory "agent-evaluation-$timestamp-combined-report.json"
$combined = [ordered]@{
    datasetId = [string]$dataset.datasetId
    datasetSha256 = $currentDatasetHash
    runId = [Guid]::NewGuid().ToString()
    evidenceType = "COMPOSITE"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    agentId = if ($agentIds.Count -eq 1) { @($agentIds)[0] } else { $null }
    agentSnapshot = if ($agentSnapshotHashes.Count -eq 1 -and $missingAgentSnapshots -eq 0) {
        $agentSnapshotsByHash[@($agentSnapshotHashes)[0]]
    } else { $null }
    apiBaseUrl = if ($apiBaseUrls.Count -eq 1) { @($apiBaseUrls)[0] } else { $null }
    mixedAgentIds = $agentIds.Count -gt 1
    mixedAgentSnapshots = $mixedAgentSnapshots
    mixedApiBaseUrls = $apiBaseUrls.Count -gt 1
    sourceRuns = $sourceRuns
    replacements = $replacements
    results = $orderedResults
}
$combined | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $observedPath -Encoding utf8

$scorerPath = Join-Path $PSScriptRoot "score-agent-eval.py"
& python $scorerPath --dataset $resolvedDatasetPath --observed $observedPath --output $reportPath
if ($LASTEXITCODE -ne 0) {
    throw "AgentEval 评分器执行失败，退出码 $LASTEXITCODE。"
}

Write-Host "Combined observed: $observedPath"
Write-Host "Combined report:   $reportPath"
