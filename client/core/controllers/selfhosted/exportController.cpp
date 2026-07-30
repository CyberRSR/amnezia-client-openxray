#include "exportController.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QStringList>
#include <algorithm>

#include "core/configurators/configuratorBase.h"
#include "core/utils/selfhosted/sshSession.h"
#include "core/utils/qrCodeUtils.h"
#include "core/utils/serialization/serialization.h"
#include "core/utils/protocolEnum.h"
#include "core/utils/serverConfigUtils.h"
#include "core/protocols/protocolUtils.h"
#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"
#include "core/models/selfhosted/selfHostedAdminServerConfig.h"
#include "core/models/selfhosted/selfHostedUserServerConfig.h"
#include "core/models/selfhosted/nativeServerConfig.h"
#include "core/models/containerConfig.h"
#include "core/models/protocolConfig.h"

using namespace amnezia;

namespace
{
bool hasRequiredAwgV2Fields(const QJsonObject &config)
{
    const QStringList requiredFields = {
        configKey::junkPacketCount,
        configKey::junkPacketMinSize,
        configKey::junkPacketMaxSize,
        configKey::initPacketJunkSize,
        configKey::responsePacketJunkSize,
        configKey::cookieReplyPacketJunkSize,
        configKey::transportPacketJunkSize,
        configKey::initPacketMagicHeader,
        configKey::responsePacketMagicHeader,
        configKey::underloadPacketMagicHeader,
        configKey::transportPacketMagicHeader,
    };

    return std::all_of(requiredFields.begin(), requiredFields.end(), [&config](const QString &field) {
        return !config.value(field).toString().trimmed().isEmpty();
    });
}

bool isStoredContainerExportable(const ContainerConfig &containerConfig)
{
    if (containerConfig.container == DockerContainer::None) {
        return false;
    }
    if (!ContainerUtils::isShareable(containerConfig.container)) {
        return false;
    }
    if (ContainerUtils::containerService(containerConfig.container) != ServiceType::Vpn) {
        return false;
    }
    if (!containerConfig.protocolConfig.hasClientConfig()) {
        return false;
    }

    if (containerConfig.container == DockerContainer::OWG) {
        const OwgProtocolConfig *owg = containerConfig.getOwgProtocolConfig();
        if (!owg || !owg->awgConfig.clientConfig.has_value()) {
            return false;
        }
        if (owg->awgConfig.serverConfig.protocolVersion != protocols::awg::awgV2) {
            return false;
        }
        if (!hasRequiredAwgV2Fields(owg->awgConfig.clientConfig->toJson())) {
            return false;
        }
    }

    if (containerConfig.container == DockerContainer::WWG) {
        const WwgProtocolConfig *wwg = containerConfig.getWwgProtocolConfig();
        if (!wwg || !wwg->isValid()) {
            return false;
        }
    }

    return true;
}

SelfHostedUserServerConfig buildSingleContainerUserConfig(const QString &description,
                                                          const QString &displayName,
                                                          const QString &hostName,
                                                          const QString &dns1,
                                                          const QString &dns2,
                                                          DockerContainer container,
                                                          const ContainerConfig &containerConfig)
{
    SelfHostedUserServerConfig exportConfig;
    exportConfig.description = description;
    exportConfig.displayName = displayName;
    exportConfig.hostName = hostName;
    exportConfig.defaultContainer = container;
    exportConfig.dns1 = dns1;
    exportConfig.dns2 = dns2;

    ContainerConfig storedContainerConfig = containerConfig;
    storedContainerConfig.container = container;
    exportConfig.containers.insert(container, storedContainerConfig);

    return exportConfig;
}
}

ExportController::ExportController(SecureServersRepository* serversRepository,
                                   SecureAppSettingsRepository* appSettingsRepository,
                                   QObject *parent)
    : QObject(parent),
      m_serversRepository(serversRepository),
      m_appSettingsRepository(appSettingsRepository)
{
}

ExportController::ExportResult ExportController::generateFullAccessConfig(const QString &serverId)
{
    ExportResult result;

    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    for (auto it = adminConfig->containers.begin(); it != adminConfig->containers.end(); ++it) {
        it.value().protocolConfig.clearClientConfig();
    }

    QJsonObject serverJson = adminConfig->toJson();
    QByteArray compressedConfig = QJsonDocument(serverJson).toJson();
    compressedConfig = qCompress(compressedConfig, 8);
    result.config = generateVpnUrl(compressedConfig);
    result.qrCodes = generateQrCodesFromConfig(compressedConfig);

    return result;
}

ExportController::ExportResult ExportController::generateConnectionConfig(const QString &serverId, int containerIndex, const QString &clientName)
{
    ExportResult result;

    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    const ServerCredentials credentials = adminConfig->credentials();
    if (!credentials.isValid()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    ContainerConfig containerConfig = adminConfig->containerConfig(container);

    if (ContainerUtils::containerService(container) != ServiceType::Other) {
        SshSession sshSession;
        Proto protocol = ContainerUtils::defaultProtocol(container);

        DnsSettings dnsSettings = {
            m_appSettingsRepository->primaryDns(),
            m_appSettingsRepository->secondaryDns()
        };

        auto configurator = ConfiguratorBase::create(protocol, &sshSession);
        ProtocolConfig newProtocolConfig = configurator->createConfig(credentials, container, containerConfig, dnsSettings, result.errorCode);
        if (result.errorCode != ErrorCode::NoError) {
            return result;
        }

        containerConfig.protocolConfig = newProtocolConfig;
        
        QString clientId = newProtocolConfig.clientId();
        if (!clientId.isEmpty()) {
            emit appendClientRequested(serverId, clientId, clientName, container);
        }
    }

    const QPair<QString, QString> dns = adminConfig->getDnsPair(m_appSettingsRepository->useAmneziaDns(),
                                                               m_appSettingsRepository->primaryDns(),
                                                               m_appSettingsRepository->secondaryDns());

    adminConfig->containers.clear();
    adminConfig->containers[container] = containerConfig;
    adminConfig->defaultContainer = container;
    adminConfig->userName.clear();
    adminConfig->password.clear();
    adminConfig->port = 0;

    adminConfig->dns1 = dns.first;
    adminConfig->dns2 = dns.second;

    QJsonObject serverJson = adminConfig->toJson();
    QByteArray compressedConfig = QJsonDocument(serverJson).toJson();
    compressedConfig = qCompress(compressedConfig, 8);
    result.config = generateVpnUrl(compressedConfig);
    result.qrCodes = generateQrCodesFromConfig(compressedConfig);

    return result;
}

ExportController::ExportResult ExportController::generateStoredConnectionConfig(const QString &serverId, int containerIndex)
{
    ExportResult result;

    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    if (container == DockerContainer::None) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }

    SelfHostedUserServerConfig exportConfig;
    const serverConfigUtils::ConfigType kind = m_serversRepository->serverKind(serverId);

    switch (kind) {
    case serverConfigUtils::SelfHostedAdmin: {
        auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
        if (!adminConfig.has_value() || !adminConfig->containers.contains(container)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        const ContainerConfig containerConfig = adminConfig->containerConfig(container);
        if (!isStoredContainerExportable(containerConfig)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        exportConfig = buildSingleContainerUserConfig(adminConfig->description, adminConfig->displayName,
                                                      adminConfig->hostName, adminConfig->dns1, adminConfig->dns2,
                                                      container, containerConfig);
        break;
    }
    case serverConfigUtils::SelfHostedUser: {
        auto userConfig = m_serversRepository->selfHostedUserConfig(serverId);
        if (!userConfig.has_value() || !userConfig->containers.contains(container)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        const ContainerConfig containerConfig = userConfig->containerConfig(container);
        if (!isStoredContainerExportable(containerConfig)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        exportConfig = buildSingleContainerUserConfig(userConfig->description, userConfig->displayName,
                                                      userConfig->hostName, userConfig->dns1, userConfig->dns2,
                                                      container, containerConfig);
        break;
    }
    case serverConfigUtils::Native: {
        auto nativeConfig = m_serversRepository->nativeConfig(serverId);
        if (!nativeConfig.has_value() || !nativeConfig->containers.contains(container)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        const ContainerConfig containerConfig = nativeConfig->containerConfig(container);
        if (!isStoredContainerExportable(containerConfig)) {
            result.errorCode = ErrorCode::InternalError;
            return result;
        }

        exportConfig = buildSingleContainerUserConfig(nativeConfig->description, nativeConfig->displayName,
                                                      nativeConfig->hostName, nativeConfig->dns1, nativeConfig->dns2,
                                                      container, containerConfig);
        break;
    }
    default:
        result.errorCode = ErrorCode::InternalError;
        return result;
    }

    QJsonObject serverJson = exportConfig.toJson();
    QByteArray compressedConfig = QJsonDocument(serverJson).toJson();
    compressedConfig = qCompress(compressedConfig, 8);
    result.config = generateVpnUrl(compressedConfig);
    result.nativeConfigString = exportConfig.containerConfig(container).protocolConfig.nativeConfig();
    result.qrCodes = generateQrCodesFromConfig(compressedConfig);

    return result;
}

ExportController::NativeConfigResult ExportController::generateNativeConfig(const QString &serverId, DockerContainer container,
                                                                             const ContainerConfig &containerConfig,
                                                                             const QString &clientName)
{
    NativeConfigResult result;

    if (ContainerUtils::containerService(container) == ServiceType::Other) {
        return result;
    }

    Proto protocol = ContainerUtils::defaultProtocol(container);

    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    const ServerCredentials credentials = adminConfig->credentials();
    if (!credentials.isValid()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    const QPair<QString, QString> dns = adminConfig->getDnsPair(m_appSettingsRepository->useAmneziaDns(),
                                                                m_appSettingsRepository->primaryDns(),
                                                                m_appSettingsRepository->secondaryDns());

    ContainerConfig modifiedContainerConfig = containerConfig;
    modifiedContainerConfig.container = container;

    DnsSettings dnsSettings = {
        m_appSettingsRepository->primaryDns(),
        m_appSettingsRepository->secondaryDns()
    };

    SshSession sshSession;
    auto configurator = ConfiguratorBase::create(protocol, &sshSession);

    ProtocolConfig newProtocolConfig = configurator->createConfig(credentials, container, modifiedContainerConfig, dnsSettings, result.errorCode);
    if (result.errorCode != ErrorCode::NoError) {
        return result;
    }

    ExportSettings exportSettings = { { dns.first, dns.second } };
    ProtocolConfig processedConfig = configurator->processConfigWithExportSettings(exportSettings, newProtocolConfig);

    if (protocol == Proto::OpenVpn || protocol == Proto::WireGuard || protocol == Proto::Awg) {
        result.jsonNativeConfig[configKey::config] = processedConfig.nativeConfig();
    } else {
        result.jsonNativeConfig = QJsonDocument::fromJson(processedConfig.nativeConfig().toUtf8()).object();
    }

    if (protocol == Proto::OpenVpn || protocol == Proto::WireGuard || protocol == Proto::Awg || protocol == Proto::Xray) {
        QString clientId = newProtocolConfig.clientId();
        if (!clientId.isEmpty()) {
            emit appendClientRequested(serverId, clientId, clientName, container);
        }
    }
    return result;
}

ExportController::ExportResult ExportController::generateOpenVpnConfig(const QString &serverId, const QString &clientName)
{
    ExportResult result;

    DockerContainer container = DockerContainer::OpenVpn;
    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    ContainerConfig containerConfig = adminConfig->containerConfig(container);

    auto nativeResult = generateNativeConfig(serverId, container, containerConfig, clientName);
    if (nativeResult.errorCode != ErrorCode::NoError) {
        result.errorCode = nativeResult.errorCode;
        return result;
    }

    QStringList lines = nativeResult.jsonNativeConfig.value(configKey::config).toString().replace("\r", "").split("\n");
    for (const QString &line : std::as_const(lines)) {
        result.config.append(line + "\n");
    }

    result.qrCodes = generateQrCodesFromConfig(result.config.toUtf8());
    return result;
}

ExportController::ExportResult ExportController::generateWireGuardConfig(const QString &serverId, const QString &clientName)
{
    ExportResult result;

    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    ContainerConfig containerConfig = adminConfig->containerConfig(DockerContainer::WireGuard);

    auto nativeResult = generateNativeConfig(serverId, DockerContainer::WireGuard, containerConfig, clientName);
    if (nativeResult.errorCode != ErrorCode::NoError) {
        result.errorCode = nativeResult.errorCode;
        return result;
    }

    QStringList lines = nativeResult.jsonNativeConfig.value(configKey::config).toString().replace("\r", "").split("\n");
    for (const QString &line : std::as_const(lines)) {
        result.config.append(line + "\n");
    }

    result.qrCodes << generateSingleQrCode(result.config.toUtf8());
    return result;
}

ExportController::ExportResult ExportController::generateAwgConfig(const QString &serverId, int containerIndex, const QString &clientName)
{
    ExportResult result;

    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    if (container != DockerContainer::Awg && container != DockerContainer::Awg2) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    ContainerConfig containerConfig = adminConfig->containerConfig(container);

    auto nativeResult = generateNativeConfig(serverId, container, containerConfig, clientName);
    if (nativeResult.errorCode != ErrorCode::NoError) {
        result.errorCode = nativeResult.errorCode;
        return result;
    }

    QStringList lines = nativeResult.jsonNativeConfig.value(configKey::config).toString().replace("\r", "").split("\n");
    for (const QString &line : std::as_const(lines)) {
        result.config.append(line + "\n");
    }

    result.qrCodes << generateSingleQrCode(result.config.toUtf8());
    return result;
}


ExportController::ExportResult ExportController::generateXrayConfig(const QString &serverId, const QString &clientName)
{
    ExportResult result;

    auto adminConfig = m_serversRepository->selfHostedAdminConfig(serverId);
    if (!adminConfig.has_value()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }
    ContainerConfig containerConfig = adminConfig->containerConfig(DockerContainer::Xray);

    auto nativeResult = generateNativeConfig(serverId, DockerContainer::Xray, containerConfig, clientName);
    if (nativeResult.errorCode != ErrorCode::NoError) {
        result.errorCode = nativeResult.errorCode;
        return result;
    }

    QStringList lines = QString(QJsonDocument(nativeResult.jsonNativeConfig).toJson()).replace("\r", "").split("\n");
    for (const QString &line : std::as_const(lines)) {
        result.config.append(line + "\n");
    }

    // Parse the Xray data to extract VLESS parameters and generate string
    QJsonObject xrayConfig = nativeResult.jsonNativeConfig;
    QJsonArray outbounds = xrayConfig.value(amnezia::protocols::xray::outbounds).toArray();

    if (outbounds.isEmpty()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }

    QJsonObject outbound = outbounds[0].toObject();
    QJsonObject settings = outbound.value(amnezia::protocols::xray::settings).toObject();
    QJsonObject streamSettings = outbound.value(amnezia::protocols::xray::streamSettings).toObject();

    QJsonArray vnext = settings.value(amnezia::protocols::xray::vnext).toArray();
    if (vnext.isEmpty()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }

    QJsonObject server = vnext[0].toObject();
    QJsonArray users = server.value(amnezia::protocols::xray::users).toArray();
    if (users.isEmpty()) {
        result.errorCode = ErrorCode::InternalError;
        return result;
    }

    QJsonObject user = users[0].toObject();

    amnezia::serialization::VlessServerObject vlessServer;
    vlessServer.address = server.value(amnezia::protocols::xray::address).toString();
    vlessServer.port = server.value(amnezia::protocols::xray::port).toInt();
    vlessServer.id = user.value(amnezia::protocols::xray::id).toString();
    vlessServer.flow = user.value(amnezia::protocols::xray::flow).toString("xtls-rprx-vision");
    vlessServer.encryption = user.value(amnezia::protocols::xray::encryption).toString("none");

    vlessServer.network = streamSettings.value(amnezia::protocols::xray::network).toString("tcp");
    vlessServer.security = streamSettings.value(amnezia::protocols::xray::security).toString("reality");

    if (vlessServer.security == "reality") {
        QJsonObject realitySettings = streamSettings.value(amnezia::protocols::xray::realitySettings).toObject();
        vlessServer.serverName = realitySettings.value(amnezia::protocols::xray::serverName).toString();
        vlessServer.publicKey = realitySettings.value(amnezia::protocols::xray::publicKey).toString();
        vlessServer.shortId = realitySettings.value(amnezia::protocols::xray::shortId).toString();
        vlessServer.fingerprint = realitySettings.value(amnezia::protocols::xray::fingerprint).toString("chrome");
        vlessServer.spiderX = realitySettings.value(amnezia::protocols::xray::spiderX).toString("");
    } else if (vlessServer.security == "tls") {
        QJsonObject tlsSettings = streamSettings.value("tlsSettings").toObject();
        vlessServer.serverName = tlsSettings.value(amnezia::protocols::xray::serverName).toString();
        vlessServer.fingerprint = tlsSettings.value(amnezia::protocols::xray::fingerprint).toString();
        // alpn: serialize array back to comma-separated for VLESS URI
        QJsonArray alpnArr = tlsSettings.value("alpn").toArray();
        QStringList alpnList;
        for (const QJsonValue &v : alpnArr) {
            alpnList << v.toString();
        }
        // alpn goes into vless URI query param — handled by Serialize via serverName/alpn fields
        // VlessServerObject doesn't have alpn field, so we embed in serverName if needed
    }

    result.nativeConfigString = amnezia::serialization::vless::Serialize(vlessServer, "AmneziaVPN");

    return result;
}

void ExportController::updateClientManagementModel(const QString &serverId, int containerIndex)
{
    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    emit updateClientsRequested(serverId, container);
}

void ExportController::revokeConfig(int row, const QString &serverId, int containerIndex)
{
    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    emit revokeClientRequested(serverId, row, container);
}

void ExportController::renameClient(int row, const QString &clientName, const QString &serverId, int containerIndex)
{
    DockerContainer container = static_cast<DockerContainer>(containerIndex);
    emit renameClientRequested(serverId, row, clientName, container);
}

QString ExportController::generateVpnUrl(const QByteArray &compressedConfig)
{
    return QString("vpn://%1").arg(QString(compressedConfig.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals)));
}

QList<QString> ExportController::generateQrCodesFromConfig(const QByteArray &data)
{
    return qrCodeUtils::generateQrCodeImageSeries(data);
}

QString ExportController::generateSingleQrCode(const QByteArray &data)
{
    auto qr = qrCodeUtils::generateQrCode(data);
    return qrCodeUtils::svgToBase64(QString::fromStdString(toSvgString(qr, 1)));
}
