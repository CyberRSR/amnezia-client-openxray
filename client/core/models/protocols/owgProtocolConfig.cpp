#include "owgProtocolConfig.h"

#include <QJsonDocument>

#include "core/utils/constants/configKeys.h"

namespace amnezia
{

QJsonObject OwgProtocolConfig::toJson() const
{
    QJsonObject obj;
    obj[configKey::openvpn] = openVpnConfig.toJson();
    obj[configKey::awg] = awgConfig.toJson();
    return obj;
}

OwgProtocolConfig OwgProtocolConfig::fromJson(const QJsonObject& json)
{
    OwgProtocolConfig config;
    config.openVpnConfig = OpenVpnProtocolConfig::fromJson(json.value(configKey::openvpn).toObject());
    config.awgConfig = AwgProtocolConfig::fromJson(json.value(configKey::awg).toObject());
    return config;
}

bool OwgProtocolConfig::hasClientConfig() const
{
    return openVpnConfig.hasClientConfig() && awgConfig.hasClientConfig();
}

void OwgProtocolConfig::clearClientConfig()
{
    openVpnConfig.clearClientConfig();
    awgConfig.clearClientConfig();
}

QJsonObject OwgProtocolConfig::openVpnClientConfigJson() const
{
    if (!openVpnConfig.clientConfig.has_value()) {
        return {};
    }
    return openVpnConfig.clientConfig->toJson();
}

QJsonObject OwgProtocolConfig::awgClientConfigJson() const
{
    if (!awgConfig.clientConfig.has_value()) {
        return {};
    }
    return awgConfig.clientConfig->toJson();
}

QString OwgProtocolConfig::nativeConfig() const
{
    QString config;
    if (openVpnConfig.clientConfig.has_value()) {
        config += QStringLiteral("# OpenVPN\n");
        config += openVpnConfig.clientConfig->nativeConfig;
        if (!config.endsWith(QLatin1Char('\n'))) {
            config += QLatin1Char('\n');
        }
    }
    if (awgConfig.clientConfig.has_value()) {
        config += QStringLiteral("\n# AmneziaWG v2\n");
        config += awgConfig.clientConfig->nativeConfig;
        if (!config.endsWith(QLatin1Char('\n'))) {
            config += QLatin1Char('\n');
        }
    }
    return config;
}

} // namespace amnezia
