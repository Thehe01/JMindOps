[CmdletBinding()]
param(
    [string]$DatasetPath = "evaluation-data/rag-test-v1.json",
    [string]$ApiBaseUrl = "http://127.0.0.1:8081/api",
    [ValidateSet("relay", "ollama")][string]$ChatProvider = "relay",
    [string]$RelayApiKeyFile = "D:\project\smartship\.api_key",
    [ValidateRange(1, 10)][int]$RepeatCount = 3,
    [ValidateRange(0, 3)][int]$WarmupRuns = 1,
    [ValidateRange(10, 1800)][int]$CaseTimeoutSeconds = 300,
    [ValidateRange(1, 30)][int]$PollIntervalSeconds = 3,
    [ValidateRange(2, 120)][int]$MaxPollIntervalSeconds = 30,
    [string[]]$RegressionCaseIds = @(),
    [switch]$Development,
    [ValidatePattern('^[0-9]{8}-[0-9]{6}$')][string]$RunId = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$databaseName = "jmindops_rag_formal_$($RunId.Replace('-', '_'))"
$redisDatabase = 15
$outputDirectory = Join-Path $repositoryRoot "evaluation-results"
$manifestPath = Join-Path $outputDirectory "rag-formal-$RunId.manifest.json"
$runOutLog = Join-Path $outputDirectory "rag-formal-$RunId.out.log"
$runErrLog = Join-Path $outputDirectory "rag-formal-$RunId.err.log"
$backendOutLog = Join-Path $outputDirectory "rag-formal-backend-$RunId.out.log"
$backendErrLog = Join-Path $outputDirectory "rag-formal-backend-$RunId.err.log"
$datasetFullPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $DatasetPath))
$documentPath = Join-Path $outputDirectory "rag-formal-documents-$RunId"
$jarPath = Join-Path $repositoryRoot "jmindops/target/jmindops-0.0.1-SNAPSHOT.jar"
$backendWorkingDirectory = Join-Path $repositoryRoot "jmindops"
$javaExecutable = "D:/develop/Java/jdk-21.0.10/bin/java.exe"
$corpusPaths = @(
    (Join-Path $repositoryRoot "README.md"),
    (Join-Path $repositoryRoot "全生命周期.md"),
    (Join-Path $repositoryRoot "walkthrough.md")
)
$chatClientKey = "ollama-qwen2.5"
$chatModelName = "qwen2.5"
$relayConfig = @{}
if ($ChatProvider -eq "relay") {
    if (-not (Test-Path -LiteralPath $RelayApiKeyFile -PathType Leaf)) {
        throw "Relay API 配置文件不存在: $RelayApiKeyFile"
    }
    foreach ($line in Get-Content -LiteralPath $RelayApiKeyFile) {
        $trimmed = [string]$line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) { continue }
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) { continue }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim().Trim('"').Trim("'")
        $relayConfig[$name] = $value
    }
    foreach ($requiredName in @("RELAY_API_KEY", "RELAY_BASE_URL", "RELAY_CHAT_MODEL")) {
        if ([string]::IsNullOrWhiteSpace([string]$relayConfig[$requiredName])) {
            throw "Relay API 配置缺少 $requiredName（不会把密钥写入日志或 manifest）"
        }
    }
    $normalizedRelayBaseUrl = ([string]$relayConfig["RELAY_BASE_URL"]).TrimEnd("/")
    if (-not $normalizedRelayBaseUrl.EndsWith("/v1", [StringComparison]::OrdinalIgnoreCase)) {
        $normalizedRelayBaseUrl += "/v1"
    }
    $relayConfig["RELAY_BASE_URL"] = $normalizedRelayBaseUrl
    $chatClientKey = "deepseek-chat"
    $chatModelName = [string]$relayConfig["RELAY_CHAT_MODEL"]
}
$backendProcess = $null
$databaseCreated = $false
$runType = if ($Development -and $RegressionCaseIds.Count -gt 0) {
    "DEVELOPMENT_REGRESSION"
} elseif ($Development) {
    "DEVELOPMENT"
} elseif ($RegressionCaseIds.Count -gt 0) {
    "REGRESSION"
} else {
    "FORMAL"
}
$manifest = [ordered]@{
    startedAt = (Get-Date).ToUniversalTime().ToString("o")
    status = "STARTING"
    runId = $RunId
    runType = $runType
    exposedTestRegression = $runType -eq "REGRESSION"
    regressionCaseIds = @($RegressionCaseIds)
    datasetPath = $datasetFullPath
    datasetSha256 = $null
    isolatedDatabase = $databaseName
    redisDatabase = $redisDatabase
    apiBaseUrl = $ApiBaseUrl
    modelProvider = $ChatProvider
    model = $chatModelName
    embeddingModel = "bge-m3"
    rerankerModel = "BAAI/bge-reranker-v2-m3"
    repeatCount = $RepeatCount
    warmupRuns = $WarmupRuns
    runOutLog = $runOutLog
    runErrLog = $runErrLog
    backendOutLog = $backendOutLog
    backendErrLog = $backendErrLog
    agentId = $null
    knowledgeBaseId = $null
    knowledgeBaseSnapshot = $null
    corpus = @()
    backendPid = $null
    summaryJson = $null
    summaryMarkdown = $null
    retrievalJson = $null
    qualityGatePassed = $null
    casePassRate = $null
    failedCaseIds = @()
    cleanup = [ordered]@{
        backendStopped = $false
        isolatedDatabaseDropped = $false
        redisDatabaseFlushed = $false
    }
}

function Write-Manifest {
    $manifest | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $manifestPath -Encoding utf8
}

function Get-NewArtifact {
    param([Parameter(Mandatory = $true)][string]$Filter, [Parameter(Mandatory = $true)][datetime]$StartedAt)
    $artifact = Get-ChildItem -LiteralPath $outputDirectory -Filter $Filter |
        Where-Object { $_.LastWriteTime -ge $StartedAt.AddSeconds(-1) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $artifact) { throw "未找到本轮产物: $Filter" }
    return $artifact
}

function Invoke-WslRoot {
    param([Parameter(Mandatory = $true)][string]$Command)
    $result = & wsl.exe -d Ubuntu -u root -- bash -lc $Command
    if ($LASTEXITCODE -ne 0) {
        throw "WSL 命令失败（exit=$LASTEXITCODE）"
    }
    return $result
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body,
        [string]$Token
    )
    $parameters = @{
        Method = $Method
        Uri = "$($ApiBaseUrl.TrimEnd('/'))$Path"
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $parameters.Headers = @{ Authorization = "Bearer $Token" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 20
    }
    $response = Invoke-RestMethod @parameters
    if ($null -eq $response -or [int]$response.code -ne 200) {
        $message = if ($null -eq $response) { "empty response" } else { [string]$response.message }
        throw "接口调用失败: $Method $Path, $message"
    }
    return $response.data
}

function Wait-Backend {
    $healthUrl = $ApiBaseUrl.Substring(0, $ApiBaseUrl.Length - 4) + "/actuator/health"
    $deadline = (Get-Date).AddMinutes(2)
    do {
        if ($null -ne $backendProcess -and $backendProcess.HasExited) {
            throw "临时后端提前退出，exit=$($backendProcess.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -SkipHttpErrorCheck -Uri $healthUrl -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "临时后端在 120 秒内未就绪"
}

$null = New-Item -ItemType Directory -Path $outputDirectory -Force
Write-Manifest

try {
    $validatorArguments = @(
        (Join-Path $PSScriptRoot "validate-rag-eval-dataset.py"),
        "--dataset", $datasetFullPath,
        "--repository-root", $repositoryRoot
    )
    if ($Development) {
        $validatorArguments += "--allow-draft"
    } else {
        $validatorArguments += "--require-frozen-test"
    }
    if ($runType -eq "REGRESSION") {
        $validatorArguments += "--allow-exposed-test-regression"
    }
    & python @validatorArguments
    if ($LASTEXITCODE -ne 0) { throw "RAG 题集校验失败" }
    $datasetMetadata = Get-Content -Raw -LiteralPath $datasetFullPath | ConvertFrom-Json
    if ($Development -and [string]$datasetMetadata.datasetRole -ne "development") {
        throw "Development 模式只接受 datasetRole=development 的题集"
    }
    if (-not $Development -and $runType -eq "FORMAL" -and $RepeatCount -lt 3) {
        throw "正式评测至少需要重复运行 3 次"
    }
    $manifest.datasetSha256 = (Get-FileHash -LiteralPath $datasetFullPath -Algorithm SHA256).Hash.ToLowerInvariant()

    if (-not (Test-Path -LiteralPath $jarPath)) { throw "缺少后端 JAR: $jarPath" }
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "缺少 Java 21: $javaExecutable" }
    foreach ($corpusPath in $corpusPaths) {
        if (-not (Test-Path -LiteralPath $corpusPath -PathType Leaf)) {
            throw "锁定语料不存在: $corpusPath"
        }
    }
    if ($MaxPollIntervalSeconds -lt $PollIntervalSeconds) {
        throw "MaxPollIntervalSeconds 不能小于 PollIntervalSeconds"
    }

    $ollamaHealth = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 5
    $rerankerHealth = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8001/health" -TimeoutSec 5
    if ($ollamaHealth.StatusCode -ne 200 -or $rerankerHealth.StatusCode -ne 200) {
        throw "Ollama 或 vLLM reranker 未就绪"
    }

    $null = Invoke-WslRoot "docker exec jmindops-eval-db createdb -U postgres --template=template0 $databaseName"
    $databaseCreated = $true
    $null = Invoke-WslRoot "docker exec jmindops-eval-redis redis-cli -n $redisDatabase FLUSHDB"
    $postgresPassword = ([string](Invoke-WslRoot "docker exec jmindops-eval-db printenv POSTGRES_PASSWORD")).Trim()
    if ([string]::IsNullOrWhiteSpace($postgresPassword)) { throw "无法读取隔离 PostgreSQL 连接凭证" }

    $adminUsername = "ragformal$($RunId.Replace('-', ''))"
    $adminPassword = "Eval-$([Guid]::NewGuid().ToString('N'))"
    $jwtSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(64)).ToLowerInvariant()
    $null = New-Item -ItemType Directory -Path $documentPath -Force

    $backendEnvironment = @{
        SERVER_PORT = "8081"
        SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5433/$databaseName"
        SPRING_DATASOURCE_USERNAME = "postgres"
        SPRING_DATASOURCE_PASSWORD = $postgresPassword
        SPRING_FLYWAY_USER = "postgres"
        SPRING_FLYWAY_PASSWORD = $postgresPassword
        SPRING_DATA_REDIS_HOST = "127.0.0.1"
        SPRING_DATA_REDIS_PORT = "6380"
        SPRING_DATA_REDIS_DATABASE = [string]$redisDatabase
        APP_JWT_SECRET = $jwtSecret
        APP_JWT_TTL_HOURS = "24"
        APP_BOOTSTRAP_ADMIN_USERNAME = $adminUsername
        APP_BOOTSTRAP_ADMIN_PASSWORD = $adminPassword
        AGENT_LLM_STREAM_TIMEOUT_SECONDS = "180"
        DATABASE_TOOL_ENABLED = "false"
        EMAIL_TOOL_ENABLED = "false"
        FILESYSTEM_TOOL_ENABLED = "false"
        MCP_CLIENT_ENABLED = "false"
        DOCUMENT_STORAGE_BASE_PATH = $documentPath
        RAG_EMBEDDING_BASE_URL = "http://127.0.0.1:11434"
        RAG_EMBEDDING_MODEL = "bge-m3"
        RAG_EMBEDDING_DIMENSIONS = "1024"
        RAG_EMBEDDING_NORMALIZATION = "none"
        RAG_INDEX_PIPELINE_VERSION = "tika-flexmark-chunk-v1"
        RAG_REQUEST_TIMEOUT_SECONDS = "60"
        RAG_RERANKER_PROVIDER = "http"
        RAG_RERANKER_BASE_URL = "http://127.0.0.1:8001"
        RAG_RERANKER_ENDPOINT = "/v1/rerank"
        RAG_RERANKER_MODEL = "BAAI/bge-reranker-v2-m3"
        RAG_RERANKER_TIMEOUT_SECONDS = "60"
        RAG_ANSWER_TOP_K = "5"
        RAG_CANDIDATE_TOP_K = "20"
        RAG_EVIDENCE_GATE_ENABLED = "true"
        RAG_MINIMUM_RERANK_SCORE = "0.05"
    }
    if ($ChatProvider -eq "relay") {
        $backendEnvironment.SPRING_AI_MODEL_CHAT = "deepseek"
        $backendEnvironment.DEEPSEEK_API_KEY = [string]$relayConfig["RELAY_API_KEY"]
        $backendEnvironment.DEEPSEEK_BASE_URL = [string]$relayConfig["RELAY_BASE_URL"]
        $backendEnvironment.DEEPSEEK_CHAT_MODEL = [string]$relayConfig["RELAY_CHAT_MODEL"]
    } else {
        $backendEnvironment.SPRING_AI_MODEL_CHAT = "ollama"
        $backendEnvironment.OLLAMA_CHAT_BASE_URL = "http://127.0.0.1:11434"
        $backendEnvironment.OLLAMA_CHAT_MODEL = "qwen2.5"
    }

    $backendProcess = Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", $jarPath) `
        -WorkingDirectory $backendWorkingDirectory -WindowStyle Hidden `
        -RedirectStandardOutput $backendOutLog -RedirectStandardError $backendErrLog `
        -Environment $backendEnvironment -PassThru
    $manifest.backendPid = $backendProcess.Id
    $manifest.status = "BACKEND_STARTING"
    Write-Manifest
    Wait-Backend

    $login = Invoke-Api -Method POST -Path "/auth/login" -Body @{
        username = $adminUsername
        password = $adminPassword
    }
    $token = [string]$login.accessToken
    if ([string]::IsNullOrWhiteSpace($token)) { throw "登录未返回 JWT" }

    $knowledgeBase = Invoke-Api -Method POST -Path "/knowledge-bases" -Token $token -Body @{
        name = "RAG $($runType.ToLowerInvariant()) $RunId"
        description = "仅包含三份 SHA-256 锁定语料的隔离 RAG $($runType.ToLowerInvariant()) 评测库"
    }
    $knowledgeBaseId = [string]$knowledgeBase.knowledgeBaseId
    foreach ($corpusPath in $corpusPaths) {
        $file = Get-Item -LiteralPath $corpusPath
        $upload = Invoke-RestMethod -Method Post -Uri "$($ApiBaseUrl.TrimEnd('/'))/documents/upload" `
            -Headers @{ Authorization = "Bearer $token" } -Form @{
                kbId = $knowledgeBaseId
                file = $file
            }
        if ($null -eq $upload -or [int]$upload.code -ne 200) {
            throw "锁定语料上传失败: $($file.Name)"
        }
        $manifest.corpus += [ordered]@{
            filename = $file.Name
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        Write-Manifest
    }

    $documents = Invoke-Api -Method GET -Path "/documents/kb/$knowledgeBaseId" -Token $token
    $indexedDocuments = @($documents.documents)
    $expectedNames = @($corpusPaths | ForEach-Object { [System.IO.Path]::GetFileName($_) } | Sort-Object)
    $actualNames = @($indexedDocuments | ForEach-Object { [string]$_.filename } | Sort-Object)
    if ($indexedDocuments.Count -ne 3 -or (Compare-Object $expectedNames $actualNames)) {
        throw "正式知识库文档集合与三份锁定语料不一致"
    }
    $manifest.corpus = @($indexedDocuments | Sort-Object filename | ForEach-Object {
        $document = $_
        $sourcePath = $corpusPaths | Where-Object {
            [System.IO.Path]::GetFileName($_) -eq [string]$document.filename
        } | Select-Object -First 1
        [ordered]@{
            filename = [string]$document.filename
            sha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
            documentId = [string]$document.id
            indexStatus = [string]$document.indexStatus
            indexVersion = [int]$document.indexVersion
            chunkCount = [int]$document.chunkCount
        }
    })
    Write-Manifest
    $notIndexed = @($indexedDocuments | Where-Object {
        [string]$_.indexStatus -ne "READY" -or [int]$_.chunkCount -le 0
    })
    if ($notIndexed.Count -gt 0) {
        throw "正式知识库存在未完成索引的文档: $(@($notIndexed.filename) -join ', ')"
    }

    $agent = Invoke-Api -Method POST -Path "/agents" -Token $token -Body @{
        name = "RAG $($runType.ToLowerInvariant()) $RunId"
        description = if ($runType -eq "REGRESSION") {
            "历史失败题回归专用 Agent"
        } elseif ($runType -eq "DEVELOPMENT") {
            "RAG 开发集评测专用 Agent"
        } else {
            "正式冻结 RAG 测试专用 Agent"
        }
        systemPrompt = "你是 JMindOps RAG 评测智能体。仅依据 KnowledgeTool 返回的知识库证据回答；每个事实必须保留直接支持它的 [Source N] 引用。只陈述证据明确表达的事实，不得从证据列出的内容反向推断未列出内容必然不存在，也不得为证据没有定义的 ID、状态、字段或组件补充语义。删除与问题无关的扩展说明；证据不足时明确拒答且不得附加来源编号。不要使用外部知识补全答案。"
        model = $chatClientKey
        allowedTools = @()
        allowedKbs = @($knowledgeBaseId)
        chatOptions = @{
            temperature = 0.0
            topP = 0.9
            messageLength = 10
        }
    }
    $agentId = [string]$agent.agentId
    $manifest.agentId = $agentId
    $manifest.knowledgeBaseId = $knowledgeBaseId

    $provenance = Invoke-Api -Method GET -Path "/rag/evaluation/provenance/$knowledgeBaseId" -Token $token
    $manifest.knowledgeBaseSnapshot = [string]$provenance.knowledgeBaseSnapshot
    $manifest.retrievalConfigHash = [string]$provenance.retrievalConfigHash
    $manifest.status = "RUNNING"
    $manifest.evaluationStartedAt = (Get-Date).ToUniversalTime().ToString("o")
    Write-Manifest

    Write-Host "RAG $($runType.ToLowerInvariant()) evaluation started: $(Get-Date -Format o)"
    Write-Host "RunId=$RunId"
    Write-Host "Dataset=$datasetFullPath"
    Write-Host "AgentId=$agentId"
    Write-Host "KnowledgeBaseId=$knowledgeBaseId"
    Write-Host "KnowledgeBaseSnapshot=$($manifest.knowledgeBaseSnapshot)"
    Write-Host "Polling=${PollIntervalSeconds}s -> ${MaxPollIntervalSeconds}s (dynamic backoff)"

    $evaluationStartedAt = Get-Date
    if ($runType -in @("REGRESSION", "DEVELOPMENT_REGRESSION")) {
        $regressionArguments = @{
            DatasetPath = $datasetFullPath
            AgentId = $agentId
            KnowledgeBaseId = $knowledgeBaseId
            ApiBaseUrl = $ApiBaseUrl
            Token = $token
            OutputDirectory = $outputDirectory
            CaseTimeoutSeconds = $CaseTimeoutSeconds
            PollIntervalSeconds = $PollIntervalSeconds
            MaxPollIntervalSeconds = $MaxPollIntervalSeconds
            CaseIds = $RegressionCaseIds
        }
        if ($Development) {
            $regressionArguments.AllowDraft = $true
        } else {
            $regressionArguments.RequireFrozenTest = $true
            $regressionArguments.AllowExposedTestRegression = $true
        }
        & (Join-Path $PSScriptRoot "run-rag-answer-evaluation.ps1") @regressionArguments
        if ($LASTEXITCODE -ne 0) { throw "RAG 回归评测失败，exit=$LASTEXITCODE" }
        $summaryJson = Get-ChildItem -LiteralPath $outputDirectory -Filter "rag-answer-evaluation-*-report.json" |
            Where-Object { $_.LastWriteTime -ge $evaluationStartedAt } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $summaryMarkdown = Get-ChildItem -LiteralPath $outputDirectory -Filter "rag-answer-evaluation-*.md" |
            Where-Object { $_.LastWriteTime -ge $evaluationStartedAt } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    } elseif ($runType -eq "DEVELOPMENT") {
        Write-Host "[development] RAG retrieval"
        $retrievalStarted = Get-Date
        & (Join-Path $PSScriptRoot "run-rag-evaluation.ps1") `
            -DatasetPath $datasetFullPath `
            -KnowledgeBaseId $knowledgeBaseId `
            -ApiBaseUrl $ApiBaseUrl `
            -Token $token `
            -Modes @("VECTOR", "HYBRID_RRF", "HYBRID_RERANK") `
            -OutputDirectory $outputDirectory `
            -AllowDraft
        if ($LASTEXITCODE -ne 0) { throw "RAG 开发集检索评测失败，exit=$LASTEXITCODE" }
        $retrievalArtifact = Get-NewArtifact "rag-evaluation-*.json" $retrievalStarted
        $manifest.retrievalJson = $retrievalArtifact.FullName
        Write-Manifest

        Write-Host "[development] RAG answer"
        $answerStarted = Get-Date
        & (Join-Path $PSScriptRoot "run-rag-answer-evaluation.ps1") `
            -DatasetPath $datasetFullPath `
            -AgentId $agentId `
            -KnowledgeBaseId $knowledgeBaseId `
            -ApiBaseUrl $ApiBaseUrl `
            -Token $token `
            -OutputDirectory $outputDirectory `
            -CaseTimeoutSeconds $CaseTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds `
            -MaxPollIntervalSeconds $MaxPollIntervalSeconds `
            -AllowDraft
        if ($LASTEXITCODE -ne 0) { throw "RAG 开发集回答评测失败，exit=$LASTEXITCODE" }
        $summaryJson = Get-NewArtifact "rag-answer-evaluation-*-report.json" $answerStarted
        $summaryMarkdown = Get-NewArtifact "rag-answer-evaluation-*.md" $answerStarted
    } else {
        & (Join-Path $PSScriptRoot "run-formal-rag-evaluation.ps1") `
            -DatasetPath $datasetFullPath `
            -AgentId $agentId `
            -KnowledgeBaseId $knowledgeBaseId `
            -ApiBaseUrl $ApiBaseUrl `
            -Token $token `
            -OutputDirectory $outputDirectory `
            -RepeatCount $RepeatCount `
            -WarmupRuns $WarmupRuns `
            -CaseTimeoutSeconds $CaseTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds `
            -MaxPollIntervalSeconds $MaxPollIntervalSeconds
        if ($LASTEXITCODE -ne 0) { throw "RAG 正式评测失败，exit=$LASTEXITCODE" }
        $summaryJson = Get-ChildItem -LiteralPath $outputDirectory -Filter "rag-formal-summary-*.json" |
            Where-Object { $_.LastWriteTime -ge $evaluationStartedAt } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $summaryMarkdown = Get-ChildItem -LiteralPath $outputDirectory -Filter "rag-formal-summary-*.md" |
            Where-Object { $_.LastWriteTime -ge $evaluationStartedAt } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if ($null -eq $summaryJson -or $null -eq $summaryMarkdown) {
        throw "$runType 评测完成但未找到汇总产物"
    }
    if ($runType -in @("REGRESSION", "DEVELOPMENT", "DEVELOPMENT_REGRESSION")) {
        $answerReport = Get-Content -Raw -LiteralPath $summaryJson.FullName | ConvertFrom-Json
        $manifest.casePassRate = [double]$answerReport.metrics.casePassRate
        $manifest.failedCaseIds = @($answerReport.details | Where-Object {
            $_.evaluated -and -not $_.passed
        } | ForEach-Object { [string]$_.caseId })
        $manifest.qualityGatePassed = $manifest.failedCaseIds.Count -eq 0
    }
    $manifest.status = "COMPLETED"
    $manifest.completedAt = (Get-Date).ToUniversalTime().ToString("o")
    $manifest.summaryJson = $summaryJson.FullName
    $manifest.summaryMarkdown = $summaryMarkdown.FullName
    Write-Manifest
} catch {
    $manifest.status = "FAILED"
    $manifest.failedAt = (Get-Date).ToUniversalTime().ToString("o")
    $manifest.error = $_.Exception.Message
    Write-Manifest
    Write-Error $_
} finally {
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
        $manifest.cleanup.backendStopped = $true
    }
    if ($databaseCreated -and $databaseName -match '^jmindops_rag_formal_[0-9_]+$') {
        try {
            $null = Invoke-WslRoot "docker exec jmindops-eval-db dropdb -U postgres --force --if-exists $databaseName"
            $manifest.cleanup.isolatedDatabaseDropped = $true
        } catch {
            $manifest.cleanup.databaseDropError = $_.Exception.Message
        }
    }
    try {
        $null = Invoke-WslRoot "docker exec jmindops-eval-redis redis-cli -n $redisDatabase FLUSHDB"
        $manifest.cleanup.redisDatabaseFlushed = $true
    } catch {
        $manifest.cleanup.redisFlushError = $_.Exception.Message
    }
    Write-Manifest
}

if ($manifest.status -eq "FAILED" -or (
    $runType -in @("FORMAL", "REGRESSION") -and $manifest.qualityGatePassed -eq $false
)) { exit 1 }
