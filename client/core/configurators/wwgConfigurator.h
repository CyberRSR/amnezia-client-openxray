#ifndef WWGCONFIGURATOR_H
#define WWGCONFIGURATOR_H

#include "configuratorBase.h"
#include "core/models/protocols/wwgProtocolConfig.h"

class WwgConfigurator : public ConfiguratorBase
{
    Q_OBJECT
public:
    explicit WwgConfigurator(SshSession *sshSession, QObject *parent = nullptr);

    // WWG profiles are always imported or provisioned by the two AmneziaWG
    // servers, never installed by this client, so there is nothing to deploy.
    amnezia::ProtocolConfig createConfig(const amnezia::ServerCredentials &credentials,
                                         amnezia::DockerContainer container,
                                         const amnezia::ContainerConfig &containerConfig,
                                         const amnezia::DnsSettings &dnsSettings,
                                         amnezia::ErrorCode &errorCode) override;

    amnezia::ProtocolConfig processConfigWithLocalSettings(const amnezia::ConnectionSettings &settings,
                                                           amnezia::ProtocolConfig protocolConfig) override;

    amnezia::ProtocolConfig processConfigWithExportSettings(const amnezia::ExportSettings &settings,
                                                            amnezia::ProtocolConfig protocolConfig) override;

    static void normalize(amnezia::WwgProtocolConfig &config);
};

#endif // WWGCONFIGURATOR_H
