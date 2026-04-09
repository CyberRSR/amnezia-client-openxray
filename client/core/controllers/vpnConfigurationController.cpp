#include "vpnConfigurationController.h"

#include "configurators/awg_configurator.h"
#include "configurators/cloak_configurator.h"
#include "configurators/ikev2_configurator.h"
#include "configurators/openvpn_configurator.h"
#include "configurators/shadowsocks_configurator.h"
#include "configurators/wireguard_configurator.h"
#include "configurators/xray_configurator.h"

#include <QJsonArray>
#include <QRegularExpression>

namespace
{
QString upsertOpenVpnDirective(QString config, const QString &directive, const QString &value)
{
    const QRegularExpression rx(QString(R"((?m)^\s*%1(?:\s+.*)?$)").arg(QRegularExpression::escape(directive)));
    if (value.isEmpty()) {
        config.replace(rx, "");
        return config;
    }

    if (config.contains(rx)) {
        config.replace(rx, QString("%1 %2").arg(directive, value));
    } else {
        if (!config.endsWith('\n') && !config.isEmpty()) {
            config.append('\n');
        }
        config.append(QString("%1 %2\n").arg(directive, value));
    }
    return config;
}

QString toggleOpenVpnDirective(QString config, const QString &directive, const bool enabled)
{
    const QRegularExpression rx(QString(R"((?m)^\s*%1(?:\s+.*)?$)").arg(QRegularExpression::escape(directive)));
    if (enabled) {
        if (!config.contains(rx)) {
            if (!config.endsWith('\n') && !config.isEmpty()) {
                config.append('\n');
            }
            config.append(QString("%1\n").arg(directive));
        }
    } else {
        config.replace(rx, "");
    }
    return config;
}

QString setOpenVpnRemotePort(QString config, const QString &port)
{
    if (port.isEmpty()) {
        return config;
    }

    QStringList updatedLines;
    updatedLines.reserve(config.split('\n').size());

    const auto lines = config.split('\n');
    const QRegularExpression remoteRx(R"(^(\s*remote\s+\S+)(?:\s+\d+)?(.*)$)");
    for (const auto &line : lines) {
        const auto match = remoteRx.match(line);
        if (match.hasMatch()) {
            updatedLines.append(QString("%1 %2%3").arg(match.captured(1), port, match.captured(2)));
        } else {
            updatedLines.append(line);
        }
    }

    return updatedLines.join('\n');
}

QString setManagedOpenVpnBlock(QString config, const QString &beginMarker, const QString &endMarker, const QString &content)
{
    const QRegularExpression blockRx(
            QString("%1[\\s\\S]*?%2\\n?").arg(QRegularExpression::escape(beginMarker), QRegularExpression::escape(endMarker)));
    config.replace(blockRx, "");

    if (content.trimmed().isEmpty()) {
        return config;
    }

    if (!config.endsWith('\n') && !config.isEmpty()) {
        config.append('\n');
    }

    config.append(QString("%1\n%2\n%3\n").arg(beginMarker, content.trimmed(), endMarker));
    return config;
}

QString applyOxrayOpenVpnOverrides(QString protocolConfigString, const QJsonObject &protocolSettings)
{
    if (protocolConfigString.isEmpty()) {
        return protocolConfigString;
    }

    QJsonObject configJson = QJsonDocument::fromJson(protocolConfigString.toUtf8()).object();
    QString rawConfig = configJson.value(amnezia::config_key::config).toString();
    if (rawConfig.isEmpty()) {
        return protocolConfigString;
    }

    if (protocolSettings.contains(amnezia::config_key::transport_proto)) {
        rawConfig = upsertOpenVpnDirective(rawConfig, "proto", protocolSettings.value(amnezia::config_key::transport_proto).toString());
    }
    if (protocolSettings.contains(amnezia::config_key::port)) {
        rawConfig = setOpenVpnRemotePort(rawConfig, protocolSettings.value(amnezia::config_key::port).toString());
    }
    if (protocolSettings.contains(amnezia::config_key::cipher)) {
        rawConfig = upsertOpenVpnDirective(rawConfig, "cipher", protocolSettings.value(amnezia::config_key::cipher).toString());
    }
    if (protocolSettings.contains(amnezia::config_key::hash)) {
        rawConfig = upsertOpenVpnDirective(rawConfig, "auth", protocolSettings.value(amnezia::config_key::hash).toString());
    }
    if (protocolSettings.contains(amnezia::config_key::ncp_disable)) {
        rawConfig = toggleOpenVpnDirective(
                rawConfig, amnezia::protocols::openvpn::ncpDisableString, protocolSettings.value(amnezia::config_key::ncp_disable).toBool());
    }
    if (protocolSettings.contains(amnezia::config_key::block_outside_dns)) {
        rawConfig = toggleOpenVpnDirective(rawConfig, "block-outside-dns",
                                           protocolSettings.value(amnezia::config_key::block_outside_dns).toBool());
    }
    if (protocolSettings.contains(amnezia::config_key::tls_auth) && !protocolSettings.value(amnezia::config_key::tls_auth).toBool()) {
        rawConfig.replace(QRegularExpression(R"((?m)^\s*tls-auth(?:\s+.*)?$)"), "");
        rawConfig.replace(QRegularExpression(R"(<tls-auth>[\s\S]*?</tls-auth>\n?)"), "");
    }

    rawConfig = setManagedOpenVpnBlock(rawConfig, "# OXRAY additional client config BEGIN",
                                       "# OXRAY additional client config END",
                                       protocolSettings.value(amnezia::config_key::additional_client_config).toString());

    configJson.insert(amnezia::config_key::config, rawConfig);
    return QJsonDocument(configJson).toJson();
}

QString applyOxrayXrayOverrides(QString protocolConfigString, const QJsonObject &protocolSettings)
{
    if (protocolConfigString.isEmpty()) {
        return protocolConfigString;
    }

    QJsonObject configJson = QJsonDocument::fromJson(protocolConfigString.toUtf8()).object();
    if (configJson.isEmpty()) {
        return protocolConfigString;
    }

    auto outbounds = configJson.value("outbounds").toArray();
    if (outbounds.isEmpty()) {
        return protocolConfigString;
    }
    auto outbound = outbounds.at(0).toObject();
    auto settings = outbound.value("settings").toObject();
    auto vnext = settings.value("vnext").toArray();
    if (vnext.isEmpty()) {
        return protocolConfigString;
    }
    auto remote = vnext.at(0).toObject();
    auto streamSettings = outbound.value("streamSettings").toObject();
    auto realitySettings = streamSettings.value("realitySettings").toObject();

    if (protocolSettings.contains(amnezia::config_key::port)) {
        remote.insert("port", protocolSettings.value(amnezia::config_key::port).toString().toInt());
    }
    if (protocolSettings.contains(amnezia::config_key::site)) {
        realitySettings.insert("serverName", protocolSettings.value(amnezia::config_key::site).toString());
    }

    vnext.replace(0, remote);
    settings.insert("vnext", vnext);
    outbound.insert("settings", settings);
    streamSettings.insert("realitySettings", realitySettings);
    outbound.insert("streamSettings", streamSettings);
    outbounds.replace(0, outbound);
    configJson.insert("outbounds", outbounds);

    return QJsonDocument(configJson).toJson();
}
} // namespace

VpnConfigurationsController::VpnConfigurationsController(const std::shared_ptr<Settings> &settings,
                                                         QSharedPointer<ServerController> serverController, QObject *parent)
    : QObject { parent }, m_settings(settings), m_serverController(serverController)
{
}

QScopedPointer<ConfiguratorBase> VpnConfigurationsController::createConfigurator(const Proto protocol)
{
    switch (protocol) {
    case Proto::OpenVpn: return QScopedPointer<ConfiguratorBase>(new OpenVpnConfigurator(m_settings, m_serverController));
    case Proto::ShadowSocks: return QScopedPointer<ConfiguratorBase>(new ShadowSocksConfigurator(m_settings, m_serverController));
    case Proto::Cloak: return QScopedPointer<ConfiguratorBase>(new CloakConfigurator(m_settings, m_serverController));
    case Proto::WireGuard: return QScopedPointer<ConfiguratorBase>(new WireguardConfigurator(m_settings, m_serverController, false));
    case Proto::Awg: return QScopedPointer<ConfiguratorBase>(new AwgConfigurator(m_settings, m_serverController));
    case Proto::Ikev2: return QScopedPointer<ConfiguratorBase>(new Ikev2Configurator(m_settings, m_serverController));
    case Proto::Xray: return QScopedPointer<ConfiguratorBase>(new XrayConfigurator(m_settings, m_serverController));
    case Proto::SSXray: return QScopedPointer<ConfiguratorBase>(new XrayConfigurator(m_settings, m_serverController));
    default: return QScopedPointer<ConfiguratorBase>();
    }
}

ErrorCode VpnConfigurationsController::createProtocolConfigForContainer(const ServerCredentials &credentials,
                                                                        const DockerContainer container, QJsonObject &containerConfig)
{
    ErrorCode errorCode = ErrorCode::NoError;

    if (container == DockerContainer::OXray) {
        return errorCode;
    }

    if (ContainerProps::containerService(container) == ServiceType::Other) {
        return errorCode;
    }

    for (Proto protocol : ContainerProps::protocolsForContainer(container)) {
        QJsonObject protocolConfig = containerConfig.value(ProtocolProps::protoToString(protocol)).toObject();

        auto configurator = createConfigurator(protocol);
        if (configurator.isNull()) {
            return ErrorCode::InternalError;
        }
        QString protocolConfigString = configurator->createConfig(credentials, container, containerConfig, errorCode);
        if (errorCode != ErrorCode::NoError) {
            return errorCode;
        }

        protocolConfig.insert(config_key::last_config, protocolConfigString);
        containerConfig.insert(ProtocolProps::protoToString(protocol), protocolConfig);
    }

    return errorCode;
}

ErrorCode VpnConfigurationsController::createProtocolConfigString(const bool isApiConfig, const QPair<QString, QString> &dns,
                                                                  const ServerCredentials &credentials, const DockerContainer container,
                                                                  const QJsonObject &containerConfig, const Proto protocol,
                                                                  QString &protocolConfigString)
{
    ErrorCode errorCode = ErrorCode::NoError;

    if (container == DockerContainer::OXray) {
        return ErrorCode::InternalError;
    }

    if (ContainerProps::containerService(container) == ServiceType::Other) {
        return errorCode;
    }

    auto configurator = createConfigurator(protocol);
    if (configurator.isNull()) {
        return ErrorCode::InternalError;
    }

    protocolConfigString = configurator->createConfig(credentials, container, containerConfig, errorCode);
    if (errorCode != ErrorCode::NoError) {
        return errorCode;
    }
    protocolConfigString = configurator->processConfigWithExportSettings(dns, isApiConfig, protocolConfigString);

    return errorCode;
}

QJsonObject VpnConfigurationsController::createVpnConfiguration(const QPair<QString, QString> &dns, const QJsonObject &serverConfig,
                                                                const QJsonObject &containerConfig, const DockerContainer container)
{
    QJsonObject vpnConfiguration {};

    if (ContainerProps::containerService(container) == ServiceType::Other) {
        return vpnConfiguration;
    }

    bool isApiConfig = serverConfig.value(config_key::configVersion).toInt();

    if (container == DockerContainer::OXray) {
        auto openVpnProtocolConfig = containerConfig.value(config_key::openvpn).toObject();
        auto xrayProtocolConfig = containerConfig.value(config_key::xray).toObject();

        QString openVpnConfigString = applyOxrayOpenVpnOverrides(openVpnProtocolConfig.value(config_key::last_config).toString(),
                                                                 openVpnProtocolConfig);
        QString xrayConfigString = applyOxrayXrayOverrides(xrayProtocolConfig.value(config_key::last_config).toString(),
                                                           xrayProtocolConfig);

        OpenVpnConfigurator openVpnConfigurator(m_settings, m_serverController);
        XrayConfigurator xrayConfigurator(m_settings, m_serverController);

        openVpnConfigString = openVpnConfigurator.processConfigWithLocalSettings(dns, isApiConfig, openVpnConfigString);
        xrayConfigString = xrayConfigurator.processConfigWithLocalSettings(dns, isApiConfig, xrayConfigString);

        vpnConfiguration.insert(ProtocolProps::key_proto_config_data(Proto::OpenVpn),
                                QJsonDocument::fromJson(openVpnConfigString.toUtf8()).object());
        vpnConfiguration.insert(ProtocolProps::key_proto_config_data(Proto::Xray),
                                QJsonDocument::fromJson(xrayConfigString.toUtf8()).object());

        vpnConfiguration[config_key::vpnproto] = ProtocolProps::protoToString(Proto::OXray);
        vpnConfiguration[config_key::dns1] = dns.first;
        vpnConfiguration[config_key::dns2] = dns.second;
        vpnConfiguration[config_key::hostName] = serverConfig.value(config_key::hostName).toString();
        vpnConfiguration[config_key::description] = serverConfig.value(config_key::description).toString();
        vpnConfiguration[config_key::configVersion] = serverConfig.value(config_key::configVersion).toInt();
        return vpnConfiguration;
    }

    for (ProtocolEnumNS::Proto proto : ContainerProps::protocolsForContainer(container)) {
        if (isApiConfig && container == DockerContainer::Cloak && proto == ProtocolEnumNS::Proto::ShadowSocks) {
            continue;
        }

        QString protocolConfigString =
                containerConfig.value(ProtocolProps::protoToString(proto)).toObject().value(config_key::last_config).toString();

        auto configurator = createConfigurator(proto);
        protocolConfigString = configurator->processConfigWithLocalSettings(dns, isApiConfig, protocolConfigString);

        QJsonObject vpnConfigData = QJsonDocument::fromJson(protocolConfigString.toUtf8()).object();
        if (ContainerProps::isAwgContainer(container) || container == DockerContainer::WireGuard) {
            // add mtu for old configs
            if (vpnConfigData[config_key::mtu].toString().isEmpty()) {
                vpnConfigData[config_key::mtu] =
                        ContainerProps::isAwgContainer(container) ? protocols::awg::defaultMtu :
                        protocols::wireguard::defaultMtu;
            }
        }

        vpnConfiguration.insert(ProtocolProps::key_proto_config_data(proto), vpnConfigData);
    }

    Proto proto = ContainerProps::defaultProtocol(container);
    vpnConfiguration[config_key::vpnproto] = ProtocolProps::protoToString(proto);

    vpnConfiguration[config_key::dns1] = dns.first;
    vpnConfiguration[config_key::dns2] = dns.second;

    vpnConfiguration[config_key::hostName] = serverConfig.value(config_key::hostName).toString();
    vpnConfiguration[config_key::description] = serverConfig.value(config_key::description).toString();

    vpnConfiguration[config_key::configVersion] = serverConfig.value(config_key::configVersion).toInt();
    // TODO: try to get hostName, port, description for 3rd party configs
    // vpnConfiguration[config_key::port] = ...;

    return vpnConfiguration;
}

void VpnConfigurationsController::updateContainerConfigAfterInstallation(const DockerContainer container, QJsonObject &containerConfig,
                                                                         const QString &stdOut)
{
    Proto mainProto = ContainerProps::defaultProtocol(container);

    if (container == DockerContainer::TorWebSite) {
        QJsonObject protocol = containerConfig.value(ProtocolProps::protoToString(mainProto)).toObject();

        qDebug() << "amnezia-tor onions" << stdOut;

        QString onion = stdOut;
        onion.replace("\n", "");
        protocol.insert(config_key::site, onion);

        containerConfig.insert(ProtocolProps::protoToString(mainProto), protocol);
    }
}
