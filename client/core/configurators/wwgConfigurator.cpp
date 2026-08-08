#include "wwgConfigurator.h"

#include <QCryptographicHash>
#include <QEventLoop>
#include <QHostAddress>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSslCertificate>
#include <QSslConfiguration>
#include <QTimer>
#include <QUuid>
#include <QUrl>

#include <openssl/rand.h>

#include "wireguardConfigurator.h"
#include "core/models/protocols/wwgProtocolConfig.h"
#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

namespace
{
constexpr int provisioningTimeoutMs = 15000;
constexpr int provisioningAttempts = 2;

struct PinnedHttpResponse {
    bool transportOk = false;
    int httpStatus = 0;
    QByteArray body;
};

struct ProvisionedPeer {
    ErrorCode errorCode = ErrorCode::WwgProvisioningUnavailable;
    QString publicKey;
    QString clientIp;
    QString serverPublicKey;
    QString rollbackToken;
};

bool rollbackPeer(const WwgProvisioningEndpoint &endpoint, const ProvisionedPeer &peer);

QString normalizedPin(const QString &pin)
{
    return QString(pin).remove(QLatin1Char(':')).trimmed().toLower();
}

bool isBase64Key32(const QString &value)
{
    return QByteArray::fromBase64(value.trimmed().toLatin1(), QByteArray::AbortOnBase64DecodingErrors).size() == 32;
}

QUrl provisioningUrl(const WwgProvisioningEndpoint &endpoint, const QString &suffix)
{
    QUrl url(endpoint.url.trimmed());
    QString path = url.path();
    while (path.endsWith(QLatin1Char('/'))) {
        path.chop(1);
    }
    url.setPath(path + suffix);
    return url;
}

PinnedHttpResponse postPinnedJson(const WwgProvisioningEndpoint &endpoint,
                                  const QString &suffix,
                                  const QJsonObject &payload)
{
    PinnedHttpResponse result;
    if (!endpoint.isValid()) {
        return result;
    }

    QNetworkAccessManager manager;
    QNetworkRequest request(provisioningUrl(endpoint, suffix));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setRawHeader("Authorization", QByteArrayLiteral("Bearer ") + endpoint.token.trimmed().toUtf8());
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);

    QNetworkReply *reply = manager.post(request, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    QEventLoop wait;
    QTimer timer;
    timer.setSingleShot(true);

    bool timedOut = false;
    bool pinMatched = false;
    bool pinRejected = false;
    const QString expectedPin = normalizedPin(endpoint.certificateSha256);

    const auto verifyPeerCertificate = [&]() {
        const QSslCertificate certificate = reply->sslConfiguration().peerCertificate();
        if (certificate.isNull()) {
            return false;
        }
        const QString actualPin = QString::fromLatin1(
                certificate.digest(QCryptographicHash::Sha256).toHex()).toLower();
        if (actualPin != expectedPin) {
            pinRejected = true;
            reply->abort();
            return false;
        }
        pinMatched = true;
        return true;
    };

    QObject::connect(reply, &QNetworkReply::sslErrors, reply,
                     [&](const QList<QSslError> &errors) {
        if (verifyPeerCertificate()) {
            // A private provisioner uses a pinned self-signed certificate.
            // Ignore trust-chain/hostname errors only after the exact SHA-256
            // certificate pin has matched.
            reply->ignoreSslErrors(errors);
        }
    });
    QObject::connect(reply, &QNetworkReply::encrypted, reply, [&]() {
        verifyPeerCertificate();
    });
    QObject::connect(reply, &QNetworkReply::finished, &wait, &QEventLoop::quit);
    QObject::connect(&timer, &QTimer::timeout, &wait, [&]() {
        timedOut = true;
        reply->abort();
        wait.quit();
    });

    timer.start(provisioningTimeoutMs);
    wait.exec(QEventLoop::ExcludeUserInputEvents);
    timer.stop();

    result.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    result.body = reply->readAll();
    result.transportOk = !timedOut && !pinRejected && pinMatched && result.httpStatus > 0;
    reply->deleteLater();
    return result;
}

ErrorCode provisioningHttpError(const PinnedHttpResponse &response)
{
    if (!response.transportOk) {
        return ErrorCode::WwgProvisioningUnavailable;
    }
    if (response.httpStatus == 401 || response.httpStatus == 403) {
        return ErrorCode::WwgProvisioningAuthenticationError;
    }
    if (response.httpStatus == 429 || response.httpStatus == 507) {
        return ErrorCode::WwgProvisioningLimitError;
    }
    return ErrorCode::WwgProvisioningUnavailable;
}

ProvisionedPeer provisionPeer(const WwgProvisioningEndpoint &endpoint,
                              const QString &publicKey,
                              const QString &presharedKey,
                              const QString &requestId)
{
    const QJsonObject payload {
        { QStringLiteral("public_key"), publicKey },
        { QStringLiteral("preshared_key"), presharedKey },
        { QStringLiteral("request_id"), requestId },
    };
    PinnedHttpResponse response;
    // A lost success response must not strand a peer. Repeating the exact
    // key/PSK request is idempotent on the capability-scoped provisioner.
    for (int attempt = 0; attempt < provisioningAttempts; ++attempt) {
        response = postPinnedJson(endpoint, QStringLiteral("/peers"), payload);
        if (response.transportOk && response.httpStatus < 500) {
            break;
        }
    }

    ProvisionedPeer result;
    if (!response.transportOk || (response.httpStatus != 200 && response.httpStatus != 201)) {
        result.errorCode = provisioningHttpError(response);
        return result;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(response.body, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        result.errorCode = ErrorCode::WwgProvisioningInvalidResponse;
        return result;
    }

    const QJsonObject json = document.object();
    result.publicKey = json.value(QStringLiteral("public_key")).toString().trimmed();
    result.clientIp = json.value(QStringLiteral("client_ip")).toString().trimmed();
    result.serverPublicKey = json.value(QStringLiteral("server_public_key")).toString().trimmed();
    result.rollbackToken = json.value(QStringLiteral("rollback_token")).toString().trimmed();

    const QString address = result.clientIp.section(QLatin1Char('/'), 0, 0);
    bool prefixOk = false;
    const int prefix = result.clientIp.section(QLatin1Char('/'), 1, 1).toInt(&prefixOk);
    const QHostAddress parsedAddress(address);
    if (result.publicKey != publicKey || !isBase64Key32(result.publicKey)
            || parsedAddress.protocol() != QAbstractSocket::IPv4Protocol
            || !prefixOk || prefix != 32
            || !isBase64Key32(result.serverPublicKey)
            || result.rollbackToken.size() < 32) {
        ProvisionedPeer rollbackCandidate = result;
        rollbackCandidate.publicKey = publicKey;
        const bool canRollback = rollbackCandidate.rollbackToken.size() >= 32;
        result.errorCode = !canRollback || rollbackPeer(endpoint, rollbackCandidate)
                ? ErrorCode::WwgProvisioningInvalidResponse
                : ErrorCode::WwgProvisioningRollbackError;
        return result;
    }

    result.errorCode = ErrorCode::NoError;
    return result;
}

bool rollbackPeer(const WwgProvisioningEndpoint &endpoint, const ProvisionedPeer &peer)
{
    if (peer.publicKey.isEmpty() || peer.rollbackToken.isEmpty()) {
        return false;
    }
    const QJsonObject payload {
        { QStringLiteral("public_key"), peer.publicKey },
        { QStringLiteral("rollback_token"), peer.rollbackToken },
    };
    const PinnedHttpResponse response = postPinnedJson(endpoint, QStringLiteral("/peers/rollback"), payload);
    return response.transportOk && (response.httpStatus == 200 || response.httpStatus == 204);
}

QString generatePresharedKey()
{
    QByteArray key(32, Qt::Uninitialized);
    if (RAND_priv_bytes(reinterpret_cast<unsigned char *>(key.data()), key.size()) != 1) {
        return {};
    }
    return QString::fromLatin1(key.toBase64());
}

void appendNativeValue(QString &output, const QString &name, const QString &value)
{
    if (!value.trimmed().isEmpty()) {
        output += name + QStringLiteral(" = ") + value.trimmed() + QLatin1Char('\n');
    }
}

QString nativeAwgConfig(const AwgClientConfig &client, const DnsSettings &dnsSettings)
{
    QString output = QStringLiteral("[Interface]\n");
    appendNativeValue(output, QStringLiteral("Address"), client.clientIp);
    QStringList dns;
    if (!dnsSettings.primaryDns.trimmed().isEmpty()) dns << dnsSettings.primaryDns.trimmed();
    if (!dnsSettings.secondaryDns.trimmed().isEmpty()) dns << dnsSettings.secondaryDns.trimmed();
    appendNativeValue(output, QStringLiteral("DNS"), dns.join(QStringLiteral(", ")));
    appendNativeValue(output, QStringLiteral("PrivateKey"), client.clientPrivateKey);
    appendNativeValue(output, QStringLiteral("MTU"), client.mtu);
    appendNativeValue(output, QStringLiteral("Jc"), client.junkPacketCount);
    appendNativeValue(output, QStringLiteral("Jmin"), client.junkPacketMinSize);
    appendNativeValue(output, QStringLiteral("Jmax"), client.junkPacketMaxSize);
    appendNativeValue(output, QStringLiteral("S1"), client.initPacketJunkSize);
    appendNativeValue(output, QStringLiteral("S2"), client.responsePacketJunkSize);
    appendNativeValue(output, QStringLiteral("S3"), client.cookieReplyPacketJunkSize);
    appendNativeValue(output, QStringLiteral("S4"), client.transportPacketJunkSize);
    appendNativeValue(output, QStringLiteral("H1"), client.initPacketMagicHeader);
    appendNativeValue(output, QStringLiteral("H2"), client.responsePacketMagicHeader);
    appendNativeValue(output, QStringLiteral("H3"), client.underloadPacketMagicHeader);
    appendNativeValue(output, QStringLiteral("H4"), client.transportPacketMagicHeader);
    appendNativeValue(output, QStringLiteral("I1"), client.specialJunk1);
    appendNativeValue(output, QStringLiteral("I2"), client.specialJunk2);
    appendNativeValue(output, QStringLiteral("I3"), client.specialJunk3);
    appendNativeValue(output, QStringLiteral("I4"), client.specialJunk4);
    appendNativeValue(output, QStringLiteral("I5"), client.specialJunk5);
    appendNativeValue(output, QStringLiteral("HeaderProtectionKey"), client.headerProtectionKey);
    appendNativeValue(output, QStringLiteral("ContentPaddingAddition"), client.contentPaddingAddition);
    appendNativeValue(output, QStringLiteral("RekeyAfterTime"), client.rekeyAfterTime);
    appendNativeValue(output, QStringLiteral("RekeyTimeout"), client.rekeyTimeout);
    appendNativeValue(output, QStringLiteral("RejectAfterTime"), client.rejectAfterTime);
    appendNativeValue(output, QStringLiteral("KeepaliveTimeout"), client.keepaliveTimeout);
    appendNativeValue(output, QStringLiteral("MaxHandshakeAttempts"), client.maxHandshakeAttempts);

    output += QStringLiteral("\n[Peer]\n");
    appendNativeValue(output, QStringLiteral("PublicKey"), client.serverPublicKey);
    appendNativeValue(output, QStringLiteral("PresharedKey"), client.presharedKey);
    appendNativeValue(output, QStringLiteral("AllowedIPs"), client.allowedIps.join(QStringLiteral(", ")));
    QString endpointHost = client.hostName.trimmed();
    if (endpointHost.contains(QLatin1Char(':')) && !endpointHost.startsWith(QLatin1Char('['))) {
        endpointHost = QLatin1Char('[') + endpointHost + QLatin1Char(']');
    }
    appendNativeValue(output, QStringLiteral("Endpoint"), endpointHost + QLatin1Char(':') + QString::number(client.port));
    appendNativeValue(output, QStringLiteral("PersistentKeepalive"), client.persistentKeepAlive);
    return output;
}

AwgClientConfig makeProvisionedClient(const AwgClientConfig &source,
                                      const WireguardConfigurator::ConnectionData &keys,
                                      const QString &presharedKey,
                                      const ProvisionedPeer &peer,
                                      const DnsSettings &dnsSettings)
{
    AwgClientConfig client = source;
    client.clientPrivateKey = keys.clientPrivKey;
    client.clientPublicKey = keys.clientPubKey;
    client.presharedKey = presharedKey;
    client.clientIp = peer.clientIp;
    client.serverPublicKey = peer.serverPublicKey;
    client.clientId = keys.clientPubKey;
    client.nativeConfig = nativeAwgConfig(client, dnsSettings);
    return client;
}
}

WwgConfigurator::WwgConfigurator(SshSession *sshSession, QObject *parent)
    : ConfiguratorBase(sshSession, parent)
{
}

ProtocolConfig WwgConfigurator::createConfig(const ServerCredentials &credentials,
                                             DockerContainer container,
                                             const ContainerConfig &containerConfig,
                                             const DnsSettings &dnsSettings,
                                             ErrorCode &errorCode)
{
    Q_UNUSED(credentials);
    if (container != DockerContainer::WWG) {
        errorCode = ErrorCode::InternalError;
        return WwgProtocolConfig {};
    }

    const WwgProtocolConfig *source = containerConfig.getWwgProtocolConfig();
    if (!source || !source->isValid() || !source->canProvisionPeers()
            || !source->underlayAwgConfig.clientConfig.has_value()
            || !source->overlayAwgConfig.clientConfig.has_value()) {
        errorCode = ErrorCode::WwgProvisioningUnavailable;
        return WwgProtocolConfig {};
    }

    const auto underlayKeys = WireguardConfigurator::genClientKeys();
    const auto overlayKeys = WireguardConfigurator::genClientKeys();
    const QString underlayPsk = generatePresharedKey();
    const QString overlayPsk = generatePresharedKey();
    if (underlayKeys.clientPrivKey.isEmpty() || underlayKeys.clientPubKey.isEmpty()
            || overlayKeys.clientPrivKey.isEmpty() || overlayKeys.clientPubKey.isEmpty()
            || underlayPsk.isEmpty() || overlayPsk.isEmpty()
            || underlayKeys.clientPubKey == overlayKeys.clientPubKey) {
        errorCode = ErrorCode::InternalError;
        return WwgProtocolConfig {};
    }

    const QString requestId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const ProvisionedPeer underlayPeer = provisionPeer(source->provisioning->underlay,
                                                       underlayKeys.clientPubKey,
                                                       underlayPsk,
                                                       requestId + QStringLiteral("-entry"));
    if (underlayPeer.errorCode != ErrorCode::NoError) {
        errorCode = underlayPeer.errorCode;
        return WwgProtocolConfig {};
    }

    const AwgClientConfig &sourceUnderlay = source->underlayAwgConfig.clientConfig.value();
    if (underlayPeer.serverPublicKey != sourceUnderlay.serverPublicKey
            || underlayPeer.clientIp == sourceUnderlay.clientIp) {
        const bool rolledBack = rollbackPeer(source->provisioning->underlay, underlayPeer);
        errorCode = rolledBack ? ErrorCode::WwgProvisioningInvalidResponse
                               : ErrorCode::WwgProvisioningRollbackError;
        return WwgProtocolConfig {};
    }

    const ProvisionedPeer overlayPeer = provisionPeer(source->provisioning->overlay,
                                                      overlayKeys.clientPubKey,
                                                      overlayPsk,
                                                      requestId + QStringLiteral("-exit"));
    if (overlayPeer.errorCode != ErrorCode::NoError) {
        const bool rolledBack = rollbackPeer(source->provisioning->underlay, underlayPeer);
        errorCode = rolledBack ? overlayPeer.errorCode : ErrorCode::WwgProvisioningRollbackError;
        return WwgProtocolConfig {};
    }

    const AwgClientConfig &sourceOverlay = source->overlayAwgConfig.clientConfig.value();
    if (overlayPeer.serverPublicKey != sourceOverlay.serverPublicKey
            || overlayPeer.clientIp == sourceOverlay.clientIp) {
        const bool overlayRolledBack = rollbackPeer(source->provisioning->overlay, overlayPeer);
        const bool underlayRolledBack = rollbackPeer(source->provisioning->underlay, underlayPeer);
        errorCode = overlayRolledBack && underlayRolledBack
                ? ErrorCode::WwgProvisioningInvalidResponse
                : ErrorCode::WwgProvisioningRollbackError;
        return WwgProtocolConfig {};
    }

    WwgProtocolConfig result = *source;
    result.underlayAwgConfig.clientConfig = makeProvisionedClient(
            sourceUnderlay, underlayKeys, underlayPsk, underlayPeer, dnsSettings);
    result.overlayAwgConfig.clientConfig = makeProvisionedClient(
            sourceOverlay, overlayKeys, overlayPsk, overlayPeer, dnsSettings);
    normalize(result);

    if (!result.isValid()) {
        const bool overlayRolledBack = rollbackPeer(source->provisioning->overlay, overlayPeer);
        const bool underlayRolledBack = rollbackPeer(source->provisioning->underlay, underlayPeer);
        errorCode = overlayRolledBack && underlayRolledBack
                ? ErrorCode::WwgProvisioningInvalidResponse
                : ErrorCode::WwgProvisioningRollbackError;
        return WwgProtocolConfig {};
    }

    errorCode = ErrorCode::NoError;
    return result;
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
        if (awg.clientConfig.has_value()) {
            awg.clientConfig->isObfuscationEnabled = true;
        }
    };
    apply(config.underlayAwgConfig);
    apply(config.overlayAwgConfig);
}

void WwgConfigurator::forceV2(WwgProtocolConfig &config)
{
    normalize(config);
}
