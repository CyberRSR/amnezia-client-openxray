#include "owgConfigurator.h"

#include "core/configurators/openVpnConfigurator.h"
#include "core/models/protocols/owgProtocolConfig.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

OwgConfigurator::OwgConfigurator(SshSession* sshSession, QObject *parent)
    : ConfiguratorBase(sshSession, parent)
{
}

ProtocolConfig OwgConfigurator::createConfig(const ServerCredentials &credentials, DockerContainer container,
                                             const ContainerConfig &containerConfig,
                                             const DnsSettings &dnsSettings,
                                             ErrorCode &errorCode)
{
    Q_UNUSED(credentials);
    Q_UNUSED(container);
    Q_UNUSED(containerConfig);
    Q_UNUSED(dnsSettings);
    errorCode = ErrorCode::NotSupportedOnThisPlatform;
    return OwgProtocolConfig {};
}

ProtocolConfig OwgConfigurator::processConfigWithLocalSettings(const ConnectionSettings &settings,
                                                               ProtocolConfig protocolConfig)
{
    auto* owgConfig = protocolConfig.as<OwgProtocolConfig>();
    if (!owgConfig) {
        return protocolConfig;
    }

    OpenVpnConfigurator openVpnConfigurator(nullptr);
    ProtocolConfig openVpnConfig = owgConfig->openVpnConfig;
    openVpnConfig = openVpnConfigurator.processConfigWithLocalSettings(settings, openVpnConfig);
    if (auto* processedOpenVpn = openVpnConfig.as<OpenVpnProtocolConfig>()) {
        owgConfig->openVpnConfig = *processedOpenVpn;
    }

    owgConfig->awgConfig.serverConfig.protocolVersion = protocols::awg::awgV2;
    if (owgConfig->awgConfig.clientConfig.has_value()) {
        owgConfig->awgConfig.clientConfig->isObfuscationEnabled = true;
    }

    return protocolConfig;
}

ProtocolConfig OwgConfigurator::processConfigWithExportSettings(const ExportSettings &settings,
                                                                ProtocolConfig protocolConfig)
{
    auto* owgConfig = protocolConfig.as<OwgProtocolConfig>();
    if (!owgConfig) {
        return protocolConfig;
    }

    OpenVpnConfigurator openVpnConfigurator(nullptr);
    ProtocolConfig openVpnConfig = owgConfig->openVpnConfig;
    openVpnConfig = openVpnConfigurator.processConfigWithExportSettings(settings, openVpnConfig);
    if (auto* processedOpenVpn = openVpnConfig.as<OpenVpnProtocolConfig>()) {
        owgConfig->openVpnConfig = *processedOpenVpn;
    }

    owgConfig->awgConfig.serverConfig.protocolVersion = protocols::awg::awgV2;
    return protocolConfig;
}
