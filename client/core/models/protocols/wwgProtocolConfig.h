#ifndef WWGPROTOCOLCONFIG_H
#define WWGPROTOCOLCONFIG_H

#include <QJsonObject>

#include "core/models/protocols/awgProtocolConfig.h"

namespace amnezia
{

struct WwgProvisioningEndpoint {
    QString url;
    QString token;
    QString certificateSha256;

    QJsonObject toJson() const;
    static WwgProvisioningEndpoint fromJson(const QJsonObject &json);
    bool isValid() const;
};

struct WwgProvisioningConfig {
    WwgProvisioningEndpoint underlay;
    WwgProvisioningEndpoint overlay;

    QJsonObject toJson() const;
    static WwgProvisioningConfig fromJson(const QJsonObject &json);
    bool isValid() const;
};

struct WwgProtocolConfig {
    enum class Mode {
        Invalid,
        V2,
        V3,
    };

    AwgProtocolConfig underlayAwgConfig;
    AwgProtocolConfig overlayAwgConfig;
    std::optional<WwgProvisioningConfig> provisioning;

    QJsonObject toJson() const;
    static WwgProtocolConfig fromJson(const QJsonObject &json);

    bool hasClientConfig() const;
    bool isValid() const;
    bool isValidV2() const;
    bool isValidV3() const;
    Mode mode() const;
    QString modeName() const;
    QString validationError() const;
    bool canProvisionPeers() const;
    void clearClientConfig();

    QJsonObject underlayClientConfigJson() const;
    QJsonObject overlayClientConfigJson() const;
    QString nativeConfig() const;

    static bool isValidAwgV2(const AwgProtocolConfig &config);
    static bool isValidAwgV3(const AwgProtocolConfig &config);
    static bool hasAwgV3Fields(const AwgProtocolConfig &config);
    static bool hasRequiredAwgV2Fields(const QJsonObject &clientConfig);
    static bool isValidHeaderProtectionKey(const QString &value);
    static bool isValidUint32Range(const QString &value, bool allowEmpty = true);
};

} // namespace amnezia

#endif // WWGPROTOCOLCONFIG_H
