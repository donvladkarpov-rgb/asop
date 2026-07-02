# Файл: migrate-to-api-modules.ps1
# МИГРАЦИЯ: Разделение API и реализации
# ВАЖНО: Перед запуском сделай git commit!

param(
    [switch]$WhatIf  # Режим "что если" — не выполняет изменения
)

$ErrorActionPreference = "Stop"
$root = "C:\Users\donvl\IdeaProjects\asop"
Set-Location $root

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host " МИГРАЦИЯ: Разделение API и реализации" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# Список API-модулей и соответствующих сервисов
$apiModules = @(
    @{ Name = "gateway-api";    Package = "gateway";    Service = "gateway-service" },
    @{ Name = "crypto-api";     Package = "crypto";     Service = "asop-crypto-service" },
    @{ Name = "carrier-api";    Package = "carrier";    Service = "carrier-service" },
    @{ Name = "session-api";    Package = "session";    Service = "session-service" },
    @{ Name = "terminal-api";   Package = "terminal";   Service = "terminal-service" },
    @{ Name = "card-api";       Package = "card";       Service = "card-service" },
    @{ Name = "user-api";       Package = "user";       Service = "user-service" },
    @{ Name = "debt-api";       Package = "debt";       Service = "debt-service" },
    @{ Name = "fiscal-api";     Package = "fiscal";     Service = "fiscal-service" },
    @{ Name = "audit-api";      Package = "audit";      Service = "audit-service" }
)

function Create-Directory($path) {
    if ($WhatIf) {
        Write-Host "  [WHATIF] mkdir $path" -ForegroundColor DarkYellow
    } else {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        Write-Host "  ✓ Создана: $path" -ForegroundColor Green
    }
}

function Create-File($path, $content) {
    if ($WhatIf) {
        Write-Host "  [WHATIF] create $path" -ForegroundColor DarkYellow
    } else {
        $dir = Split-Path $path -Parent
        if (-not (Test-Path $dir)) { Create-Directory $dir }
        Set-Content -Path $path -Value $content -Encoding UTF8
        Write-Host "  ✓ Создан: $path" -ForegroundColor Green
    }
}

function Move-File($source, $destination) {
    if (-not (Test-Path $source)) {
        Write-Host "  ⚠️  Источник не найден: $source" -ForegroundColor Red
        return $false
    }
    if ($WhatIf) {
        Write-Host "  [WHATIF] move $source → $destination" -ForegroundColor DarkYellow
    } else {
        $dir = Split-Path $destination -Parent
        if (-not (Test-Path $dir)) { Create-Directory $dir }
        Move-Item -Path $source -Destination $destination -Force
        Write-Host "  ✓ Перемещён: $source → $destination" -ForegroundColor Green
    }
    return $true
}

# ========================================
# ШАГ 1: Создание структуры API-модулей
# ========================================
Write-Host "`n📦 ШАГ 1: Создание структуры API-модулей" -ForegroundColor Yellow

foreach ($module in $apiModules) {
    $apiPath = "backend\api\$($module.Name)"
    Write-Host "`n🔹 Модуль: $($module.Name)" -ForegroundColor Cyan

    # Директории
    Create-Directory "$apiPath\src\main\kotlin\ru\asop\api\$($module.Package)\controller"
    Create-Directory "$apiPath\src\main\kotlin\ru\asop\api\$($module.Package)\dto\request"
    Create-Directory "$apiPath\src\main\kotlin\ru\asop\api\$($module.Package)\dto\response"
    Create-Directory "$apiPath\src\main\kotlin\ru\asop\api\$($module.Package)\exception"

    # build.gradle.kts
    $buildContent = @"
plugins {
    ``java-library``
    alias(libs.plugins.kotlin.jvm)
}

description = "API contracts для $($module.Service)"

dependencies {
    // Только зависимости для контрактов (без Spring Boot runtime)
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.validation)
    api(libs.jakarta.validation.api)

    // Internal
    api(project(":backend:shared:asop-common"))

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
}
"@
    Create-File "$apiPath\build.gradle.kts" $buildContent
}

# ========================================
# ШАГ 2: Перенос DTO из asop-crypto-service в crypto-api
# ========================================
Write-Host "`n📦 ШАГ 2: Перенос DTO из asop-crypto-service в crypto-api" -ForegroundColor Yellow

$source = "backend\asop-crypto-service\src\main\kotlin\ru\asop\crypto\dto\TerminalCertRequest.kt"
$dest = "backend\api\crypto-api\src\main\kotlin\ru\asop\api\crypto\dto\request\TerminalCertRequest.kt"
Move-File $source $dest

$source = "backend\asop-crypto-service\src\main\kotlin\ru\asop\crypto\dto\TerminalCertResponse.kt"
$dest = "backend\api\crypto-api\src\main\kotlin\ru\asop\api\crypto\dto\response\TerminalCertResponse.kt"
Move-File $source $dest

# ========================================
# ШАГ 3: Создание API-интерфейсов
# ========================================
Write-Host "`n📦 ШАГ 3: Создание API-интерфейсов" -ForegroundColor Yellow

# gateway-api: CarrierApi
$carrierApiContent = @"
package ru.asop.api.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.carrier.CarrierCreateRequest
import ru.asop.api.gateway.dto.response.AcceptedResponse
import java.security.Principal

@RequestMapping("/api/v1/carriers")
interface CarrierApi {

    @PostMapping
    fun createCarrier(
        @Valid @RequestBody request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>>
}
"@
Create-File "backend\api\gateway-api\src\main\kotlin\ru\asop\api\gateway\controller\CarrierApi.kt" $carrierApiContent

# gateway-api: AcceptedResponse
$acceptedResponseContent = @"
package ru.asop.api.gateway.dto.response

import java.time.Instant
import java.util.UUID

data class AcceptedResponse(
    val eventId: UUID,
    val topic: String,
    val acceptedAt: Instant,
    val locationHint: String? = null
)
"@
Create-File "backend\api\gateway-api\src\main\kotlin\ru\asop\api\gateway\dto\response\AcceptedResponse.kt" $acceptedResponseContent

# crypto-api: TerminalCertApi
$terminalApiContent = @"
package ru.asop.api.crypto.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.crypto.dto.request.TerminalCertRequest
import ru.asop.api.crypto.dto.response.TerminalCertResponse

@RequestMapping("/api/v1/terminals")
interface TerminalCertApi {

    @PostMapping("/register")
    fun registerTerminal(
        @RequestBody request: TerminalCertRequest
    ): Mono<ResponseEntity<TerminalCertResponse>>

    @GetMapping("/root-ca", produces = ["application/x-pem-file"])
    fun getRootCaCertificate(): Mono<ResponseEntity<String>>

    @GetMapping("/root-ca/der", produces = ["application/octet-stream"])
    fun getRootCaCertificateDer(): Mono<ResponseEntity<ByteArray>>

    @GetMapping("/root-ca/public-key", produces = ["application/json"])
    fun getRootCaPublicKey(): Mono<ResponseEntity<Map<String, String>>>
}
"@
Create-File "backend\api\crypto-api\src\main\kotlin\ru\asop\api\crypto\controller\TerminalCertApi.kt" $terminalApiContent

# Пустые API-интерфейсы для остальных сервисов
foreach ($module in $apiModules) {
    if ($module.Name -in @("gateway-api", "crypto-api")) { continue }

    $className = ($module.Package.Substring(0,1).ToUpper() + $module.Package.Substring(1)) + "Api"
    $content = @"
package ru.asop.api.$($module.Package).controller

import org.springframework.web.bind.annotation.RequestMapping

@RequestMapping("/api/v1/$($module.Package)")
interface $className {
    // TODO: заполнить после реализации сервиса
}
"@
    Create-File "backend\api\$($module.Name)\src\main\kotlin\ru\asop\api\$($module.Package)\controller\$className.kt" $content
}

# ========================================
# ШАГ 4: Обновление settings.gradle.kts
# ========================================
Write-Host "`n📦 ШАГ 4: Обновление settings.gradle.kts" -ForegroundColor Yellow

$settingsPath = "settings.gradle.kts"
if (Test-Path $settingsPath) {
    $content = Get-Content $settingsPath -Raw

    # Проверяем, есть ли уже API-модули
    if ($content -notmatch ":backend:api:") {
        # Добавляем API-модули после shared модулей
        $apiIncludes = ($apiModules | ForEach-Object { "    `":backend:api:$($_.Name)`"" }) -join ",`n"

        $newContent = $content -replace
            '(\s*// ============ Backend services ============\s*include\s*\()',
            "`n// ============ API modules ============`ninclude(`n$apiIncludes`n)`n`n`$1"

        Create-File $settingsPath $newContent
    } else {
        Write-Host "  ℹ️  API-модули уже добавлены в settings.gradle.kts" -ForegroundColor DarkCyan
    }
}

# ========================================
# ШАГ 5: Обновление build.gradle.kts сервисов
# ========================================
Write-Host "`n📦 ШАГ 5: Добавление зависимостей на API-модули в сервисы" -ForegroundColor Yellow

foreach ($module in $apiModules) {
    $serviceBuildPath = "backend\$($module.Service)\build.gradle.kts"
    if (Test-Path $serviceBuildPath) {
        $content = Get-Content $serviceBuildPath -Raw
        $apiDep = "implementation(project(`":backend:api:$($module.Name)`"))"

        if ($content -notmatch [regex]::Escape($apiDep)) {
            # Добавляем после первой строки dependencies {
            $newContent = $content -replace
                '(dependencies\s*\{)',
                "`$1`n    // API-контракты`n    $apiDep"
            Create-File $serviceBuildPath $newContent
        } else {
            Write-Host "  ℹ️  $($module.Service): зависимость уже есть" -ForegroundColor DarkCyan
        }
    } else {
        Write-Host "  ⚠️  build.gradle.kts не найден для $($module.Service)" -ForegroundColor Red
    }
}

# ========================================
# ШАГ 6: Обновление импортов в контроллерах
# ========================================
Write-Host "`n📦 ШАГ 6: Обновление импортов в контроллерах" -ForegroundColor Yellow

# gateway-service: CarrierController
$carrierControllerPath = "backend\gateway-service\src\main\kotlin\ru\asop\gateway\controller\CarrierController.kt"
if (Test-Path $carrierControllerPath) {
    $content = Get-Content $carrierControllerPath -Raw

    # Заменяем импорт AcceptedResponse
    $content = $content -replace
        'import ru\.asop\.gateway\.dto\.AcceptedResponse',
        'import ru.asop.api.gateway.dto.response.AcceptedResponse'

    # Добавляем implements CarrierApi
    $content = $content -replace
        'class CarrierController\(',
        'class CarrierController('

    $content = $content -replace
        'class CarrierController\(\s*private val carrierCommandService: CarrierCommandService\s*\)',
        'class CarrierController(`n    private val carrierCommandService: CarrierCommandService`n) : ru.asop.api.gateway.controller.CarrierApi'

    Create-File $carrierControllerPath $content
    Write-Host "  ✓ Обновлён: CarrierController.kt" -ForegroundColor Green
}

# asop-crypto-service: TerminalCertController
$terminalControllerPath = "backend\asop-crypto-service\src\main\kotlin\ru\asop\crypto\controller\TerminalCertController.kt"
if (Test-Path $terminalControllerPath) {
    $content = Get-Content $terminalControllerPath -Raw

    # Заменяем импорты DTO
    $content = $content -replace
        'import ru\.asop\.crypto\.dto\.TerminalCertRequest',
        'import ru.asop.api.crypto.dto.request.TerminalCertRequest'

    $content = $content -replace
        'import ru\.asop\.crypto\.dto\.TerminalCertResponse',
        'import ru.asop.api.crypto.dto.response.TerminalCertResponse'

    # Добавляем implements TerminalCertApi
    $content = $content -replace
        'class TerminalCertController\(\s*private val terminalCertService: TerminalCertService,\s*private val rootCaService: RootCaService\s*\)',
        'class TerminalCertController(`n    private val terminalCertService: TerminalCertService,`n    private val rootCaService: RootCaService`n) : ru.asop.api.crypto.controller.TerminalCertApi'

    Create-File $terminalControllerPath $content
    Write-Host "  ✓ Обновлён: TerminalCertController.kt" -ForegroundColor Green
}

# ========================================
# ИТОГ
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
if ($WhatIf) {
    Write-Host " РЕЖИМ WHATIF — изменения НЕ выполнены" -ForegroundColor Yellow
    Write-Host " Для реального выполнения запусти без флага -WhatIf" -ForegroundColor Yellow
} else {
    Write-Host " МИГРАЦИЯ ЗАВЕРШЕНА!" -ForegroundColor Green
    Write-Host "`n Следующие шаги:" -ForegroundColor Cyan
    Write-Host "  1. Проверь структуру: .\get-project-tree.ps1" -ForegroundColor White
    Write-Host "  2. Собери проект: .\gradlew build" -ForegroundColor White
    Write-Host "  3. Если есть ошибки компиляции — исправь импорты вручную" -ForegroundColor White
    Write-Host "  4. Закоммить изменения: git add -A && git commit -m 'refactor: split API and implementation'" -ForegroundColor White
}
Write-Host "========================================`n" -ForegroundColor Cyan