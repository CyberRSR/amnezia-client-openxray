#ifndef WWGCONFIGURATOR_H
#define WWGCONFIGURATOR_H

#include "configuratorBase.h"
#include "core/models/protocols/wwgProtocolConfig.h"

class WwgConfigurator : public ConfiguratorBase
{
    Q_OBJECT
public:
    explicit WwgConfigurator(SshSession *sshSession, QObject *parent = nullptr);

    amnezia::ProtocolConfig createConfig(const amnezia::ServerCredentials &credentials,
                                         amnezia::DockerContainer container,
                                         const amnezia::ContainerConfig &containerConfig,
                                         const amnezia::DnsSettings &dnsSettings,
                                         amnezia::ErrorCode &errorCode) override;

    amnezia::ProtocolConfig processConfigWithLocalSettings(const amnezia::ConnectionSettings &settings,
                                                           amnezia::ProtocolConfig protocolConfig) override;

    amnezia::ProtocolConfig processConfigWithExportSettings(const amnezia::ExportSettings &settings,
                                                            amnezia::ProtocolConfig protocolConfig) override;

private:
    static void normalize(amnezia::WwgProtocolConfig &config);
    // Kept for source compatibility with older WWG integrations. AWG3 also
    // uses protocol_version=2, so this no longer converts or strips a mode.
    static void forceV2(amnezia::WwgProtocolConfig &config);
};

#endif // WWGCONFIGURATOR_H
