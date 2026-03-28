#include "oxrayConfigModel.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>

#include "protocols/protocols_defs.h"

namespace
{
QString openVpnRawConfig(const QJsonObject &protocolConfig)
{
    const auto lastConfig = QJsonDocument::fromJson(protocolConfig.value(amnezia::config_key::last_config).toString().toUtf8()).object();
    return lastConfig.value(amnezia::config_key::config).toString();
}

QString openVpnDirectiveValue(const QString &config, const QString &directive)
{
    const QRegularExpression rx(QString(R"((?m)^\s*%1\s+([^\r\n#;]+))").arg(QRegularExpression::escape(directive)));
    const auto match = rx.match(config);
    return match.hasMatch() ? match.captured(1).trimmed() : QString();
}

QString openVpnRemotePort(const QString &config)
{
    const QRegularExpression rx(R"((?m)^\s*remote\s+\S+\s+(\d+))");
    const auto match = rx.match(config);
    return match.hasMatch() ? match.captured(1).trimmed() : QString();
}

bool openVpnHasDirective(const QString &config, const QString &directive)
{
    const QRegularExpression rx(QString(R"((?m)^\s*%1(?:\s|$))").arg(QRegularExpression::escape(directive)));
    return rx.match(config).hasMatch();
}

QJsonObject xrayClientConfig(const QJsonObject &protocolConfig)
{
    return QJsonDocument::fromJson(protocolConfig.value(amnezia::config_key::last_config).toString().toUtf8()).object();
}

QString xraySite(const QJsonObject &protocolConfig)
{
    const auto config = xrayClientConfig(protocolConfig);
    const auto outbounds = config.value("outbounds").toArray();
    if (outbounds.isEmpty()) {
        return {};
    }
    const auto streamSettings = outbounds.at(0).toObject().value("streamSettings").toObject();
    return streamSettings.value("realitySettings").toObject().value("serverName").toString();
}

QString xrayPort(const QJsonObject &protocolConfig)
{
    const auto config = xrayClientConfig(protocolConfig);
    const auto outbounds = config.value("outbounds").toArray();
    if (outbounds.isEmpty()) {
        return {};
    }
    const auto outbound = outbounds.at(0).toObject();
    const auto vnext = outbound.value("settings").toObject().value("vnext").toArray();
    if (vnext.isEmpty()) {
        return {};
    }
    return QString::number(vnext.at(0).toObject().value("port").toInt());
}
} // namespace

OxrayConfigModel::OxrayConfigModel(QObject *parent) : QAbstractListModel(parent)
{
}

int OxrayConfigModel::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent);
    return 1;
}

bool OxrayConfigModel::setData(const QModelIndex &index, const QVariant &value, int role)
{
    if (!index.isValid() || index.row() < 0 || index.row() >= rowCount()) {
        return false;
    }

    switch (role) {
    case OpenVpnSubnetAddressRole: m_openVpnProtocolConfig.insert(amnezia::config_key::subnet_address, value.toString()); break;
    case OpenVpnTransportProtoRole: m_openVpnProtocolConfig.insert(amnezia::config_key::transport_proto, value.toString()); break;
    case OpenVpnPortRole: m_openVpnProtocolConfig.insert(amnezia::config_key::port, value.toString()); break;
    case OpenVpnAutoNegotiateEncryptionRole: m_openVpnProtocolConfig.insert(amnezia::config_key::ncp_disable, !value.toBool()); break;
    case OpenVpnHashRole: m_openVpnProtocolConfig.insert(amnezia::config_key::hash, value.toString()); break;
    case OpenVpnCipherRole: m_openVpnProtocolConfig.insert(amnezia::config_key::cipher, value.toString()); break;
    case OpenVpnTlsAuthRole: m_openVpnProtocolConfig.insert(amnezia::config_key::tls_auth, value.toBool()); break;
    case OpenVpnBlockDnsRole: m_openVpnProtocolConfig.insert(amnezia::config_key::block_outside_dns, value.toBool()); break;
    case OpenVpnAdditionalClientCommandsRole:
        m_openVpnProtocolConfig.insert(amnezia::config_key::additional_client_config, value.toString());
        break;
    case OpenVpnAdditionalServerCommandsRole:
        m_openVpnProtocolConfig.insert(amnezia::config_key::additional_server_config, value.toString());
        break;
    case XraySiteRole: m_xrayProtocolConfig.insert(amnezia::config_key::site, value.toString()); break;
    case XrayPortRole: m_xrayProtocolConfig.insert(amnezia::config_key::port, value.toString()); break;
    default: return false;
    }

    emit dataChanged(index, index, QList<int> { role });
    return true;
}

QVariant OxrayConfigModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() < 0 || index.row() >= rowCount()) {
        return {};
    }

    const QString rawOpenVpnConfig = openVpnRawConfig(m_openVpnProtocolConfig);

    switch (role) {
    case OpenVpnSubnetAddressRole:
        return m_openVpnProtocolConfig.value(amnezia::config_key::subnet_address)
                .toString(amnezia::protocols::openvpn::defaultSubnetAddress);
    case OpenVpnTransportProtoRole: {
        const auto value = m_openVpnProtocolConfig.value(amnezia::config_key::transport_proto).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "proto").toLower();
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::openvpn::defaultTransportProto) : parsed) : value;
    }
    case OpenVpnPortRole: {
        const auto value = m_openVpnProtocolConfig.value(amnezia::config_key::port).toString();
        const auto parsed = openVpnRemotePort(rawOpenVpnConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::openvpn::defaultPort) : parsed) : value;
    }
    case OpenVpnAutoNegotiateEncryptionRole:
        return !m_openVpnProtocolConfig.value(amnezia::config_key::ncp_disable)
                        .toBool(openVpnHasDirective(rawOpenVpnConfig, amnezia::protocols::openvpn::ncpDisableString));
    case OpenVpnHashRole: {
        const auto value = m_openVpnProtocolConfig.value(amnezia::config_key::hash).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "auth");
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::openvpn::defaultHash) : parsed) : value;
    }
    case OpenVpnCipherRole: {
        const auto value = m_openVpnProtocolConfig.value(amnezia::config_key::cipher).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "cipher");
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::openvpn::defaultCipher) : parsed) : value;
    }
    case OpenVpnTlsAuthRole:
        return m_openVpnProtocolConfig.value(amnezia::config_key::tls_auth)
                .toBool(openVpnHasDirective(rawOpenVpnConfig, "tls-auth") || rawOpenVpnConfig.contains("<tls-auth>"));
    case OpenVpnBlockDnsRole:
        return m_openVpnProtocolConfig.value(amnezia::config_key::block_outside_dns)
                .toBool(openVpnHasDirective(rawOpenVpnConfig, "block-outside-dns"));
    case OpenVpnAdditionalClientCommandsRole:
        return m_openVpnProtocolConfig.value(amnezia::config_key::additional_client_config)
                .toString(amnezia::protocols::openvpn::defaultAdditionalClientConfig);
    case OpenVpnAdditionalServerCommandsRole:
        return m_openVpnProtocolConfig.value(amnezia::config_key::additional_server_config)
                .toString(amnezia::protocols::openvpn::defaultAdditionalServerConfig);
    case XraySiteRole: {
        const auto value = m_xrayProtocolConfig.value(amnezia::config_key::site).toString();
        const auto parsed = xraySite(m_xrayProtocolConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::xray::defaultSite) : parsed) : value;
    }
    case XrayPortRole: {
        const auto value = m_xrayProtocolConfig.value(amnezia::config_key::port).toString();
        const auto parsed = xrayPort(m_xrayProtocolConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(amnezia::protocols::xray::defaultPort) : parsed) : value;
    }
    default: return {};
    }
}

void OxrayConfigModel::updateModel(const QJsonObject &config)
{
    beginResetModel();
    m_fullConfig = config;
    m_openVpnProtocolConfig = config.value(amnezia::config_key::openvpn).toObject();
    m_xrayProtocolConfig = config.value(amnezia::config_key::xray).toObject();
    endResetModel();
}

QJsonObject OxrayConfigModel::getConfig()
{
    m_fullConfig.insert(amnezia::config_key::openvpn, m_openVpnProtocolConfig);
    m_fullConfig.insert(amnezia::config_key::xray, m_xrayProtocolConfig);
    return m_fullConfig;
}

QHash<int, QByteArray> OxrayConfigModel::roleNames() const
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
        { XraySiteRole, "xraySite" },
        { XrayPortRole, "xrayPort" }
    };
}
