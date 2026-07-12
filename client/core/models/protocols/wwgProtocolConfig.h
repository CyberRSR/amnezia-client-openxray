#ifndef WWGPROTOCOLCONFIG_H
#define WWGPROTOCOLCONFIG_H

#include <QJsonObject>

#include "core/models/protocols/awgProtocolConfig.h"

namespace amnezia
{

struct WwgProtocolConfig {
    AwgProtocolConfig underlayAwgConfig;
    AwgProtocolConfig overlayAwgConfig;

    QJsonObject toJson() const;
    static WwgProtocolConfig fromJson(const QJsonObject &json);

    bool hasClientConfig() const;
    bool isValidV2() const;
    void clearClientConfig();

    QJsonObject underlayClientConfigJson() const;
    QJsonObject overlayClientConfigJson() const;
    QString nativeConfig() const;

    static bool isValidAwgV2(const AwgProtocolConfig &config);
    static bool hasRequiredAwgV2Fields(const QJsonObject &clientConfig);
};

} // namespace amnezia

#endif // WWGPROTOCOLCONFIG_H
