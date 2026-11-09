#include "wwgProtocolConfig.h"

#include <QJsonDocument>
#include <QJsonArray>
#include <QByteArray>
#include <QRegularExpression>
#include <QStringList>
#include <QUrl>
#include <algorithm>
#include <limits>

#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

namespace amnezia
{

QJsonObject WwgProvisioningEndpoint::toJson() const
{
    QJsonObject obj;
    obj[configKey::wwgProvisioningUrl] = url;
    obj[configKey::wwgProvisioningToken] = token;
    obj[configKey::wwgProvisioningCertificateSha256] = certificateSha256;
    return obj;
}

WwgProvisioningEndpoint WwgProvisioningEndpoint::fromJson(const QJsonObject &json)
{
    WwgProvisioningEndpoint endpoint;
    endpoint.url = json.value(configKey::wwgProvisioningUrl).toString();
    endpoint.token = json.value(configKey::wwgProvisioningToken).toString();
    endpoint.certificateSha256 = json.value(configKey::wwgProvisioningCertificateSha256).toString();
    return endpoint;
}

bool WwgProvisioningEndpoint::isValid() const
{
    const QUrl parsedUrl(url.trimmed());
    if (!parsedUrl.isValid() || parsedUrl.scheme() != QStringLiteral("https")
            || parsedUrl.host().isEmpty() || !parsedUrl.userInfo().isEmpty()
            || parsedUrl.hasFragment() || parsedUrl.hasQuery()) {
        return false;
    }

    const QString normalizedPin = QString(certificateSha256).remove(QLatin1Char(':')).trimmed().toLower();
    static const QRegularExpression sha256Pattern(QStringLiteral("^[0-9a-f]{64}$"));
    return token.trimmed().size() >= 32 && sha256Pattern.match(normalizedPin).hasMatch();
}

QJsonObject WwgProvisioningConfig::toJson() const
{
    QJsonObject obj;
    obj[configKey::wwgProvisioningUnderlay] = underlay.toJson();
    obj[configKey::wwgProvisioningOverlay] = overlay.toJson();
    return obj;
}

WwgProvisioningConfig WwgProvisioningConfig::fromJson(const QJsonObject &json)
{
    WwgProvisioningConfig config;
    config.underlay = WwgProvisioningEndpoint::fromJson(
            json.value(configKey::wwgProvisioningUnderlay).toObject());
    config.overlay = WwgProvisioningEndpoint::fromJson(
            json.value(configKey::wwgProvisioningOverlay).toObject());
    return config;
}

bool WwgProvisioningConfig::isValid() const
{
    return underlay.isValid() && overlay.isValid();
}

namespace
{
WwgProtocolConfig::Mode detectedAwgMode(const AwgProtocolConfig &config)
{
    if (!config.clientConfig.has_value()) {
        return WwgProtocolConfig::Mode::Invalid;
    }
    return WwgProtocolConfig::hasAwgV3Fields(config)
            ? WwgProtocolConfig::Mode::V3
            : WwgProtocolConfig::Mode::V2;
}

QString validateCommonAwgFields(const AwgProtocolConfig &config, const QString &layerName)
{
    if (!config.clientConfig.has_value()) {
        return QStringLiteral("WWG %1 layer has no client configuration").arg(layerName);
    }
    if (config.serverConfig.protocolVersion != protocols::awg::awgV2) {
        return QStringLiteral("WWG %1 protocol_version must remain 2").arg(layerName);
    }

    const QJsonObject client = config.clientConfig->toJson();
    if (!WwgProtocolConfig::hasRequiredAwgV2Fields(client)) {
        return QStringLiteral("WWG %1 layer is missing required AmneziaWG fields").arg(layerName);
    }

    bool ok = false;
    const int port = client.value(configKey::port).toInt();
    if (port < 1 || port > 65535) {
        return QStringLiteral("WWG %1 layer has an invalid port").arg(layerName);
    }

    const int jc = client.value(configKey::junkPacketCount).toString().toInt(&ok);
    if (!ok || jc < 0) {
        return QStringLiteral("WWG %1 layer has an invalid Jc").arg(layerName);
    }
    const int jmin = client.value(configKey::junkPacketMinSize).toString().toInt(&ok);
    if (!ok || jmin < 0) {
        return QStringLiteral("WWG %1 layer has an invalid Jmin").arg(layerName);
    }
    const int jmax = client.value(configKey::junkPacketMaxSize).toString().toInt(&ok);
    if (!ok || jmax < jmin) {
        return QStringLiteral("WWG %1 layer has an invalid Jmax").arg(layerName);
    }

    const QString persistentKeepalive = config.clientConfig->persistentKeepAlive.trimmed();
    if (!WwgProtocolConfig::isValidUint32Range(persistentKeepalive)) {
        return QStringLiteral("WWG %1 layer has an invalid PersistentKeepalive range").arg(layerName);
    }

    return {};
}

QString validateV3Fields(const AwgProtocolConfig &config, const QString &layerName)
{
    if (!config.clientConfig.has_value()) {
        return QStringLiteral("WWG %1 layer has no client configuration").arg(layerName);
    }

    const AwgClientConfig &client = config.clientConfig.value();
    const QString serverHpk = config.serverConfig.headerProtectionKey.trimmed();
    const QString clientHpk = client.headerProtectionKey.trimmed();
    if (!WwgProtocolConfig::isValidHeaderProtectionKey(serverHpk)
            || !WwgProtocolConfig::isValidHeaderProtectionKey(clientHpk)) {
        return QStringLiteral("WWG %1 layer has an invalid 32-byte HeaderProtectionKey").arg(layerName);
    }
    if (QByteArray::fromBase64(serverHpk.toLatin1(), QByteArray::AbortOnBase64DecodingErrors)
            != QByteArray::fromBase64(clientHpk.toLatin1(), QByteArray::AbortOnBase64DecodingErrors)) {
        return QStringLiteral("WWG %1 server and client HeaderProtectionKey values do not match").arg(layerName);
    }

    const QStringList paddings = {
        client.initPacketJunkSize,
        client.responsePacketJunkSize,
        client.cookieReplyPacketJunkSize,
        client.transportPacketJunkSize,
    };
    for (int i = 0; i < paddings.size(); ++i) {
        bool ok = false;
        const int value = paddings.at(i).toInt(&ok);
        if (!ok || value < 8) {
            return QStringLiteral("WWG %1 AmneziaWG v3 requires S%2 >= 8").arg(layerName).arg(i + 1);
        }
    }

    const auto validateRanges = [&layerName](const QStringList &ranges) -> QString {
        for (const QString &value : ranges) {
            if (!WwgProtocolConfig::isValidUint32Range(value.trimmed())) {
                return QStringLiteral("WWG %1 layer has an invalid AWG3 range value").arg(layerName);
            }
        }
        return {};
    };

    QString error = validateRanges({
        config.serverConfig.contentPaddingAddition,
        config.serverConfig.rekeyAfterTime,
        config.serverConfig.rekeyTimeout,
        config.serverConfig.rejectAfterTime,
        config.serverConfig.keepaliveTimeout,
        config.serverConfig.maxHandshakeAttempts,
    });
    if (!error.isEmpty()) {
        return error;
    }
    return validateRanges({
        client.contentPaddingAddition,
        client.rekeyAfterTime,
        client.rekeyTimeout,
        client.rejectAfterTime,
        client.keepaliveTimeout,
        client.maxHandshakeAttempts,
    });
}
} // namespace

QJsonObject WwgProtocolConfig::toJson() const
{
    QJsonObject obj;
    obj[configKey::underlayAwg] = underlayAwgConfig.toJson();
    obj[configKey::overlayAwg] = overlayAwgConfig.toJson();
    if (provisioning.has_value()) {
        obj[configKey::wwgProvisioning] = provisioning->toJson();
    }
    return obj;
}

WwgProtocolConfig WwgProtocolConfig::fromJson(const QJsonObject &json)
{
    WwgProtocolConfig config;
    config.underlayAwgConfig = AwgProtocolConfig::fromJson(json.value(configKey::underlayAwg).toObject());
    config.overlayAwgConfig = AwgProtocolConfig::fromJson(json.value(configKey::overlayAwg).toObject());
    const QJsonObject provisioningJson = json.value(configKey::wwgProvisioning).toObject();
    if (!provisioningJson.isEmpty()) {
        config.provisioning = WwgProvisioningConfig::fromJson(provisioningJson);
    }
    return config;
}

bool WwgProtocolConfig::hasClientConfig() const
{
    return underlayAwgConfig.hasClientConfig() && overlayAwgConfig.hasClientConfig();
}

bool WwgProtocolConfig::isValid() const
{
    return validationError().isEmpty();
}

bool WwgProtocolConfig::isValidV2() const
{
    return mode() == Mode::V2 && isValid();
}

bool WwgProtocolConfig::isValidV3() const
{
    return mode() == Mode::V3 && isValid();
}

WwgProtocolConfig::Mode WwgProtocolConfig::mode() const
{
    const Mode underlayMode = detectedAwgMode(underlayAwgConfig);
    const Mode overlayMode = detectedAwgMode(overlayAwgConfig);
    if (underlayMode == Mode::Invalid || overlayMode == Mode::Invalid || underlayMode != overlayMode) {
        return Mode::Invalid;
    }
    return underlayMode;
}

QString WwgProtocolConfig::modeName() const
{
    switch (mode()) {
    case Mode::V2: return QStringLiteral("AmneziaWG v2");
    case Mode::V3: return QStringLiteral("AmneziaWG v3");
    case Mode::Invalid: return QStringLiteral("Invalid");
    }
    return QStringLiteral("Invalid");
}

QString WwgProtocolConfig::validationError() const
{
    QString error = validateCommonAwgFields(underlayAwgConfig, QStringLiteral("entry"));
    if (!error.isEmpty()) {
        return error;
    }
    error = validateCommonAwgFields(overlayAwgConfig, QStringLiteral("exit"));
    if (!error.isEmpty()) {
        return error;
    }

    const Mode underlayMode = detectedAwgMode(underlayAwgConfig);
    const Mode overlayMode = detectedAwgMode(overlayAwgConfig);
    if (underlayMode != overlayMode) {
        return QStringLiteral("WWG cannot mix AmneziaWG v2 and v3 layers");
    }
    if (underlayMode == Mode::Invalid) {
        return QStringLiteral("WWG mode cannot be determined");
    }

    if (underlayMode == Mode::V3) {
        error = validateV3Fields(underlayAwgConfig, QStringLiteral("entry"));
        if (!error.isEmpty()) {
            return error;
        }
        error = validateV3Fields(overlayAwgConfig, QStringLiteral("exit"));
        if (!error.isEmpty()) {
            return error;
        }
    }
    return {};
}

bool WwgProtocolConfig::canProvisionPeers() const
{
    return provisioning.has_value() && provisioning->isValid();
}

void WwgProtocolConfig::clearProvisioning()
{
    provisioning.reset();
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

    const QString version = mode() == Mode::V3 ? QStringLiteral("v3") : QStringLiteral("v2");
    appendConfig(QStringLiteral("# WWG underlay - AmneziaWG %1").arg(version), underlayAwgConfig);
    appendConfig(QStringLiteral("# WWG overlay - AmneziaWG %1").arg(version), overlayAwgConfig);
    return result;
}

bool WwgProtocolConfig::isValidAwgV2(const AwgProtocolConfig &config)
{
    return config.serverConfig.protocolVersion == protocols::awg::awgV2
            && config.clientConfig.has_value()
            && !hasAwgV3Fields(config)
            && hasRequiredAwgV2Fields(config.clientConfig->toJson());
}

bool WwgProtocolConfig::isValidAwgV3(const AwgProtocolConfig &config)
{
    return config.serverConfig.protocolVersion == protocols::awg::awgV2
            && config.clientConfig.has_value()
            && hasAwgV3Fields(config)
            && validateCommonAwgFields(config, QStringLiteral("layer")).isEmpty()
            && validateV3Fields(config, QStringLiteral("layer")).isEmpty();
}

bool WwgProtocolConfig::hasAwgV3Fields(const AwgProtocolConfig &config)
{
    const auto hasAny = [](const QStringList &values) {
        return std::any_of(values.cbegin(), values.cend(), [](const QString &value) {
            return !value.trimmed().isEmpty();
        });
    };

    if (hasAny({
            config.serverConfig.headerProtectionKey,
            config.serverConfig.contentPaddingAddition,
            config.serverConfig.rekeyAfterTime,
            config.serverConfig.rekeyTimeout,
            config.serverConfig.rejectAfterTime,
            config.serverConfig.keepaliveTimeout,
            config.serverConfig.maxHandshakeAttempts,
        })) {
        return true;
    }
    if (!config.clientConfig.has_value()) {
        return false;
    }
    const AwgClientConfig &client = config.clientConfig.value();
    return hasAny({
        client.headerProtectionKey,
        client.contentPaddingAddition,
        client.rekeyAfterTime,
        client.rekeyTimeout,
        client.rejectAfterTime,
        client.keepaliveTimeout,
        client.maxHandshakeAttempts,
    });
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

bool WwgProtocolConfig::isValidHeaderProtectionKey(const QString &value)
{
    const QByteArray encoded = value.trimmed().toLatin1();
    if (encoded.isEmpty()) {
        return false;
    }
    const QByteArray decoded = QByteArray::fromBase64(encoded, QByteArray::AbortOnBase64DecodingErrors);
    return decoded.size() == 32;
}

bool WwgProtocolConfig::isValidUint32Range(const QString &value, bool allowEmpty)
{
    const QString trimmed = value.trimmed();
    if (trimmed.isEmpty()) {
        return allowEmpty;
    }
    if (trimmed.compare(QStringLiteral("(off)"), Qt::CaseInsensitive) == 0) {
        return true;
    }

    const QStringList parts = trimmed.split(QLatin1Char('-'));
    if (parts.size() < 1 || parts.size() > 2
            || parts.at(0).isEmpty() || (parts.size() == 2 && parts.at(1).isEmpty())) {
        return false;
    }

    bool loOk = false;
    bool hiOk = false;
    const qulonglong lo = parts.at(0).toULongLong(&loOk, 10);
    const qulonglong hi = parts.size() == 2 ? parts.at(1).toULongLong(&hiOk, 10) : lo;
    if (parts.size() == 1) {
        hiOk = loOk;
    }
    return loOk && hiOk
            && lo <= std::numeric_limits<quint32>::max()
            && hi <= std::numeric_limits<quint32>::max()
            && lo <= hi;
}

} // namespace amnezia
