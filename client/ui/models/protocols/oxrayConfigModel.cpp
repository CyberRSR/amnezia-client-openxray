#include "oxrayConfigModel.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>

#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

namespace
{
QString openVpnRawConfig(const QJsonObject &protocolConfig)
{
    const auto lastConfig = QJsonDocument::fromJson(protocolConfig.value(configKey::lastConfig).toString().toUtf8()).object();
    return lastConfig.value(configKey::config).toString();
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
    return QJsonDocument::fromJson(protocolConfig.value(configKey::lastConfig).toString().toUtf8()).object();
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
    case OpenVpnSubnetAddressRole: m_openVpnProtocolConfig.insert(configKey::subnetAddress, value.toString()); break;
    case OpenVpnTransportProtoRole: m_openVpnProtocolConfig.insert(configKey::transportProto, value.toString()); break;
    case OpenVpnPortRole: m_openVpnProtocolConfig.insert(configKey::port, value.toString()); break;
    case OpenVpnAutoNegotiateEncryptionRole: m_openVpnProtocolConfig.insert(configKey::ncpDisable, !value.toBool()); break;
    case OpenVpnHashRole: m_openVpnProtocolConfig.insert(configKey::hash, value.toString()); break;
    case OpenVpnCipherRole: m_openVpnProtocolConfig.insert(configKey::cipher, value.toString()); break;
    case OpenVpnTlsAuthRole: m_openVpnProtocolConfig.insert(configKey::tlsAuth, value.toBool()); break;
    case OpenVpnBlockDnsRole: m_openVpnProtocolConfig.insert(configKey::blockOutsideDns, value.toBool()); break;
    case OpenVpnAdditionalClientCommandsRole:
        m_openVpnProtocolConfig.insert(configKey::additionalClientConfig, value.toString());
        break;
    case OpenVpnAdditionalServerCommandsRole:
        m_openVpnProtocolConfig.insert(configKey::additionalServerConfig, value.toString());
        break;
    case XraySiteRole: m_xrayProtocolConfig.insert(configKey::site, value.toString()); break;
    case XrayPortRole: m_xrayProtocolConfig.insert(configKey::port, value.toString()); break;
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
        return m_openVpnProtocolConfig.value(configKey::subnetAddress)
                .toString(protocols::openvpn::defaultSubnetAddress);
    case OpenVpnTransportProtoRole: {
        const auto value = m_openVpnProtocolConfig.value(configKey::transportProto).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "proto").toLower();
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::openvpn::defaultTransportProto) : parsed) : value;
    }
    case OpenVpnPortRole: {
        const auto value = m_openVpnProtocolConfig.value(configKey::port).toString();
        const auto parsed = openVpnRemotePort(rawOpenVpnConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::openvpn::defaultPort) : parsed) : value;
    }
    case OpenVpnAutoNegotiateEncryptionRole:
        return !m_openVpnProtocolConfig.value(configKey::ncpDisable)
                        .toBool(openVpnHasDirective(rawOpenVpnConfig, protocols::openvpn::ncpDisableString));
    case OpenVpnHashRole: {
        const auto value = m_openVpnProtocolConfig.value(configKey::hash).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "auth");
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::openvpn::defaultHash) : parsed) : value;
    }
    case OpenVpnCipherRole: {
        const auto value = m_openVpnProtocolConfig.value(configKey::cipher).toString();
        const auto parsed = openVpnDirectiveValue(rawOpenVpnConfig, "cipher");
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::openvpn::defaultCipher) : parsed) : value;
    }
    case OpenVpnTlsAuthRole:
        return m_openVpnProtocolConfig.value(configKey::tlsAuth)
                .toBool(openVpnHasDirective(rawOpenVpnConfig, "tls-auth") || rawOpenVpnConfig.contains("<tls-auth>"));
    case OpenVpnBlockDnsRole:
        return m_openVpnProtocolConfig.value(configKey::blockOutsideDns)
                .toBool(openVpnHasDirective(rawOpenVpnConfig, "block-outside-dns"));
    case OpenVpnAdditionalClientCommandsRole:
        return m_openVpnProtocolConfig.value(configKey::additionalClientConfig)
                .toString(protocols::openvpn::defaultAdditionalClientConfig);
    case OpenVpnAdditionalServerCommandsRole:
        return m_openVpnProtocolConfig.value(configKey::additionalServerConfig)
                .toString(protocols::openvpn::defaultAdditionalServerConfig);
    case XraySiteRole: {
        const auto value = m_xrayProtocolConfig.value(configKey::site).toString();
        const auto parsed = xraySite(m_xrayProtocolConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::xray::defaultSite) : parsed) : value;
    }
    case XrayPortRole: {
        const auto value = m_xrayProtocolConfig.value(configKey::port).toString();
        const auto parsed = xrayPort(m_xrayProtocolConfig);
        return value.isEmpty() ? (parsed.isEmpty() ? QString(protocols::xray::defaultPort) : parsed) : value;
    }
    default: return {};
    }
}

void OxrayConfigModel::updateModel(const QJsonObject &config)
{
    beginResetModel();
    m_fullConfig = config;
    m_openVpnProtocolConfig = config.value(configKey::openvpn).toObject();
    m_xrayProtocolConfig = config.value(configKey::xray).toObject();
    endResetModel();
}

QJsonObject OxrayConfigModel::getConfig()
{
    m_fullConfig.insert(configKey::openvpn, m_openVpnProtocolConfig);
    m_fullConfig.insert(configKey::xray, m_xrayProtocolConfig);
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
