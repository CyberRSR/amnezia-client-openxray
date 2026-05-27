#ifndef OWGCONFIGURATOR_H
#define OWGCONFIGURATOR_H

#include "configuratorBase.h"

class OwgConfigurator : public ConfiguratorBase
{
    Q_OBJECT
public:
    explicit OwgConfigurator(SshSession* sshSession, QObject *parent = nullptr);

    amnezia::ProtocolConfig createConfig(const amnezia::ServerCredentials &credentials, amnezia::DockerContainer container,
                                         const amnezia::ContainerConfig &containerConfig,
                                         const amnezia::DnsSettings &dnsSettings,
                                         amnezia::ErrorCode &errorCode) override;

    amnezia::ProtocolConfig processConfigWithLocalSettings(const amnezia::ConnectionSettings &settings,
                                                           amnezia::ProtocolConfig protocolConfig) override;

    amnezia::ProtocolConfig processConfigWithExportSettings(const amnezia::ExportSettings &settings,
                                                            amnezia::ProtocolConfig protocolConfig) override;
};

#endif // OWGCONFIGURATOR_H
