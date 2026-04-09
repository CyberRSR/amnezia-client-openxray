#include "oxrayprotocol.h"

#include <QJsonArray>

#include "core/networkUtilities.h"
#include "openvpnprotocol.h"
#include "xrayprotocol.h"

namespace
{
constexpr auto chainedUpstreamGatewayKey = "oxrayUpstreamGateway";
constexpr auto chainedRouteServerViaUpstreamKey = "oxrayRouteServerViaUpstream";

QString extractXrayRemoteHost(const QJsonObject &xrayConfig)
{
    const auto outbounds = xrayConfig.value("outbounds").toArray();
    if (outbounds.isEmpty()) {
        return {};
    }
    const auto outbound = outbounds.at(0).toObject();
    const auto settings = outbound.value("settings").toObject();
    const auto vnext = settings.value("vnext").toArray();
    if (vnext.isEmpty()) {
        return {};
    }
    return vnext.at(0).toObject().value("address").toString();
}

bool replaceXrayRemoteHost(QJsonObject &xrayConfig, const QString &host)
{
    auto outbounds = xrayConfig.value("outbounds").toArray();
    if (outbounds.isEmpty()) {
        return false;
    }

    auto outbound = outbounds.at(0).toObject();
    auto settings = outbound.value("settings").toObject();
    auto vnext = settings.value("vnext").toArray();
    if (vnext.isEmpty()) {
        return false;
    }

    auto remote = vnext.at(0).toObject();
    remote.insert("address", host);
    vnext.replace(0, remote);
    settings.insert("vnext", vnext);
    outbound.insert("settings", settings);
    outbounds.replace(0, outbound);
    xrayConfig.insert("outbounds", outbounds);
    return true;
}
} // namespace

OxrayProtocol::OxrayProtocol(const QJsonObject &configuration, QObject *parent) : VpnProtocol(configuration, parent)
{
    m_openVpnConfiguration = configuration;
    m_openVpnConfiguration.insert(amnezia::config_key::vpnproto, ProtocolProps::protoToString(Proto::OpenVpn));
    m_openVpnConfiguration.insert(amnezia::config_key::killSwitchOption, false);

    m_xrayConfiguration = configuration;
    m_xrayConfiguration.insert(amnezia::config_key::vpnproto, ProtocolProps::protoToString(Proto::Xray));
    m_xrayRemoteHost = extractXrayRemoteHost(configuration.value(ProtocolProps::key_proto_config_data(Proto::Xray)).toObject());
    if (!m_xrayRemoteHost.isEmpty()) {
        m_xrayRemoteAddress = NetworkUtilities::getIPAddress(m_xrayRemoteHost);
        m_xrayConfiguration.insert(amnezia::config_key::hostName, m_xrayRemoteHost);
    }

    m_openVpnRemoteAddress = NetworkUtilities::getIPAddress(configuration.value(amnezia::config_key::hostName).toString());
    if (!m_openVpnRemoteAddress.isEmpty()) {
        auto excludedAddresses = m_xrayConfiguration.value(amnezia::config_key::excludedAddresses).toArray();
        if (!excludedAddresses.contains(m_openVpnRemoteAddress)) {
            excludedAddresses.append(m_openVpnRemoteAddress);
        }
        m_xrayConfiguration.insert(amnezia::config_key::excludedAddresses, excludedAddresses);
    }

    m_xrayBaseConfiguration = m_xrayConfiguration;
}

OxrayProtocol::~OxrayProtocol()
{
    stop();
}

ErrorCode OxrayProtocol::prepare()
{
    OpenVpnProtocol openVpn(m_openVpnConfiguration);
    return openVpn.prepare();
}

void OxrayProtocol::createOpenVpnProtocol()
{
    m_openVpnProtocol.reset(new OpenVpnProtocol(m_openVpnConfiguration, this));
    connect(m_openVpnProtocol.data(), &VpnProtocol::connectionStateChanged, this, &OxrayProtocol::onOpenVpnStateChanged);
    connect(m_openVpnProtocol.data(), &VpnProtocol::protocolError, this, &OxrayProtocol::onChildProtocolError);
    connect(m_openVpnProtocol.data(), &VpnProtocol::bytesChanged, this, &OxrayProtocol::onOpenVpnBytesChanged);
}

void OxrayProtocol::createXrayProtocol()
{
    m_xrayProtocol.reset(new XrayProtocol(m_xrayConfiguration, this));
    connect(m_xrayProtocol.data(), &VpnProtocol::connectionStateChanged, this, &OxrayProtocol::onXrayStateChanged);
    connect(m_xrayProtocol.data(), &VpnProtocol::protocolError, this, &OxrayProtocol::onChildProtocolError);
    connect(m_xrayProtocol.data(), &VpnProtocol::bytesChanged, this, &OxrayProtocol::onXrayBytesChanged);
}

void OxrayProtocol::stopXrayProtocol()
{
    if (!m_xrayProtocol) {
        return;
    }

    disconnect(m_xrayProtocol.data(), nullptr, this, nullptr);
    m_xrayProtocol->stop();
    m_xrayProtocol.reset();
}

void OxrayProtocol::prepareXrayConfiguration()
{
    m_xrayConfiguration = m_xrayBaseConfiguration;
    m_xrayConfiguration.remove(chainedUpstreamGatewayKey);
    m_xrayConfiguration.remove(chainedRouteServerViaUpstreamKey);

    auto xrayConfig = m_xrayConfiguration.value(ProtocolProps::key_proto_config_data(Proto::Xray)).toObject();
    const auto tunnelServerAddress = m_openVpnProtocol ? m_openVpnProtocol->tunnelServerAddress() : QString();

    if (!m_xrayRemoteAddress.isEmpty() && m_xrayRemoteAddress == m_openVpnRemoteAddress && !tunnelServerAddress.isEmpty()) {
        if (replaceXrayRemoteHost(xrayConfig, tunnelServerAddress)) {
            m_xrayConfiguration.insert(ProtocolProps::key_proto_config_data(Proto::Xray), xrayConfig);
            m_xrayConfiguration.insert(amnezia::config_key::hostName, tunnelServerAddress);
        }
        return;
    }

    if (!m_xrayRemoteHost.isEmpty()) {
        m_xrayConfiguration.insert(amnezia::config_key::hostName, m_xrayRemoteHost);
    }

    if (!m_xrayRemoteAddress.isEmpty() && m_openVpnProtocol && !m_openVpnProtocol->vpnGateway().isEmpty()) {
        m_xrayConfiguration.insert(chainedUpstreamGatewayKey, m_openVpnProtocol->vpnGateway());
        m_xrayConfiguration.insert(chainedRouteServerViaUpstreamKey, true);
    }
}

ErrorCode OxrayProtocol::start()
{
    stop();

    createOpenVpnProtocol();
    setConnectionState(Vpn::ConnectionState::Connecting);

    const auto errorCode = m_openVpnProtocol->start();
    if (errorCode != ErrorCode::NoError) {
        setLastError(errorCode);
    }
    return errorCode;
}

void OxrayProtocol::stop()
{
    if (m_connectionState != Vpn::ConnectionState::Disconnected && m_connectionState != Vpn::ConnectionState::Unknown) {
        setConnectionState(Vpn::ConnectionState::Disconnecting);
    }

    m_isStartingXray = false;

    stopXrayProtocol();
    if (m_openVpnProtocol) {
        m_openVpnProtocol->stop();
        m_openVpnProtocol.reset();
    }

    setConnectionState(Vpn::ConnectionState::Disconnected);
}

void OxrayProtocol::onOpenVpnStateChanged(Vpn::ConnectionState state)
{
    if (!m_openVpnProtocol) {
        return;
    }

    switch (state) {
    case Vpn::ConnectionState::Connected: {
        if (m_xrayProtocol || m_isStartingXray) {
            return;
        }

        m_isStartingXray = true;
        prepareXrayConfiguration();
        createXrayProtocol();
        const auto errorCode = m_xrayProtocol->start();
        m_isStartingXray = false;

        if (errorCode != ErrorCode::NoError) {
            setLastError(errorCode);
            emit protocolError(errorCode);
        }
        break;
    }
    case Vpn::ConnectionState::Reconnecting:
        stopXrayProtocol();
        setConnectionState(Vpn::ConnectionState::Reconnecting);
        break;
    case Vpn::ConnectionState::Disconnecting:
        stopXrayProtocol();
        setConnectionState(Vpn::ConnectionState::Disconnecting);
        break;
    case Vpn::ConnectionState::Disconnected:
        stopXrayProtocol();
        if (!m_xrayProtocol) {
            setConnectionState(Vpn::ConnectionState::Disconnected);
        }
        break;
    case Vpn::ConnectionState::Error:
        stopXrayProtocol();
        setConnectionState(Vpn::ConnectionState::Error);
        break;
    default:
        break;
    }
}

void OxrayProtocol::onXrayStateChanged(Vpn::ConnectionState state)
{
    if (!m_xrayProtocol) {
        return;
    }

    if (state == Vpn::ConnectionState::Connected) {
        m_routeGateway = m_xrayProtocol->routeGateway();
        m_vpnGateway = m_xrayProtocol->vpnGateway();
        m_vpnLocalAddress = m_xrayProtocol->vpnLocalAddress();
    }

    setConnectionState(state);
}

void OxrayProtocol::onChildProtocolError(amnezia::ErrorCode errorCode)
{
    setLastError(errorCode);
    emit protocolError(errorCode);
}

void OxrayProtocol::onOpenVpnBytesChanged(quint64 receivedBytes, quint64 sentBytes)
{
    if (!m_xrayProtocol) {
        emit bytesChanged(receivedBytes, sentBytes);
    }
}

void OxrayProtocol::onXrayBytesChanged(quint64 receivedBytes, quint64 sentBytes)
{
    emit bytesChanged(receivedBytes, sentBytes);
}
