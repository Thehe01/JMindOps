[CmdletBinding()]
param(
    [string]$DatasetPath = "evaluation-data/rag-test-v1.json",
    [Parameter(Mandatory = $true)][string]$AgentId,
    [Parameter(Mandatory = $true)][string]$KnowledgeBaseId,
    [string]$ApiBaseUrl = "http://127.0.0.1:8081/api",
    [string]$Token = $env:JMINDOPS_TOKEN,
    [string]$OutputDirectory,
    [ValidateRange(3, 10)][int]$RepeatCount = 3,
    [ValidateRange(0, 3)][int]$WarmupRuns = 1,
    [ValidateRange(10, 1800)][int]$CaseTimeoutSeconds = 300,
    [ValidateRange(1, 30)][int]$PollIntervalSeconds = 3,
    [ValidateRange(2, 120)][int]$MaxPollIntervalSeconds = 30
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force

function Get-NewArtifact {
    param([string]$Filter, [datetime]$StartedAt)
    $artifact = Get-ChildItem -LiteralPath $resolvedOutputDirectory -Filter $Filter |
        Where-Object { $_.LastWriteTime -ge $StartedAt.AddSeconds(-1) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $artifact) { throw "未找到本轮产物: $Filter" }
    return $artifact.FullName
}

Set-Location -LiteralPath $repositoryRoot
$datasetFullPath = (Resolve-Path -LiteralPath $DatasetPath).Path
$validatorArguments = @(
    (Join-Path $PSScriptRoot "validate-rag-eval-dataset.py"),
    "--dataset", $datasetFullPath,
    "--repository-root", $repositoryRoot,
    "--require-frozen-test"
)
& python @validatorArguments
if ($LASTEXITCODE -ne 0) { throw "冻结 RAG 测试集校验失败" }

for ($warmup = 1; $warmup -le $WarmupRuns; $warmup++) {
    Write-Host "[warmup $warmup/$WarmupRuns] RAG retrieval"
    & (Join-Path $PSScriptRoot "run-rag-evaluation.ps1") `
        -DatasetPath $datasetFullPath -KnowledgeBaseId $KnowledgeBaseId `
        -ApiBaseUrl $ApiBaseUrl -Token $Token `
        -Modes @("VECTOR", "HYBRID_RRF", "HYBRID_RERANK") `
        -OutputDirectory $resolvedOutputDirectory -RequireFrozenTest
    if ($LASTEXITCODE -ne 0) { throw "RAG 预热失败" }
}

$retrievalArtifacts = [System.Collections.Generic.List[string]]::new()
$answerArtifacts = [System.Collections.Generic.List[string]]::new()
for ($run = 1; $run -le $RepeatCount; $run++) {
    Write-Host "[formal $run/$RepeatCount] RAG retrieval"
    $retrievalStarted = Get-Date
    & (Join-Path $PSScriptRoot "run-rag-evaluation.ps1") `
        -DatasetPath $datasetFullPath -KnowledgeBaseId $KnowledgeBaseId `
        -ApiBaseUrl $ApiBaseUrl -Token $Token `
        -Modes @("VECTOR", "HYBRID_RRF", "HYBRID_RERANK") `
        -OutputDirectory $resolvedOutputDirectory -RequireFrozenTest
    if ($LASTEXITCODE -ne 0) { throw "第 $run 次 RAG 检索评测失败" }
    $retrievalArtifacts.Add((Get-NewArtifact "rag-evaluation-*.json" $retrievalStarted))

    Write-Host "[formal $run/$RepeatCount] RAG answer"
    $answerStarted = Get-Date
    & (Join-Path $PSScriptRoot "run-rag-answer-evaluation.ps1") `
        -DatasetPath $datasetFullPath -AgentId $AgentId -KnowledgeBaseId $KnowledgeBaseId `
        -ApiBaseUrl $ApiBaseUrl -Token $Token -OutputDirectory $resolvedOutputDirectory `
        -CaseTimeoutSeconds $CaseTimeoutSeconds -PollIntervalSeconds $PollIntervalSeconds `
        -MaxPollIntervalSeconds $MaxPollIntervalSeconds -RequireFrozenTest
    if ($LASTEXITCODE -ne 0) { throw "第 $run 次 RAG 回答评测失败" }
    $answerArtifacts.Add((Get-NewArtifact "rag-answer-evaluation-*-report.json" $answerStarted))
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryJson = Join-Path $resolvedOutputDirectory "rag-formal-summary-$timestamp.json"
$summaryMarkdown = Join-Path $resolvedOutputDirectory "rag-formal-summary-$timestamp.md"
$summaryArguments = @((Join-Path $PSScriptRoot "summarize-rag-evaluation-runs.py"), "--retrieval")
$summaryArguments += @($retrievalArtifacts)
$summaryArguments += "--answer"
$summaryArguments += @($answerArtifacts)
$summaryArguments += @("--output", $summaryJson, "--markdown", $summaryMarkdown)
& python @summaryArguments
if ($LASTEXITCODE -ne 0) { throw "RAG 重复运行汇总失败" }

Write-Host "RAG formal evaluation completed"
Write-Host "Summary JSON: $summaryJson"
Write-Host "Summary Markdown: $summaryMarkdown"
