#include "owgConfigModel.h"

#include "core/models/protocols/awgProtocolConfig.h"
#include "core/protocols/protocolUtils.h"
#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"
#include "core/utils/containers/containerUtils.h"

using namespace amnezia;

OwgConfigModel::OwgConfigModel(QObject *parent) : QAbstractListModel(parent)
{
}

int OwgConfigModel::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent);
    return 1;
}

bool OwgConfigModel::setData(const QModelIndex &index, const QVariant &value, int role)
{
    if (!index.isValid() || index.row() < 0 || index.row() >= rowCount()) {
        return false;
    }

    const QString strValue = value.toString();
    if (!m_protocolConfig.openVpnConfig.clientConfig.has_value()) {
        m_protocolConfig.openVpnConfig.clientConfig = OpenVpnClientConfig {};
    }
    if (!m_protocolConfig.awgConfig.clientConfig.has_value()) {
        m_protocolConfig.awgConfig.clientConfig = AwgClientConfig {};
    }

    switch (role) {
    case OpenVpnSubnetAddressRole: m_protocolConfig.openVpnConfig.serverConfig.subnetAddress = strValue; break;
    case OpenVpnTransportProtoRole: m_protocolConfig.openVpnConfig.serverConfig.transportProto = strValue; break;
    case OpenVpnPortRole: m_protocolConfig.openVpnConfig.serverConfig.port = strValue; break;
    case OpenVpnAutoNegotiateEncryptionRole: m_protocolConfig.openVpnConfig.serverConfig.ncpDisable = !value.toBool(); break;
    case OpenVpnHashRole: m_protocolConfig.openVpnConfig.serverConfig.hash = strValue; break;
    case OpenVpnCipherRole: m_protocolConfig.openVpnConfig.serverConfig.cipher = strValue; break;
    case OpenVpnTlsAuthRole: m_protocolConfig.openVpnConfig.serverConfig.tlsAuth = value.toBool(); break;
    case OpenVpnBlockDnsRole: m_protocolConfig.openVpnConfig.clientConfig->blockOutsideDns = value.toBool(); break;
    case OpenVpnAdditionalClientCommandsRole: m_protocolConfig.openVpnConfig.serverConfig.additionalClientConfig = strValue; break;
    case OpenVpnAdditionalServerCommandsRole: m_protocolConfig.openVpnConfig.serverConfig.additionalServerConfig = strValue; break;

    case AwgHostNameRole: m_protocolConfig.awgConfig.clientConfig->hostName = strValue; break;
    case AwgPortRole:
        m_protocolConfig.awgConfig.clientConfig->port = strValue.toInt();
        m_protocolConfig.awgConfig.serverConfig.port = strValue;
        break;
    case AwgClientMtuRole: m_protocolConfig.awgConfig.clientConfig->mtu = strValue; break;
    case AwgJunkPacketCountRole: m_protocolConfig.awgConfig.clientConfig->junkPacketCount = strValue; break;
    case AwgJunkPacketMinSizeRole: m_protocolConfig.awgConfig.clientConfig->junkPacketMinSize = strValue; break;
    case AwgJunkPacketMaxSizeRole: m_protocolConfig.awgConfig.clientConfig->junkPacketMaxSize = strValue; break;
    case AwgInitPacketJunkSizeRole: m_protocolConfig.awgConfig.clientConfig->initPacketJunkSize = strValue; break;
    case AwgResponsePacketJunkSizeRole: m_protocolConfig.awgConfig.clientConfig->responsePacketJunkSize = strValue; break;
    case AwgCookieReplyPacketJunkSizeRole: m_protocolConfig.awgConfig.clientConfig->cookieReplyPacketJunkSize = strValue; break;
    case AwgTransportPacketJunkSizeRole: m_protocolConfig.awgConfig.clientConfig->transportPacketJunkSize = strValue; break;
    case AwgInitPacketMagicHeaderRole: m_protocolConfig.awgConfig.clientConfig->initPacketMagicHeader = strValue; break;
    case AwgResponsePacketMagicHeaderRole: m_protocolConfig.awgConfig.clientConfig->responsePacketMagicHeader = strValue; break;
    case AwgUnderloadPacketMagicHeaderRole: m_protocolConfig.awgConfig.clientConfig->underloadPacketMagicHeader = strValue; break;
    case AwgTransportPacketMagicHeaderRole: m_protocolConfig.awgConfig.clientConfig->transportPacketMagicHeader = strValue; break;
    case AwgSpecialJunk1Role: m_protocolConfig.awgConfig.clientConfig->specialJunk1 = strValue; break;
    case AwgSpecialJunk2Role: m_protocolConfig.awgConfig.clientConfig->specialJunk2 = strValue; break;
    case AwgSpecialJunk3Role: m_protocolConfig.awgConfig.clientConfig->specialJunk3 = strValue; break;
    case AwgSpecialJunk4Role: m_protocolConfig.awgConfig.clientConfig->specialJunk4 = strValue; break;
    case AwgSpecialJunk5Role: m_protocolConfig.awgConfig.clientConfig->specialJunk5 = strValue; break;
    default: return false;
    }

    emit dataChanged(index, index, QList<int> { role });
    return true;
}

QVariant OwgConfigModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() < 0 || index.row() >= rowCount()) {
        return {};
    }

    const auto &openVpnServer = m_protocolConfig.openVpnConfig.serverConfig;
    const auto openVpnClient = m_protocolConfig.openVpnConfig.clientConfig.value_or(OpenVpnClientConfig {});
    const auto awgClient = m_protocolConfig.awgConfig.clientConfig.value_or(AwgClientConfig {});

    switch (role) {
    case OpenVpnSubnetAddressRole:
        return openVpnServer.subnetAddress.isEmpty() ? QString(protocols::openvpn::defaultSubnetAddress) : openVpnServer.subnetAddress;
    case OpenVpnTransportProtoRole:
        return openVpnServer.transportProto.isEmpty() ? QString(protocols::openvpn::defaultTransportProto) : openVpnServer.transportProto;
    case OpenVpnPortRole:
        return openVpnServer.port.isEmpty() ? QString(protocols::openvpn::defaultPort) : openVpnServer.port;
    case OpenVpnAutoNegotiateEncryptionRole: return !openVpnServer.ncpDisable;
    case OpenVpnHashRole:
        return openVpnServer.hash.isEmpty() ? QString(protocols::openvpn::defaultHash) : openVpnServer.hash;
    case OpenVpnCipherRole:
        return openVpnServer.cipher.isEmpty() ? QString(protocols::openvpn::defaultCipher) : openVpnServer.cipher;
    case OpenVpnTlsAuthRole: return openVpnServer.tlsAuth;
    case OpenVpnBlockDnsRole: return openVpnClient.blockOutsideDns;
    case OpenVpnAdditionalClientCommandsRole: return openVpnServer.additionalClientConfig;
    case OpenVpnAdditionalServerCommandsRole: return openVpnServer.additionalServerConfig;

    case AwgHostNameRole: return awgClient.hostName;
    case AwgPortRole: return awgClient.port > 0 ? QString::number(awgClient.port) : QString(protocols::awg::defaultPort);
    case AwgClientMtuRole: return awgClient.mtu.isEmpty() ? QString(protocols::awg::defaultMtu) : awgClient.mtu;
    case AwgJunkPacketCountRole: return awgClient.junkPacketCount;
    case AwgJunkPacketMinSizeRole: return awgClient.junkPacketMinSize;
    case AwgJunkPacketMaxSizeRole: return awgClient.junkPacketMaxSize;
    case AwgInitPacketJunkSizeRole: return awgClient.initPacketJunkSize;
    case AwgResponsePacketJunkSizeRole: return awgClient.responsePacketJunkSize;
    case AwgCookieReplyPacketJunkSizeRole: return awgClient.cookieReplyPacketJunkSize;
    case AwgTransportPacketJunkSizeRole: return awgClient.transportPacketJunkSize;
    case AwgInitPacketMagicHeaderRole: return awgClient.initPacketMagicHeader;
    case AwgResponsePacketMagicHeaderRole: return awgClient.responsePacketMagicHeader;
    case AwgUnderloadPacketMagicHeaderRole: return awgClient.underloadPacketMagicHeader;
    case AwgTransportPacketMagicHeaderRole: return awgClient.transportPacketMagicHeader;
    case AwgSpecialJunk1Role: return awgClient.specialJunk1;
    case AwgSpecialJunk2Role: return awgClient.specialJunk2;
    case AwgSpecialJunk3Role: return awgClient.specialJunk3;
    case AwgSpecialJunk4Role: return awgClient.specialJunk4;
    case AwgSpecialJunk5Role: return awgClient.specialJunk5;
    default: return {};
    }
}

void OwgConfigModel::updateModel(DockerContainer container, const OwgProtocolConfig &protocolConfig)
{
    beginResetModel();
    m_container = container;
    m_protocolConfig = protocolConfig;
    applyDefaults();
    endResetModel();
}

QJsonObject OwgConfigModel::getConfig()
{
    applyDefaults();
    m_protocolConfig.awgConfig.serverConfig.protocolVersion = protocols::awg::awgV2;
    if (m_protocolConfig.awgConfig.clientConfig.has_value()) {
        m_protocolConfig.awgConfig.clientConfig->isObfuscationEnabled = true;
    }

    QJsonObject container;
    container[configKey::container] = ContainerUtils::containerToString(m_container);
    container[configKey::owg] = m_protocolConfig.toJson();
    return container;
}

bool OwgConfigModel::isHeadersEqual(const QString &h1, const QString &h2, const QString &h3, const QString &h4)
{
    return AwgProtocolConfig::isHeadersEqual(h1, h2, h3, h4);
}

bool OwgConfigModel::isPacketSizeEqual(const int s1, const int s2, const int s3, const int s4)
{
    return AwgProtocolConfig::isPacketSizeEqual(s1, s2, s3, s4);
}

void OwgConfigModel::applyDefaults()
{
    auto &openVpnServer = m_protocolConfig.openVpnConfig.serverConfig;
    if (openVpnServer.subnetAddress.isEmpty()) openVpnServer.subnetAddress = protocols::openvpn::defaultSubnetAddress;
    if (openVpnServer.transportProto.isEmpty()) openVpnServer.transportProto = protocols::openvpn::defaultTransportProto;
    if (openVpnServer.port.isEmpty()) openVpnServer.port = protocols::openvpn::defaultPort;
    if (openVpnServer.hash.isEmpty()) openVpnServer.hash = protocols::openvpn::defaultHash;
    if (openVpnServer.cipher.isEmpty()) openVpnServer.cipher = protocols::openvpn::defaultCipher;

    if (!m_protocolConfig.openVpnConfig.clientConfig.has_value()) {
        m_protocolConfig.openVpnConfig.clientConfig = OpenVpnClientConfig {};
    }
    if (!m_protocolConfig.awgConfig.clientConfig.has_value()) {
        m_protocolConfig.awgConfig.clientConfig = AwgClientConfig {};
    }

    auto &awgServer = m_protocolConfig.awgConfig.serverConfig;
    auto &awgClient = m_protocolConfig.awgConfig.clientConfig.value();
    awgServer.protocolVersion = protocols::awg::awgV2;
    if (awgServer.port.isEmpty() && awgClient.port > 0) awgServer.port = QString::number(awgClient.port);
    if (awgClient.port <= 0) awgClient.port = awgServer.port.isEmpty() ? QString(protocols::awg::defaultPort).toInt() : awgServer.port.toInt();
    if (awgClient.mtu.isEmpty()) awgClient.mtu = protocols::awg::defaultMtu;
    if (awgClient.junkPacketCount.isEmpty()) awgClient.junkPacketCount = awgServer.junkPacketCount.isEmpty() ? protocols::awg::defaultJunkPacketCount : awgServer.junkPacketCount;
    if (awgClient.junkPacketMinSize.isEmpty()) awgClient.junkPacketMinSize = awgServer.junkPacketMinSize.isEmpty() ? protocols::awg::defaultJunkPacketMinSize : awgServer.junkPacketMinSize;
    if (awgClient.junkPacketMaxSize.isEmpty()) awgClient.junkPacketMaxSize = awgServer.junkPacketMaxSize.isEmpty() ? protocols::awg::defaultJunkPacketMaxSize : awgServer.junkPacketMaxSize;
    if (awgClient.initPacketJunkSize.isEmpty()) awgClient.initPacketJunkSize = awgServer.initPacketJunkSize.isEmpty() ? protocols::awg::defaultInitPacketJunkSize : awgServer.initPacketJunkSize;
    if (awgClient.responsePacketJunkSize.isEmpty()) awgClient.responsePacketJunkSize = awgServer.responsePacketJunkSize.isEmpty() ? protocols::awg::defaultResponsePacketJunkSize : awgServer.responsePacketJunkSize;
    if (awgClient.cookieReplyPacketJunkSize.isEmpty()) awgClient.cookieReplyPacketJunkSize = awgServer.cookieReplyPacketJunkSize.isEmpty() ? protocols::awg::defaultCookieReplyPacketJunkSize : awgServer.cookieReplyPacketJunkSize;
    if (awgClient.transportPacketJunkSize.isEmpty()) awgClient.transportPacketJunkSize = awgServer.transportPacketJunkSize.isEmpty() ? protocols::awg::defaultTransportPacketJunkSize : awgServer.transportPacketJunkSize;
    if (awgClient.initPacketMagicHeader.isEmpty()) awgClient.initPacketMagicHeader = awgServer.initPacketMagicHeader.isEmpty() ? protocols::awg::defaultInitPacketMagicHeader : awgServer.initPacketMagicHeader;
    if (awgClient.responsePacketMagicHeader.isEmpty()) awgClient.responsePacketMagicHeader = awgServer.responsePacketMagicHeader.isEmpty() ? protocols::awg::defaultResponsePacketMagicHeader : awgServer.responsePacketMagicHeader;
    if (awgClient.underloadPacketMagicHeader.isEmpty()) awgClient.underloadPacketMagicHeader = awgServer.underloadPacketMagicHeader.isEmpty() ? protocols::awg::defaultUnderloadPacketMagicHeader : awgServer.underloadPacketMagicHeader;
    if (awgClient.transportPacketMagicHeader.isEmpty()) awgClient.transportPacketMagicHeader = awgServer.transportPacketMagicHeader.isEmpty() ? protocols::awg::defaultTransportPacketMagicHeader : awgServer.transportPacketMagicHeader;
    if (awgClient.specialJunk1.isEmpty()) awgClient.specialJunk1 = awgServer.specialJunk1.isEmpty() ? protocols::awg::defaultSpecialJunk1 : awgServer.specialJunk1;
    if (awgClient.specialJunk2.isEmpty()) awgClient.specialJunk2 = awgServer.specialJunk2.isEmpty() ? protocols::awg::defaultSpecialJunk2 : awgServer.specialJunk2;
    if (awgClient.specialJunk3.isEmpty()) awgClient.specialJunk3 = awgServer.specialJunk3.isEmpty() ? protocols::awg::defaultSpecialJunk3 : awgServer.specialJunk3;
    if (awgClient.specialJunk4.isEmpty()) awgClient.specialJunk4 = awgServer.specialJunk4.isEmpty() ? protocols::awg::defaultSpecialJunk4 : awgServer.specialJunk4;
    if (awgClient.specialJunk5.isEmpty()) awgClient.specialJunk5 = awgServer.specialJunk5.isEmpty() ? protocols::awg::defaultSpecialJunk5 : awgServer.specialJunk5;
    awgClient.isObfuscationEnabled = true;
}

QHash<int, QByteArray> OwgConfigModel::roleNames() const
{
    return {
        { OpenVpnSubnetAddressRole, "openVpnSubnetAddress" },
        { OpenVpnTransportProtoRole, "openVpnTransportProto" },
        { OpenVpnPortRole, "openVpnPort" },
        { OpenVpnAutoNegotiateEncryptionRole, "openVpnAutoNegotiateEncryption" },
        { OpenVpnHashRole, "openVpnHash" },
        { OpenVpnCipherRole, "openVpnCipher" },
        { OpenVpnTlsAuthRole, "openVpnTlsAuth" },
        { OpenVpnBlockDnsRole, "openVpnBlockDns" },
        { OpenVpnAdditionalClientCommandsRole, "openVpnAdditionalClientCommands" },
        { OpenVpnAdditionalServerCommandsRole, "openVpnAdditionalServerCommands" },
        { AwgHostNameRole, "awgHostName" },
        { AwgPortRole, "awgPort" },
        { AwgClientMtuRole, "awgClientMtu" },
        { AwgJunkPacketCountRole, "awgJunkPacketCount" },
        { AwgJunkPacketMinSizeRole, "awgJunkPacketMinSize" },
        { AwgJunkPacketMaxSizeRole, "awgJunkPacketMaxSize" },
        { AwgInitPacketJunkSizeRole, "awgInitPacketJunkSize" },
        { AwgResponsePacketJunkSizeRole, "awgResponsePacketJunkSize" },
        { AwgCookieReplyPacketJunkSizeRole, "awgCookieReplyPacketJunkSize" },
        { AwgTransportPacketJunkSizeRole, "awgTransportPacketJunkSize" },
        { AwgInitPacketMagicHeaderRole, "awgInitPacketMagicHeader" },
        { AwgResponsePacketMagicHeaderRole, "awgResponsePacketMagicHeader" },
        { AwgUnderloadPacketMagicHeaderRole, "awgUnderloadPacketMagicHeader" },
        { AwgTransportPacketMagicHeaderRole, "awgTransportPacketMagicHeader" },
        { AwgSpecialJunk1Role, "awgSpecialJunk1" },
        { AwgSpecialJunk2Role, "awgSpecialJunk2" },
        { AwgSpecialJunk3Role, "awgSpecialJunk3" },
        { AwgSpecialJunk4Role, "awgSpecialJunk4" },
        { AwgSpecialJunk5Role, "awgSpecialJunk5" },
    };
}
