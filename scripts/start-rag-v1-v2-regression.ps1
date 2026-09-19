[CmdletBinding()]
param(
    [string]$V1DatasetPath = "evaluation-data/rag-test-v1.json",
    [string]$V2DatasetPath = "evaluation-data/rag-test-v2.json",
    [string]$ApiBaseUrl = "http://127.0.0.1:8081/api",
    [string]$RelayApiKeyFile = "D:\project\smartship\.api_key",
    [string]$JudgeModel = "gpt-5.5",
    [ValidateRange(10, 1800)][int]$CaseTimeoutSeconds = 300,
    [ValidateRange(1, 30)][int]$PollIntervalSeconds = 3,
    [ValidateRange(2, 120)][int]$MaxPollIntervalSeconds = 30,
    [ValidateRange(0, 60)][int]$MinimumSubmissionIntervalSeconds = 6,
    [ValidatePattern('^[0-9]{8}-[0-9]{6}$')][string]$RunId = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repositoryRoot "evaluation-results"
$suiteDirectory = Join-Path $outputRoot "rag-regression-v1-v2-$RunId"
$suiteManifestPath = Join-Path $suiteDirectory "suite.manifest.json"
$summaryJsonPath = Join-Path $suiteDirectory "joint-summary.json"
$summaryMarkdownPath = Join-Path $suiteDirectory "joint-summary.md"
$backendOutLog = Join-Path $suiteDirectory "backend.out.log"
$backendErrLog = Join-Path $suiteDirectory "backend.err.log"
$documentPath = Join-Path $suiteDirectory "documents"
$databaseName = "jmindops_rag_reg_$($RunId.Replace('-', '_'))"
$redisDatabase = 15
$jarPath = Join-Path $repositoryRoot "jmindops/target/jmindops-0.0.1-SNAPSHOT.jar"
$backendWorkingDirectory = Join-Path $repositoryRoot "jmindops"
$javaExecutable = "D:/develop/Java/jdk-21.0.10/bin/java.exe"
$corpusPaths = @(
    (Join-Path $repositoryRoot "README.md"),
    (Join-Path $repositoryRoot "全生命周期.md"),
    (Join-Path $repositoryRoot "walkthrough.md")
)
$backendProcess = $null
$databaseCreated = $false
$activeDataset = $null
$datasetRuns = [System.Collections.ArrayList]::new()

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][object]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Write-SuiteManifest {
    Write-JsonFile -Value $script:suiteManifest -Path $script:suiteManifestPath
}

function Write-DatasetManifest {
    param([Parameter(Mandatory = $true)][object]$Entry)
    Write-JsonFile -Value $Entry -Path ([string]$Entry.manifestPath)
}

function Get-NewArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Filter,
        [Parameter(Mandatory = $true)][datetime]$StartedAt
    )
    $artifact = Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Where-Object { $_.LastWriteTime -ge $StartedAt.AddSeconds(-1) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $artifact) { throw "未找到本轮产物: $Directory / $Filter" }
    return $artifact
}

function Invoke-WslRoot {
    param([Parameter(Mandatory = $true)][string]$Command)
    $result = & wsl.exe -d Ubuntu -u root -- bash -lc $Command
    if ($LASTEXITCODE -ne 0) { throw "WSL 命令失败（exit=$LASTEXITCODE）" }
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
        if ($null -ne $script:backendProcess -and $script:backendProcess.HasExited) {
            throw "临时后端提前退出，exit=$($script:backendProcess.ExitCode)"
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

function New-ArtifactRecord {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [ordered]@{
        path = [System.IO.Path]::GetFullPath($Path)
        sha256 = Get-FileSha256 $Path
    }
}

$null = New-Item -ItemType Directory -Path $suiteDirectory -Force
$suiteManifest = [ordered]@{
    schemaVersion = "1.0"
    startedAt = (Get-Date).ToUniversalTime().ToString("o")
    status = "STARTING"
    runId = $RunId
    runType = "REGRESSION"
    suiteType = "V1_V2_SEQUENTIAL_FULL"
    exposedTestRegression = $true
    sequential = $true
    executionOrder = @("V1", "V2")
    totalCases = 100
    apiBaseUrl = $ApiBaseUrl
    answerProvider = "relay"
    answerModel = $null
    judgeModel = $JudgeModel
    judgeMaxAttemptsPerCase = 1
    jarPath = $jarPath
    jarSha256 = $null
    isolatedDatabase = $databaseName
    redisDatabase = $redisDatabase
    backendPid = $null
    backendOutLog = $backendOutLog
    backendErrLog = $backendErrLog
    agentId = $null
    knowledgeBaseId = $null
    knowledgeBaseSnapshot = $null
    retrievalConfigHash = $null
    corpus = @()
    datasets = $datasetRuns
    summaryJson = $null
    summaryMarkdown = $null
    cleanup = [ordered]@{
        backendStopped = $false
        isolatedDatabaseDropped = $false
        redisDatabaseFlushed = $false
    }
}
Write-SuiteManifest

try {
    if ($MaxPollIntervalSeconds -lt $PollIntervalSeconds) {
        throw "MaxPollIntervalSeconds 不能小于 PollIntervalSeconds"
    }
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "缺少后端 JAR: $jarPath" }
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw "缺少 Java 21: $javaExecutable" }
    if (-not (Test-Path -LiteralPath $RelayApiKeyFile -PathType Leaf)) {
        throw "Relay API 配置文件不存在: $RelayApiKeyFile"
    }
    foreach ($corpusPath in $corpusPaths) {
        if (-not (Test-Path -LiteralPath $corpusPath -PathType Leaf)) {
            throw "锁定语料不存在: $corpusPath"
        }
    }

    $relayConfig = @{}
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
    $suiteManifest.answerModel = [string]$relayConfig["RELAY_CHAT_MODEL"]
    $suiteManifest.jarSha256 = Get-FileSha256 $jarPath

    $datasetDefinitions = @(
        [ordered]@{ label = "V1"; relativePath = $V1DatasetPath },
        [ordered]@{ label = "V2"; relativePath = $V2DatasetPath }
    )
    $seenDatasetPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($definition in $datasetDefinitions) {
        $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $definition.relativePath))
        if (-not $seenDatasetPaths.Add($fullPath)) { throw "V1/V2 题集路径不能相同" }
        & python (Join-Path $PSScriptRoot "validate-rag-eval-dataset.py") `
            --dataset $fullPath --repository-root $repositoryRoot `
            --require-frozen-test --allow-exposed-test-regression
        if ($LASTEXITCODE -ne 0) { throw "$($definition.label) 题集正式回归门禁失败" }
        $dataset = Get-Content -Raw -LiteralPath $fullPath | ConvertFrom-Json
        if (@($dataset.testCases).Count -ne 50) {
            throw "$($definition.label) 必须恰好包含 50 题"
        }
        $datasetDirectory = Join-Path $suiteDirectory $definition.label.ToLowerInvariant()
        $null = New-Item -ItemType Directory -Path $datasetDirectory -Force
        $entry = [ordered]@{
            label = $definition.label
            status = "VALIDATED"
            runType = "REGRESSION"
            exposedTestRegression = $true
            executionOrder = $datasetRuns.Count + 1
            datasetId = [string]$dataset.datasetId
            datasetPath = $fullPath
            datasetSha256 = Get-FileSha256 $fullPath
            caseCount = 50
            jarSha256 = $suiteManifest.jarSha256
            answerModel = $suiteManifest.answerModel
            judgeModel = $JudgeModel
            agentId = $null
            knowledgeBaseId = $null
            knowledgeBaseSnapshot = $null
            retrievalConfigHash = $null
            outputDirectory = $datasetDirectory
            manifestPath = Join-Path $datasetDirectory "manifest.json"
            retrievalJson = $null
            retrievalMarkdown = $null
            observedJson = $null
            deterministicJson = $null
            deterministicMarkdown = $null
            judgeJson = $null
            judgeMarkdown = $null
            judgeCheckpoint = $null
            startedAt = $null
            deterministicCompletedAt = $null
            judgeCompletedAt = $null
            completedAt = $null
            error = $null
        }
        $null = $datasetRuns.Add($entry)
        Write-DatasetManifest $entry
        Write-SuiteManifest
    }
    $totalCaseCount = [int](($datasetRuns | ForEach-Object { [int]$_['caseCount'] } | Measure-Object -Sum).Sum)
    if ($totalCaseCount -ne 100) {
        throw "V1+V2 总题数必须为 100"
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

    $adminUsername = "ragreg$($RunId.Replace('-', ''))"
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
        SPRING_AI_MODEL_CHAT = "deepseek"
        DEEPSEEK_API_KEY = [string]$relayConfig["RELAY_API_KEY"]
        DEEPSEEK_BASE_URL = [string]$relayConfig["RELAY_BASE_URL"]
        DEEPSEEK_CHAT_MODEL = [string]$relayConfig["RELAY_CHAT_MODEL"]
    }

    $backendProcess = Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", $jarPath) `
        -WorkingDirectory $backendWorkingDirectory -WindowStyle Hidden `
        -RedirectStandardOutput $backendOutLog -RedirectStandardError $backendErrLog `
        -Environment $backendEnvironment -PassThru
    $suiteManifest.backendPid = $backendProcess.Id
    $suiteManifest.status = "BACKEND_STARTING"
    Write-SuiteManifest
    Wait-Backend

    $login = Invoke-Api -Method POST -Path "/auth/login" -Body @{
        username = $adminUsername
        password = $adminPassword
    }
    $token = [string]$login.accessToken
    if ([string]::IsNullOrWhiteSpace($token)) { throw "登录未返回 JWT" }

    $knowledgeBase = Invoke-Api -Method POST -Path "/knowledge-bases" -Token $token -Body @{
        name = "RAG V1+V2 regression $RunId"
        description = "V1 与 V2 串行全量回归共享的三文档隔离知识库"
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
    }
    $documents = Invoke-Api -Method GET -Path "/documents/kb/$knowledgeBaseId" -Token $token
    $indexedDocuments = @($documents.documents)
    $expectedNames = @($corpusPaths | ForEach-Object { [System.IO.Path]::GetFileName($_) } | Sort-Object)
    $actualNames = @($indexedDocuments | ForEach-Object { [string]$_.filename } | Sort-Object)
    if ($indexedDocuments.Count -ne 3 -or (Compare-Object $expectedNames $actualNames)) {
        throw "联合回归知识库文档集合与三份锁定语料不一致"
    }
    $notIndexed = @($indexedDocuments | Where-Object {
        [string]$_.indexStatus -ne "READY" -or [int]$_.chunkCount -le 0
    })
    if ($notIndexed.Count -gt 0) { throw "联合回归知识库存在未完成索引的文档" }
    $suiteManifest.corpus = @($indexedDocuments | Sort-Object filename | ForEach-Object {
        $document = $_
        $sourcePath = $corpusPaths | Where-Object {
            [System.IO.Path]::GetFileName($_) -eq [string]$document.filename
        } | Select-Object -First 1
        [ordered]@{
            filename = [string]$document.filename
            sha256 = Get-FileSha256 $sourcePath
            documentId = [string]$document.id
            indexStatus = [string]$document.indexStatus
            indexVersion = [int]$document.indexVersion
            chunkCount = [int]$document.chunkCount
        }
    })

    $agent = Invoke-Api -Method POST -Path "/agents" -Token $token -Body @{
        name = "RAG V1+V2 regression $RunId"
        description = "V1 与 V2 暴露测试集串行全量回归 Agent"
        systemPrompt = "你是 JMindOps RAG 评测智能体。仅依据 KnowledgeTool 返回的知识库证据回答；每个事实必须保留直接支持它的 [Source N] 引用。只陈述证据明确表达的事实，不得从证据列出的内容反向推断未列出内容必然不存在，也不得为证据没有定义的 ID、状态、字段或组件补充语义。删除与问题无关的扩展说明；证据不足时明确拒答且不得附加来源编号。不要使用外部知识补全答案。"
        model = "deepseek-chat"
        allowedTools = @()
        allowedKbs = @($knowledgeBaseId)
        chatOptions = @{
            temperature = 0.0
            topP = 0.9
            messageLength = 10
        }
    }
    $agentId = [string]$agent.agentId
    $provenance = Invoke-Api -Method GET -Path "/rag/evaluation/provenance/$knowledgeBaseId" -Token $token
    $suiteManifest.agentId = $agentId
    $suiteManifest.knowledgeBaseId = $knowledgeBaseId
    $suiteManifest.knowledgeBaseSnapshot = [string]$provenance.knowledgeBaseSnapshot
    $suiteManifest.retrievalConfigHash = [string]$provenance.retrievalConfigHash
    $suiteManifest.status = "DETERMINISTIC_RUNNING"
    foreach ($entry in $datasetRuns) {
        $entry.agentId = $agentId
        $entry.knowledgeBaseId = $knowledgeBaseId
        $entry.knowledgeBaseSnapshot = $suiteManifest.knowledgeBaseSnapshot
        $entry.retrievalConfigHash = $suiteManifest.retrievalConfigHash
        Write-DatasetManifest $entry
    }
    Write-SuiteManifest

    foreach ($entry in $datasetRuns) {
        $activeDataset = $entry
        $entry.status = "DETERMINISTIC_RUNNING"
        $entry.startedAt = (Get-Date).ToUniversalTime().ToString("o")
        Write-DatasetManifest $entry
        Write-SuiteManifest
        Write-Host "[$($entry.label)] deterministic retrieval started"
        $retrievalStarted = Get-Date
        & (Join-Path $PSScriptRoot "run-rag-evaluation.ps1") `
            -DatasetPath $entry.datasetPath `
            -KnowledgeBaseId $knowledgeBaseId `
            -ApiBaseUrl $ApiBaseUrl `
            -Token $token `
            -Modes @("VECTOR", "HYBRID_RRF", "HYBRID_RERANK") `
            -OutputDirectory $entry.outputDirectory `
            -RequireFrozenTest `
            -AllowExposedTestRegression
        if ($LASTEXITCODE -ne 0) { throw "$($entry.label) 检索回归失败，exit=$LASTEXITCODE" }
        $retrievalJson = Get-NewArtifact $entry.outputDirectory "rag-evaluation-*.json" $retrievalStarted
        $retrievalMarkdown = Get-NewArtifact $entry.outputDirectory "rag-evaluation-*.md" $retrievalStarted
        $entry.retrievalJson = $retrievalJson.FullName
        $entry.retrievalMarkdown = $retrievalMarkdown.FullName
        Write-DatasetManifest $entry

        Write-Host "[$($entry.label)] deterministic answer started"
        $answerStarted = Get-Date
        $dataset = Get-Content -Raw -LiteralPath $entry.datasetPath | ConvertFrom-Json
        $caseIds = @($dataset.testCases | ForEach-Object { [string]$_.id })
        & (Join-Path $PSScriptRoot "run-rag-answer-evaluation.ps1") `
            -DatasetPath $entry.datasetPath `
            -AgentId $agentId `
            -KnowledgeBaseId $knowledgeBaseId `
            -ApiBaseUrl $ApiBaseUrl `
            -Token $token `
            -OutputDirectory $entry.outputDirectory `
            -CaseTimeoutSeconds $CaseTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds `
            -MaxPollIntervalSeconds $MaxPollIntervalSeconds `
            -RateLimitRetries 0 `
            -MinimumSubmissionIntervalSeconds $MinimumSubmissionIntervalSeconds `
            -CaseIds $caseIds `
            -RequireFrozenTest `
            -AllowExposedTestRegression
        if ($LASTEXITCODE -ne 0) { throw "$($entry.label) 回答回归失败，exit=$LASTEXITCODE" }
        $entry.observedJson = (Get-NewArtifact $entry.outputDirectory "rag-answer-evaluation-*-observed.json" $answerStarted).FullName
        $entry.deterministicJson = (Get-NewArtifact $entry.outputDirectory "rag-answer-evaluation-*-report.json" $answerStarted).FullName
        $entry.deterministicMarkdown = (Get-NewArtifact $entry.outputDirectory "rag-answer-evaluation-*.md" $answerStarted).FullName
        $entry.deterministicCompletedAt = (Get-Date).ToUniversalTime().ToString("o")
        $entry.status = "DETERMINISTIC_COMPLETED"
        Write-DatasetManifest $entry
        Write-SuiteManifest
        Write-Host "[$($entry.label)] deterministic gate completed"
    }

    $suiteManifest.status = "SEMANTIC_JUDGE_RUNNING"
    Write-SuiteManifest
    foreach ($entry in $datasetRuns) {
        $activeDataset = $entry
        $entry.status = "SEMANTIC_JUDGE_RUNNING"
        $judgeBase = Join-Path $entry.outputDirectory "semantic-judge"
        $entry.judgeJson = "$judgeBase.json"
        $entry.judgeMarkdown = "$judgeBase.md"
        $entry.judgeCheckpoint = "$judgeBase.checkpoint.json"
        Write-DatasetManifest $entry
        Write-SuiteManifest
        Write-Host "[$($entry.label)] fixed semantic judge started: $JudgeModel"
        & python (Join-Path $PSScriptRoot "judge-rag-answer-eval.py") `
            --dataset $entry.datasetPath `
            --observed $entry.observedJson `
            --deterministic-report $entry.deterministicJson `
            --api-config $RelayApiKeyFile `
            --output $entry.judgeJson `
            --markdown $entry.judgeMarkdown `
            --checkpoint $entry.judgeCheckpoint `
            --judge-model $JudgeModel `
            --timeout-seconds 180 `
            --max-completion-tokens 1200 `
            --reasoning-effort low
        if ($LASTEXITCODE -ne 0) { throw "$($entry.label) 固定 Judge 执行失败，exit=$LASTEXITCODE" }
        $entry.judgeCompletedAt = (Get-Date).ToUniversalTime().ToString("o")
        $entry.completedAt = $entry.judgeCompletedAt
        $entry.status = "COMPLETED"
        Write-DatasetManifest $entry
        Write-SuiteManifest
        Write-Host "[$($entry.label)] fixed semantic judge completed"
    }
    $activeDataset = $null

    $suiteManifest.status = "SUMMARIZING"
    Write-SuiteManifest
    & python (Join-Path $PSScriptRoot "summarize-rag-regression-suite.py") `
        --suite-manifest $suiteManifestPath `
        --output $summaryJsonPath `
        --markdown $summaryMarkdownPath
    if ($LASTEXITCODE -ne 0) { throw "V1+V2 联合汇总失败，exit=$LASTEXITCODE" }
    $suiteManifest.summaryJson = $summaryJsonPath
    $suiteManifest.summaryMarkdown = $summaryMarkdownPath
    $suiteManifest.summaryJsonSha256 = Get-FileSha256 $summaryJsonPath
    $suiteManifest.summaryMarkdownSha256 = Get-FileSha256 $summaryMarkdownPath
    $suiteManifest.status = "COMPLETED"
    $suiteManifest.completedAt = (Get-Date).ToUniversalTime().ToString("o")
    Write-SuiteManifest
    Write-Host "V1+V2 sequential full regression completed"
    Write-Host "Suite manifest: $suiteManifestPath"
    Write-Host "Joint summary: $summaryMarkdownPath"
} catch {
    if ($null -ne $activeDataset) {
        $activeDataset.status = "FAILED"
        $activeDataset.error = $_.Exception.Message
        $activeDataset.failedAt = (Get-Date).ToUniversalTime().ToString("o")
        Write-DatasetManifest $activeDataset
    }
    $suiteManifest.status = "FAILED"
    $suiteManifest.error = $_.Exception.Message
    $suiteManifest.failedAt = (Get-Date).ToUniversalTime().ToString("o")
    Write-SuiteManifest
    Write-Error $_
} finally {
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
        $suiteManifest.cleanup.backendStopped = $true
    }
    if ($databaseCreated -and $databaseName -match '^jmindops_rag_reg_[0-9_]+$') {
        try {
            $null = Invoke-WslRoot "docker exec jmindops-eval-db dropdb -U postgres --force --if-exists $databaseName"
            $suiteManifest.cleanup.isolatedDatabaseDropped = $true
        } catch {
            $suiteManifest.cleanup.databaseDropError = $_.Exception.Message
        }
    }
    try {
        $null = Invoke-WslRoot "docker exec jmindops-eval-redis redis-cli -n $redisDatabase FLUSHDB"
        $suiteManifest.cleanup.redisDatabaseFlushed = $true
    } catch {
        $suiteManifest.cleanup.redisFlushError = $_.Exception.Message
    }
    Write-SuiteManifest
}

if ($suiteManifest.status -eq "FAILED") { exit 1 }
