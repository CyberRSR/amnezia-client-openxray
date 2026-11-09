#include "wwgunderlay.h"

#include <QDebug>
#include <QJsonArray>
#include <QLibrary>
#include <QTextStream>

#include "core/utils/constants/configKeys.h"

using namespace amnezia;

namespace
{
constexpr int healthPollIntervalMsec = 5000;
constexpr int awgBlockSize = 16;
constexpr int awgTransportMessageOverhead = 32;
constexpr int ipv6UdpOverhead = 48;

typedef int (*wwgNetstackStart_t)(char *interfaceName, char *localAddresses, char *dnsServers, int mtu,
                                 char *settings, char *relayHost, int relayPort, int bindInterfaceIndex);
typedef int (*wwgNetstackRelayPort_t)(int tunnelHandle);
typedef int (*wwgNetstackIsHealthy_t)(int tunnelHandle);
typedef void (*wwgNetstackStop_t)(int tunnelHandle);
typedef int (*wwgNetstackDrainLog_t)(char *buffer, int bufferLength);

QString stringValue(const QJsonObject &json, const QString &key)
{
    return json.value(key).toVariant().toString().trimmed();
}

QString toUapiHex(const QString &base64Value)
{
    if (base64Value.isEmpty())
    {
        return {};
    }

    const QByteArray decoded = QByteArray::fromBase64(base64Value.toUtf8());
    if (decoded.isEmpty())
    {
        return {};
    }
    return QString::fromLatin1(decoded.toHex());
}

QString toUapiBool(const QString &value)
{
    const QString v = value.toLower();
    if (v == QLatin1String("on") || v == QLatin1String("1") || v == QLatin1String("true")
        || v == QLatin1String("t") || v == QLatin1String("yes"))
    {
        return QStringLiteral("1");
    }
    return QStringLiteral("0");
}

int intValue(const QJsonObject &json, const QString &key, int fallback)
{
    bool ok = false;
    const int value = stringValue(json, key).toInt(&ok);
    return ok ? value : fallback;
}
} // namespace

WwgUnderlay::WwgUnderlay(QObject *parent) : QObject(parent)
{
    m_healthTimer.setInterval(healthPollIntervalMsec);
    connect(&m_healthTimer, &QTimer::timeout, this, [this]() {
        if (m_handle < 0 || isHealthy())
        {
            return;
        }
        qWarning() << "WWG entry relay lost, last log:" << lastLog();
        m_healthTimer.stop();
        emit healthLost();
    });
}

WwgUnderlay::~WwgUnderlay()
{
    stop();
}

bool WwgUnderlay::loadLibrary()
{
    if (m_library)
    {
        return true;
    }

    auto library = new QLibrary(QStringLiteral("awgnetstack"), this);
    if (!library->load())
    {
        qWarning() << "Failed to load awgnetstack library:" << library->errorString();
        delete library;
        return false;
    }

    const auto required = [library](const char *name) { return library->resolve(name) != nullptr; };
    if (!required("wwgNetstackStart") || !required("wwgNetstackRelayPort") || !required("wwgNetstackIsHealthy")
        || !required("wwgNetstackStop") || !required("wwgNetstackDrainLog"))
    {
        qWarning() << "awgnetstack library is missing required exports";
        library->unload();
        delete library;
        return false;
    }

    m_library = library;
    return true;
}

bool WwgUnderlay::start(const Request &request, QString *error)
{
    stop();

    if (request.relayHost.isEmpty() || request.relayPort == 0)
    {
        if (error)
        {
            *error = QStringLiteral("WWG exit endpoint is empty");
        }
        return false;
    }

    if (!loadLibrary())
    {
        if (error)
        {
            *error = QStringLiteral("Unable to load the WWG entry library");
        }
        return false;
    }

    const int mtu = effectiveUnderlayMtu(intValue(request.configData, configKey::mtu, 0),
                                         request.overlayMtu, request.overlayS4, request.overlayIsAwgV3);
    const QString settings = buildUapiSettings(request.configData);
    if (settings.isEmpty())
    {
        if (error)
        {
            *error = QStringLiteral("Unable to build the WWG entry configuration");
        }
        return false;
    }

    QByteArray interfaceName = QByteArrayLiteral("wwg0");
    QByteArray localAddresses = stringValue(request.configData, configKey::clientIp).toUtf8();
    QByteArray dnsServers;
    QByteArray uapiSettings = settings.toUtf8();
    QByteArray relayHost = request.relayHost.toUtf8();

    auto startFn = reinterpret_cast<wwgNetstackStart_t>(m_library->resolve("wwgNetstackStart"));
    const int handle = startFn(interfaceName.data(), localAddresses.data(), dnsServers.data(), mtu,
                               uapiSettings.data(), relayHost.data(), static_cast<int>(request.relayPort),
                               static_cast<int>(request.bindInterfaceIndex));
    if (handle < 0)
    {
        const QString log = lastLog();
        qWarning() << "WWG entry layer failed to start:" << log;
        if (error)
        {
            *error = log.isEmpty() ? QStringLiteral("Unable to start the WWG entry layer") : log;
        }
        return false;
    }

    m_handle = handle;
    auto relayPortFn = reinterpret_cast<wwgNetstackRelayPort_t>(m_library->resolve("wwgNetstackRelayPort"));
    m_relayPort = static_cast<quint16>(relayPortFn(m_handle));
    if (m_relayPort == 0)
    {
        qWarning() << "WWG entry relay port is unavailable";
        stop();
        if (error)
        {
            *error = QStringLiteral("WWG entry relay is unavailable");
        }
        return false;
    }

    qDebug() << "WWG entry layer started, relay on 127.0.0.1:" << m_relayPort << "mtu" << mtu << lastLog();
    m_healthTimer.start();
    return true;
}

void WwgUnderlay::stop()
{
    m_healthTimer.stop();
    if (m_handle < 0 || !m_library)
    {
        m_handle = -1;
        m_relayPort = 0;
        return;
    }

    auto stopFn = reinterpret_cast<wwgNetstackStop_t>(m_library->resolve("wwgNetstackStop"));
    stopFn(m_handle);
    m_handle = -1;
    m_relayPort = 0;
}

bool WwgUnderlay::isActive() const
{
    return m_handle >= 0;
}

bool WwgUnderlay::isHealthy() const
{
    if (m_handle < 0 || !m_library)
    {
        return false;
    }

    auto healthyFn = reinterpret_cast<wwgNetstackIsHealthy_t>(m_library->resolve("wwgNetstackIsHealthy"));
    return healthyFn(m_handle) != 0;
}

quint16 WwgUnderlay::relayPort() const
{
    return m_relayPort;
}

QString WwgUnderlay::lastLog() const
{
    if (!m_library)
    {
        return {};
    }

    auto drainFn = reinterpret_cast<wwgNetstackDrainLog_t>(m_library->resolve("wwgNetstackDrainLog"));
    if (!drainFn)
    {
        return {};
    }

    QByteArray buffer(16384, '\0');
    const int written = drainFn(buffer.data(), buffer.size());
    buffer.truncate(qMax(0, written));
    return QString::fromUtf8(buffer);
}

int WwgUnderlay::minimumV3UnderlayMtu(int overlayMtu, int overlayS4)
{
    // The entry netstack carries whole exit AWG datagrams, so it must fit the
    // aligned exit payload plus the transport header, S4 and IPv6+UDP headers.
    const int alignedOverlay = (overlayMtu + awgBlockSize - 1) & -awgBlockSize;
    return alignedOverlay + awgTransportMessageOverhead + qMax(0, overlayS4) + ipv6UdpOverhead;
}

int WwgUnderlay::effectiveUnderlayMtu(int underlayMtu, int overlayMtu, int overlayS4, bool overlayIsAwgV3)
{
    if (underlayMtu <= 0)
    {
        underlayMtu = 1280;
    }
    if (!overlayIsAwgV3 || overlayMtu <= 0)
    {
        return underlayMtu;
    }
    return qMax(underlayMtu, minimumV3UnderlayMtu(overlayMtu, overlayS4));
}

QString WwgUnderlay::buildUapiSettings(const QJsonObject &configData)
{
    const QString privateKey = toUapiHex(stringValue(configData, configKey::clientPrivKey));
    const QString publicKey = toUapiHex(stringValue(configData, configKey::serverPubKey));
    if (privateKey.isEmpty() || publicKey.isEmpty())
    {
        return {};
    }

    QString settings;
    QTextStream out(&settings);
    out << "private_key=" << privateKey << "\n";

    const QStringList junkKeys { configKey::junkPacketCount,  configKey::junkPacketMinSize, configKey::junkPacketMaxSize,
                                 configKey::initPacketJunkSize, configKey::responsePacketJunkSize,
                                 configKey::cookieReplyPacketJunkSize, configKey::transportPacketJunkSize,
                                 configKey::initPacketMagicHeader, configKey::responsePacketMagicHeader,
                                 configKey::underloadPacketMagicHeader, configKey::transportPacketMagicHeader };
    const QStringList junkUapiNames { "jc",   "jmin", "jmax", "s1", "s2",   "s3", "s4",
                                      "h1",   "h2",   "h3",   "h4" };
    for (int i = 0; i < junkUapiNames.size(); ++i)
    {
        const QString value = stringValue(configData, junkKeys.at(i));
        if (!value.isEmpty())
        {
            out << junkUapiNames.at(i) << "=" << value << "\n";
        }
    }

    const QStringList specialJunkKeys { configKey::specialJunk1, configKey::specialJunk2, configKey::specialJunk3,
                                        configKey::specialJunk4, configKey::specialJunk5 };
    for (int i = 0; i < specialJunkKeys.size(); ++i)
    {
        const QString value = stringValue(configData, specialJunkKeys.at(i));
        if (!value.isEmpty())
        {
            out << 'i' << (i + 1) << "=" << value << "\n";
        }
    }

    const QString headerProtectionKey = toUapiHex(stringValue(configData, configKey::headerProtectionKey));
    if (!headerProtectionKey.isEmpty())
    {
        out << "header_protection_key=" << headerProtectionKey << "\n";
    }

    const QStringList passthroughKeys { configKey::contentPaddingAddition, configKey::rekeyAfterTime,
                                        configKey::rekeyTimeout,          configKey::rejectAfterTime,
                                        configKey::keepaliveTimeout,      configKey::maxHandshakeAttempts };
    const QStringList passthroughUapiNames { "content_padding_addition", "rekey_after_time",      "rekey_timeout",
                                             "reject_after_time",        "keepalive_timeout",     "max_handshake_attempts" };
    for (int i = 0; i < passthroughUapiNames.size(); ++i)
    {
        const QString value = stringValue(configData, passthroughKeys.at(i));
        if (!value.isEmpty())
        {
            out << passthroughUapiNames.at(i) << "=" << value << "\n";
        }
    }

    const QString randomTrailers = stringValue(configData, configKey::randomTrailers);
    if (!randomTrailers.isEmpty())
    {
        out << "random_trailers=" << toUapiBool(randomTrailers) << "\n";
    }
    const QString disableCookies = stringValue(configData, configKey::disableCookies);
    if (!disableCookies.isEmpty())
    {
        out << "disable_cookies=" << toUapiBool(disableCookies) << "\n";
    }

    out << "replace_peers=true\n";
    out << "public_key=" << publicKey << "\n";

    const QJsonArray allowedIps = configData.value(configKey::allowedIps).toArray();
    for (const auto &allowedIp : allowedIps)
    {
        const QString range = allowedIp.toVariant().toString().trimmed();
        if (!range.isEmpty())
        {
            out << "allowed_ip=" << range << "\n";
        }
    }

    const QString hostName = stringValue(configData, configKey::hostName);
    const QString port = stringValue(configData, configKey::port);
    if (hostName.isEmpty() || port.isEmpty())
    {
        return {};
    }
    out << "endpoint=";
    if (hostName.contains(QLatin1Char(':')))
    {
        out << '[' << hostName << ']';
    }
    else
    {
        out << hostName;
    }
    out << ':' << port << "\n";

    const QString persistentKeepAlive = stringValue(configData, configKey::persistentKeepAlive);
    if (!persistentKeepAlive.isEmpty() && persistentKeepAlive != QLatin1String("0"))
    {
        out << "persistent_keepalive_interval=" << persistentKeepAlive << "\n";
    }

    const QString presharedKey = toUapiHex(stringValue(configData, configKey::pskKey));
    if (!presharedKey.isEmpty())
    {
        out << "preshared_key=" << presharedKey << "\n";
    }

    return settings;
}
