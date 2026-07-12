#include "wwgConfigurator.h"

#include "core/models/protocols/wwgProtocolConfig.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

WwgConfigurator::WwgConfigurator(SshSession *sshSession, QObject *parent)
    : ConfiguratorBase(sshSession, parent)
{
}

ProtocolConfig WwgConfigurator::createConfig(const ServerCredentials &credentials,
                                             DockerContainer container,
                                             const ContainerConfig &containerConfig,
                                             const DnsSettings &dnsSettings,
                                             ErrorCode &errorCode)
{
    Q_UNUSED(credentials);
    Q_UNUSED(container);
    Q_UNUSED(containerConfig);
    Q_UNUSED(dnsSettings);
    errorCode = ErrorCode::NotSupportedOnThisPlatform;
    return WwgProtocolConfig {};
}

ProtocolConfig WwgConfigurator::processConfigWithLocalSettings(const ConnectionSettings &settings,
                                                               ProtocolConfig protocolConfig)
{
    Q_UNUSED(settings);
    if (auto *config = protocolConfig.as<WwgProtocolConfig>()) {
        forceV2(*config);
    }
    return protocolConfig;
}

ProtocolConfig WwgConfigurator::processConfigWithExportSettings(const ExportSettings &settings,
                                                                ProtocolConfig protocolConfig)
{
    Q_UNUSED(settings);
    if (auto *config = protocolConfig.as<WwgProtocolConfig>()) {
        forceV2(*config);
    }
    return protocolConfig;
}

void WwgConfigurator::forceV2(WwgProtocolConfig &config)
{
    const auto apply = [](AwgProtocolConfig &awg) {
        awg.serverConfig.protocolVersion = protocols::awg::awgV2;
        if (awg.clientConfig.has_value()) {
            awg.clientConfig->isObfuscationEnabled = true;
        }
    };
    apply(config.underlayAwgConfig);
    apply(config.overlayAwgConfig);
}
