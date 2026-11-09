#include "wwgConfigurator.h"

#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

WwgConfigurator::WwgConfigurator(SshSession *sshSession, QObject *parent)
    : ConfiguratorBase(sshSession, parent)
{
}

ProtocolConfig WwgConfigurator::createConfig(const ServerCredentials &credentials, DockerContainer container,
                                             const ContainerConfig &containerConfig, const DnsSettings &dnsSettings,
                                             ErrorCode &errorCode)
{
    Q_UNUSED(credentials);
    Q_UNUSED(container);
    Q_UNUSED(containerConfig);
    Q_UNUSED(dnsSettings);

    errorCode = ErrorCode::NotImplementedError;
    return ProtocolConfig {};
}

ProtocolConfig WwgConfigurator::processConfigWithLocalSettings(const ConnectionSettings &settings,
                                                               ProtocolConfig protocolConfig)
{
    Q_UNUSED(settings);
    if (auto *config = protocolConfig.as<WwgProtocolConfig>()) {
        normalize(*config);
    }
    return protocolConfig;
}

ProtocolConfig WwgConfigurator::processConfigWithExportSettings(const ExportSettings &settings,
                                                                ProtocolConfig protocolConfig)
{
    Q_UNUSED(settings);
    if (auto *config = protocolConfig.as<WwgProtocolConfig>()) {
        normalize(*config);
    }
    return protocolConfig;
}

void WwgConfigurator::normalize(WwgProtocolConfig &config)
{
    const auto apply = [](AwgProtocolConfig &awg) {
        if (awg.serverConfig.protocolVersion.isEmpty()) {
            awg.serverConfig.protocolVersion = protocols::awg::awgV2;
        }
    };
    apply(config.underlayAwgConfig);
    apply(config.overlayAwgConfig);
}
