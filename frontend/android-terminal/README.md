# ASOP Terminal — Android

Android-приложение для терминала ASOP. Аутентификация через mTLS (X.509 сертификат crypto-service), связь с gateway через OkHttp + Retrofit.

## Сборка

```bash
# Требуется Android SDK 35, JDK 17+
./gradlew :app:assembleDebug

# APK будет в:
# app/build/outputs/apk/debug/app-debug.apk
```

## Архитектура

```
Терминал (Android)
  │ POST /api/v1/terminals/register  (mTLS — X.509 cert)
  │ GET  /api/v1/terminals/{id}
  ▼
Gateway (10.0.2.2:8080)
  │ SecurityConfig Chain 1 @Order(1)
  │ mTLS → CN → X-Terminal-Serial header
  ▼
terminal-service (port 8084)
```

## Поток provisioning

1. **First launch** — генерируется EC P-256 keypair в AndroidKeyStore
2. **Запрос сертификата** — публичный ключ отправляется в crypto-service (через прямой вызов на порт 8081 для dev)
3. **Сертификат** сохраняется в SharedPreferences + устанавливается в Android KeyChain
4. **Регистрация** — терминал регистрируется через gateway (`POST /api/v1/terminals/register`) с mTLS

## Режимы подключения

| Окружение | Gateway | Crypto-service |
|-----------|---------|----------------|
| Эмулятор (dev) | `https://10.0.2.2:8080` | `https://10.0.2.2:8081` |
 | Реальное устройство (LAN) | `https://<host>:8080` | `https://<host>:8081` |

Настройка base URL — в `app/build.gradle.kts` (buildConfigField).

## Зависимости

- Jetpack Compose + Material3
- Hilt DI
- OkHttp + Retrofit + Moshi
- Bouncy Castle (bcpkix) для PEM
- AndroidX Navigation Compose

## Структура

```
app/src/main/java/ru/asop/terminal/
├── AsopTerminalApp.kt         # Application (@HiltAndroidApp)
├── MainActivity.kt            # Single Activity + Compose
├── cert/
│   └── MtlsManager.kt         # AndroidKeyStore, EC keypair, cert import/export
├── di/
│   └── AppModule.kt           # Hilt DI: OkHttp, Retrofit, Moshi
├── network/
│   ├── GatewayApi.kt          # Retrofit interface для gateway
│   ├── CryptoApi.kt           # Retrofit interface для crypto-service
│   └── models/
│       └── TerminalModels.kt  # DTO: запросы/ответы
├── service/
│   └── CertificateService.kt  # Provisioning: keygen → cert request → store
└── ui/
    ├── TerminalNavHost.kt     # Navigation (3 экрана)
    ├── theme/
    │   └── Theme.kt           # Material3 theme
    └── screen/
        ├── TerminalViewModel.kt
        ├── ProvisioningScreen.kt   # Первый запуск: получение сертификата
        ├── RegistrationScreen.kt   # Регистрация терминала
        └── MainScreen.kt           # Статус и ожидание команд
```
