#include "wwgProtocolConfig.h"

#include <QJsonDocument>
#include <QJsonArray>
#include <QStringList>
#include <algorithm>

#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

namespace amnezia
{

QJsonObject WwgProtocolConfig::toJson() const
{
    QJsonObject obj;
    obj[configKey::underlayAwg] = underlayAwgConfig.toJson();
    obj[configKey::overlayAwg] = overlayAwgConfig.toJson();
    return obj;
}

WwgProtocolConfig WwgProtocolConfig::fromJson(const QJsonObject &json)
{
    WwgProtocolConfig config;
    config.underlayAwgConfig = AwgProtocolConfig::fromJson(json.value(configKey::underlayAwg).toObject());
    config.overlayAwgConfig = AwgProtocolConfig::fromJson(json.value(configKey::overlayAwg).toObject());
    return config;
}

bool WwgProtocolConfig::hasClientConfig() const
{
    return underlayAwgConfig.hasClientConfig() && overlayAwgConfig.hasClientConfig();
}

bool WwgProtocolConfig::isValidV2() const
{
    return isValidAwgV2(underlayAwgConfig) && isValidAwgV2(overlayAwgConfig);
}

void WwgProtocolConfig::clearClientConfig()
{
    underlayAwgConfig.clearClientConfig();
    overlayAwgConfig.clearClientConfig();
}

QJsonObject WwgProtocolConfig::underlayClientConfigJson() const
{
    return underlayAwgConfig.clientConfig.has_value()
            ? underlayAwgConfig.clientConfig->toJson()
            : QJsonObject {};
}

QJsonObject WwgProtocolConfig::overlayClientConfigJson() const
{
    return overlayAwgConfig.clientConfig.has_value()
            ? overlayAwgConfig.clientConfig->toJson()
            : QJsonObject {};
}

QString WwgProtocolConfig::nativeConfig() const
{
    QString result;
    const auto appendConfig = [&result](const QString &title, const AwgProtocolConfig &config) {
        if (!config.clientConfig.has_value()) {
            return;
        }
        if (!result.isEmpty()) {
            result += QLatin1Char('\n');
        }
        result += title + QLatin1Char('\n');
        result += config.clientConfig->nativeConfig;
        if (!result.endsWith(QLatin1Char('\n'))) {
            result += QLatin1Char('\n');
        }
    };

    appendConfig(QStringLiteral("# WWG underlay - AmneziaWG v2"), underlayAwgConfig);
    appendConfig(QStringLiteral("# WWG overlay - AmneziaWG v2"), overlayAwgConfig);
    return result;
}

bool WwgProtocolConfig::isValidAwgV2(const AwgProtocolConfig &config)
{
    return config.serverConfig.protocolVersion == protocols::awg::awgV2
            && config.clientConfig.has_value()
            && hasRequiredAwgV2Fields(config.clientConfig->toJson());
}

bool WwgProtocolConfig::hasRequiredAwgV2Fields(const QJsonObject &clientConfig)
{
    const QStringList requiredStringFields = {
        configKey::hostName,
        configKey::clientIp,
        configKey::clientPrivKey,
        configKey::serverPubKey,
        configKey::junkPacketCount,
        configKey::junkPacketMinSize,
        configKey::junkPacketMaxSize,
        configKey::initPacketJunkSize,
        configKey::responsePacketJunkSize,
        configKey::cookieReplyPacketJunkSize,
        configKey::transportPacketJunkSize,
        configKey::initPacketMagicHeader,
        configKey::responsePacketMagicHeader,
        configKey::underloadPacketMagicHeader,
        configKey::transportPacketMagicHeader,
    };

    const bool hasStrings = std::all_of(requiredStringFields.cbegin(), requiredStringFields.cend(),
                                        [&clientConfig](const QString &key) {
        return !clientConfig.value(key).toString().trimmed().isEmpty();
    });

    return hasStrings
            && clientConfig.value(configKey::port).toInt() > 0
            && !clientConfig.value(configKey::allowedIps).toArray().isEmpty();
}

} // namespace amnezia
