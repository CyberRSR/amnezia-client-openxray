#ifndef OWGPROTOCOLCONFIG_H
#define OWGPROTOCOLCONFIG_H

#include <QJsonObject>

#include "core/models/protocols/awgProtocolConfig.h"
#include "core/models/protocols/openVpnProtocolConfig.h"

namespace amnezia
{

struct OwgProtocolConfig {
    OpenVpnProtocolConfig openVpnConfig;
    AwgProtocolConfig awgConfig;

    QJsonObject toJson() const;
    static OwgProtocolConfig fromJson(const QJsonObject& json);

    bool hasClientConfig() const;
    void clearClientConfig();

    QJsonObject openVpnClientConfigJson() const;
    QJsonObject awgClientConfigJson() const;
    QString nativeConfig() const;
};

} // namespace amnezia

#endif // OWGPROTOCOLCONFIG_H
