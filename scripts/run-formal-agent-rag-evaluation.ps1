[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AgentId,

    [string]$RagAgentId,

    [Parameter(Mandatory = $true)]
    [string]$KnowledgeBaseId,

    [string]$AgentDatasetPath = "evaluation-data/agent-eval-v2-test.json",

    [string]$RagDatasetPath = "evaluation-data/rag-test-v1.json",

    [string]$ApiBaseUrl = "http://127.0.0.1:8081/api",

    [string]$Token = $env:JMINDOPS_TOKEN,

    [string]$OutputDirectory,

    [ValidateRange(10, 1800)]
    [int]$CaseTimeoutSeconds = 300,

    [ValidateRange(1, 30)]
    [int]$PollIntervalSeconds = 3,

    [ValidateRange(2, 120)]
    [int]$MaxPollIntervalSeconds = 30,

    [ValidateRange(3, 10)]
    [int]$RagRepeatCount = 3,

    [ValidateRange(0, 3)]
    [int]$RagWarmupRuns = 1
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 JWT。请设置 JMINDOPS_TOKEN，或使用 -Token 参数。"
}
if ([string]::IsNullOrWhiteSpace($RagAgentId)) {
    $RagAgentId = $AgentId
}
if ($MaxPollIntervalSeconds -lt $PollIntervalSeconds) {
    throw "MaxPollIntervalSeconds 不能小于 PollIntervalSeconds。"
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot "evaluation-results"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force

function Invoke-Phase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )
    $startedAt = Get-Date
    Write-Host "[$($startedAt.ToString('o'))] START $Name"
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name 失败，退出码 $LASTEXITCODE。"
    }
    $completedAt = Get-Date
    Write-Host "[$($completedAt.ToString('o'))] DONE  $Name duration=$([Math]::Round(($completedAt - $startedAt).TotalSeconds, 1))s"
}

Set-Location -LiteralPath $repositoryRoot
Write-Host "Formal evaluation started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
Write-Host "AgentId=$AgentId"
Write-Host "RagAgentId=$RagAgentId"
Write-Host "KnowledgeBaseId=$KnowledgeBaseId"
Write-Host "ApiBaseUrl=$ApiBaseUrl"
Write-Host "Polling=${PollIntervalSeconds}s -> ${MaxPollIntervalSeconds}s (dynamic backoff)"

Invoke-Phase "AgentEval full" {
    & (Join-Path $PSScriptRoot "run-agent-evaluation.ps1") `
        -DatasetPath $AgentDatasetPath `
        -AgentId $AgentId `
        -ApiBaseUrl $ApiBaseUrl `
        -Token $Token `
        -OutputDirectory $resolvedOutputDirectory `
        -CaseTimeoutSeconds $CaseTimeoutSeconds `
        -PollIntervalSeconds $PollIntervalSeconds `
        -MaxPollIntervalSeconds $MaxPollIntervalSeconds
}

Invoke-Phase "RAG formal (warmup + repeated retrieval and answer)" {
    & (Join-Path $PSScriptRoot "run-formal-rag-evaluation.ps1") `
        -DatasetPath $RagDatasetPath `
        -AgentId $RagAgentId `
        -KnowledgeBaseId $KnowledgeBaseId `
        -ApiBaseUrl $ApiBaseUrl `
        -Token $Token `
        -OutputDirectory $resolvedOutputDirectory `
        -RepeatCount $RagRepeatCount `
        -WarmupRuns $RagWarmupRuns `
        -CaseTimeoutSeconds $CaseTimeoutSeconds `
        -PollIntervalSeconds $PollIntervalSeconds `
        -MaxPollIntervalSeconds $MaxPollIntervalSeconds
}

Write-Host "Formal evaluation completed: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
