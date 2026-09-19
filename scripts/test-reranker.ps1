[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8001",
    [string]$Model = "BAAI/bge-reranker-v2-m3",
    [int]$TopN = 2,
    [ValidateSet("http", "tei")]
    [string]$Provider = "http"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($TopN -lt 1 -or $TopN -gt 3) {
    throw "TopN 必须在 1 到测试文档数量 3 之间。"
}

$normalizedBaseUrl = $BaseUrl.TrimEnd('/')
$health = Invoke-WebRequest -UseBasicParsing -Uri "$normalizedBaseUrl/health" -TimeoutSec 10
if ($health.StatusCode -ne 200) {
    throw "Reranker 健康检查失败，HTTP 状态码：$($health.StatusCode)"
}

$documents = @(
    "Spring 使用 IoC 容器管理对象，并支持构造器依赖注入。",
    "Redis 是一个高性能内存数据库。",
    "PostgreSQL 可以通过 pgvector 扩展进行向量检索。"
)
$requestObject = if ($Provider -eq "tei") {
    @{
        query = "Spring Boot 如何实现依赖注入？"
        texts = $documents
        raw_scores = $false
        return_text = $false
        truncate = $true
    }
} else {
    @{
        model = $Model
        query = "Spring Boot 如何实现依赖注入？"
        documents = $documents
        top_n = $TopN
    }
}
$endpoint = if ($Provider -eq "tei") { "/rerank" } else { "/v1/rerank" }
$response = Invoke-RestMethod `
    -Method Post `
    -Uri "$normalizedBaseUrl$endpoint" `
    -ContentType "application/json; charset=utf-8" `
    -Body ($requestObject | ConvertTo-Json -Depth 4) `
    -TimeoutSec 60

$results = if ($Provider -eq "tei") { @($response) } else { @($response.results) }
if ($results.Count -lt $TopN) {
    throw "Reranker 返回的结果数量少于 TopN。"
}
$results = @($results | Select-Object -First $TopN)

$seenIndexes = [System.Collections.Generic.HashSet[int]]::new()
$previousScore = [double]::PositiveInfinity
foreach ($result in $results) {
    $index = [int]$result.index
    $score = if ($Provider -eq "tei") { [double]$result.score } else { [double]$result.relevance_score }
    if ($index -lt 0 -or $index -ge $documents.Count) {
        throw "Reranker 返回了非法文档索引：$index"
    }
    if (-not $seenIndexes.Add($index)) {
        throw "Reranker 返回了重复文档索引：$index"
    }
    if ($score -gt $previousScore) {
        throw "Reranker 结果没有按 relevance_score 降序排列。"
    }
    $previousScore = $score
}

Write-Host "Reranker contract test ($Provider): PASS"
$results | ConvertTo-Json -Depth 8
