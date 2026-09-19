[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PreparedDirectory,

    [string]$ApiBaseUrl = "http://localhost:8080/api",

    [string]$Token = $env:JMINDOPS_TOKEN,

    [string]$KnowledgeBaseId,

    [string]$KnowledgeBaseName,

    [ValidateRange(1, 10)]
    [int]$MaxAttempts = 3
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "该脚本需要 PowerShell 7+，因为文档上传使用 Invoke-RestMethod -Form。"
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 JWT。请设置 JMINDOPS_TOKEN，或传入 -Token。"
}

$prepared = (Resolve-Path -LiteralPath $PreparedDirectory).Path
$manifestPath = Join-Path $prepared "manifest.json"
$datasetPath = Join-Path $prepared "evaluation.dataset.json"
$corpusDirectory = Join-Path $prepared "corpus"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "缺少 T2Ranking manifest.json：$manifestPath"
}
if (-not (Test-Path -LiteralPath $datasetPath -PathType Leaf)) {
    throw "缺少 evaluation.dataset.json：$datasetPath"
}
if (-not (Test-Path -LiteralPath $corpusDirectory -PathType Container)) {
    throw "缺少 corpus 目录：$corpusDirectory"
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ([string]$manifest.benchmark -ne "T2Ranking") {
    throw "manifest benchmark 不是 T2Ranking"
}
$shards = @($manifest.shards)
if ($shards.Count -eq 0) {
    throw "manifest 中没有 corpus shard"
}

$baseUrl = $ApiBaseUrl.TrimEnd("/")
$headers = @{ Authorization = "Bearer $Token" }

if ([string]::IsNullOrWhiteSpace($KnowledgeBaseId)) {
    if ([string]::IsNullOrWhiteSpace($KnowledgeBaseName)) {
        $KnowledgeBaseName = "T2Ranking-$($manifest.source.split)-$($manifest.parameters.queryCount)q-$($manifest.parameters.actualCorpusSize)c"
    }
    $body = @{
        name = $KnowledgeBaseName
        description = "T2Ranking 固定抽样检索评测；fingerprint=$($manifest.corpusFingerprint)"
    } | ConvertTo-Json -Compress
    $created = Invoke-RestMethod -Method Post -Uri "$baseUrl/knowledge-bases" -Headers $headers -ContentType "application/json; charset=utf-8" -Body $body
    if ($null -eq $created -or [int]$created.code -ne 200) {
        throw "创建 T2Ranking 知识库失败：$($created.message)"
    }
    $KnowledgeBaseId = [string]$created.data.knowledgeBaseId
}

function Upload-Shard {
    param(
        [string]$Path,
        [string]$KbId
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            return Invoke-RestMethod -Method Post -Uri "$baseUrl/documents/upload" -Headers $headers -Form @{
                kbId = $KbId
                file = Get-Item -LiteralPath $Path
            }
        } catch {
            $statusCode = 0
            if ($null -ne $_.Exception.Response) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            if ($statusCode -ne 429 -or $attempt -eq $MaxAttempts) {
                throw
            }
            $retryAfter = 60
            $retryHeader = $_.Exception.Response.Headers.RetryAfter
            if ($null -ne $retryHeader -and $null -ne $retryHeader.Delta) {
                $retryAfter = [Math]::Max(1, [int][Math]::Ceiling($retryHeader.Delta.TotalSeconds))
            }
            Write-Warning "上传触发限流，等待 $retryAfter 秒后重试：$([IO.Path]::GetFileName($Path))"
            Start-Sleep -Seconds $retryAfter
        }
    }
}

$uploaded = [System.Collections.Generic.List[object]]::new()
foreach ($shard in $shards) {
    $filename = [string]$shard.filename
    $path = Join-Path $corpusDirectory $filename
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "缺少 corpus shard：$path"
    }
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne ([string]$shard.sha256).ToLowerInvariant()) {
        throw "corpus shard 哈希不一致：$filename"
    }
    $response = Upload-Shard -Path $path -KbId $KnowledgeBaseId
    if ($null -eq $response -or [int]$response.code -ne 200) {
        throw "上传失败：$filename，$($response.message)"
    }
    $uploaded.Add([ordered]@{
        filename = $filename
        documentId = [string]$response.data.documentId
        indexAction = [string]$response.data.indexAction
        chunkCount = [int]$response.data.chunkCount
        embeddedChunkCount = [int]$response.data.embeddedChunkCount
    })
    Write-Host "已导入 $filename：chunks=$($response.data.chunkCount)，embedded=$($response.data.embeddedChunkCount)"
}

$dataset = Get-Content -Raw -LiteralPath $datasetPath | ConvertFrom-Json
$dataset.kbId = $KnowledgeBaseId
$readyDatasetPath = Join-Path $prepared "evaluation.ready.json"
$dataset | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $readyDatasetPath -Encoding utf8

$importArtifact = [ordered]@{
    importedAt = (Get-Date).ToUniversalTime().ToString("o")
    knowledgeBaseId = $KnowledgeBaseId
    corpusFingerprint = [string]$manifest.corpusFingerprint
    shards = $uploaded
}
$importPath = Join-Path $prepared "import-result.json"
$importArtifact | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $importPath -Encoding utf8

Write-Host "T2Ranking 导入完成"
Write-Host "Knowledge base: $KnowledgeBaseId"
Write-Host "Ready dataset: $readyDatasetPath"
Write-Host "Import artifact: $importPath"
