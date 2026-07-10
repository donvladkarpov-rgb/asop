package ru.asop.crypto.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asop.crypto")
data class RootCaProperties(
    val rootCa: RootCaConfig,
    val intermediateCa: IntermediateCaConfig,
    val serverCert: ServerCertConfig = ServerCertConfig(),
    val terminalCert: TerminalCertConfig,
    val driverCert: DriverCertConfig,
    val smartCardCert: SmartCardCertConfig,
    val crl: CrlConfig
)

data class ServerCertConfig(
    val keystorePath: String = "./data/server.p12",
    val keystorePassword: String = "changeit",
    val keyAlias: String = "crypto-service",
    val validityYears: Int = 5,
    val commonName: String = "crypto-service",
    val dnsNames: List<String> = listOf("crypto-service", "localhost")
)

data class RootCaConfig(
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val validityYears: Int,
    val dn: String
)

data class IntermediateCaConfig(
    val validityYears: Int,
    val dn: String,
    val keystorePath: String = "./data/intermediate-ca.p12",
    val keystorePassword: String = "changeit",
    val keyAlias: String = "asop-intermediate-ca"
)

data class TerminalCertConfig(
    val validityYears: Int,
    val dnTemplate: String
)

data class DriverCertConfig(
    val validityYears: Int,
    val dnTemplate: String
)

data class SmartCardCertConfig(
    val validityYears: Int,
    val dnTemplates: Map<String, String>
)

data class CrlConfig(
    val crlPath: String,
    val validityDays: Int
)