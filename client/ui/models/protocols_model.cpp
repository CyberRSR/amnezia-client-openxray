#include "protocols_model.h"

ProtocolsModel::ProtocolsModel(std::shared_ptr<Settings> settings, QObject *parent)
    : m_settings(settings), QAbstractListModel(parent)
{
}

int ProtocolsModel::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent);
    return m_protocolKeys.size();
}

QHash<int, QByteArray> ProtocolsModel::roleNames() const
{
    QHash<int, QByteArray> roles;

    roles[ProtocolNameRole] = "protocolName";
    roles[ServerProtocolPageRole] = "serverProtocolPage";
    roles[ClientProtocolPageRole] = "clientProtocolPage";
    roles[ProtocolIndexRole] = "protocolIndex";
    roles[RawConfigRole] = "rawConfig";
    roles[IsClientProtocolExistsRole] = "isClientProtocolExists";

    return roles;
}

QVariant ProtocolsModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() < 0 || index.row() >= m_protocolKeys.size()) {
        return QVariant();
    }

    const QString protocolKey = m_protocolKeys.at(index.row());
    const auto protocol = ProtocolProps::protoFromString(protocolKey);

    switch (role) {
    case ProtocolNameRole: {
        return ProtocolProps::protocolHumanNames().value(protocol);
    }
    case ServerProtocolPageRole:
        return static_cast<int>(serverProtocolPage(protocol));
    case ClientProtocolPageRole:
        return static_cast<int>(clientProtocolPage(protocol));
    case ProtocolIndexRole: return protocol;
    case RawConfigRole: {
        if (m_container == DockerContainer::OXray) {
            const auto openVpnConfig =
                    QJsonDocument::fromJson(m_content.value(config_key::openvpn).toObject().value(config_key::last_config).toString().toUtf8())
                            .object()
                            .value(config_key::config)
                            .toString();
            const auto xrayConfig = m_content.value(config_key::xray).toObject().value(config_key::last_config).toString();
            return QString("%1\n\n%2\n\n%3\n\n%4")
                    .arg(QObject::tr("OpenVPN config:"))
                    .arg(openVpnConfig)
                    .arg(QObject::tr("XRay config:"))
                    .arg(xrayConfig);
        }
        auto protocolConfig = m_content.value(ContainerProps::containerTypeToProtocolString(m_container)).toObject();
        auto lastConfigJsonDoc =
                QJsonDocument::fromJson(protocolConfig.value(config_key::last_config).toString().toUtf8());
        auto lastConfigJson = lastConfigJsonDoc.object();

        QString rawConfig;
        QStringList lines = lastConfigJson.value(config_key::config).toString().replace("\r", "").split("\n");
        for (const QString &l : lines) {
            rawConfig.append(l + "\n");
        }
        return rawConfig;
    }
    case IsClientProtocolExistsRole: {
        if (m_container == DockerContainer::OXray) {
            return !m_content.value(config_key::openvpn).toObject().value(config_key::last_config).toString().isEmpty()
                   && !m_content.value(config_key::xray).toObject().value(config_key::last_config).toString().isEmpty();
        }
        QString protocolKey = ContainerProps::containerTypeToProtocolString(m_container);
        auto protocolConfig = m_content.value(protocolKey).toObject();
        auto lastConfigJsonDoc =
                QJsonDocument::fromJson(protocolConfig.value(config_key::last_config).toString().toUtf8());
        auto lastConfigJson = lastConfigJsonDoc.object();

        auto configString = lastConfigJson.value(config_key::config).toString();
        return !configString.isEmpty();
    }
    }

    return QVariant();
}

void ProtocolsModel::updateModel(const QJsonObject &content)
{
    beginResetModel();
    m_container = ContainerProps::containerFromString(content.value(config_key::container).toString());

    m_content = content;
    m_content.remove(config_key::container);
    m_protocolKeys = m_content.keys();
    if (m_container == DockerContainer::OXray) {
        m_protocolKeys = { ProtocolProps::protoToString(Proto::OXray) };
    }
    endResetModel();
}

QJsonObject ProtocolsModel::getConfig()
{
    QJsonObject config = m_content;
    config.insert(config_key::container, ContainerProps::containerToString(m_container));
    return config;
}

PageLoader::PageEnum ProtocolsModel::serverProtocolPage(Proto protocol) const
{
    switch (protocol) {
    case Proto::OpenVpn: return PageLoader::PageEnum::PageProtocolOpenVpnSettings;
    case Proto::Cloak: return PageLoader::PageEnum::PageProtocolCloakSettings;
    case Proto::ShadowSocks: return PageLoader::PageEnum::PageProtocolShadowSocksSettings;
    case Proto::WireGuard: return PageLoader::PageEnum::PageProtocolWireGuardSettings;
    case Proto::Awg: return PageLoader::PageEnum::PageProtocolAwgSettings;
    case Proto::Ikev2: return PageLoader::PageEnum::PageProtocolIKev2Settings;
    case Proto::L2tp: return PageLoader::PageEnum::PageProtocolIKev2Settings;
    case Proto::Xray: return PageLoader::PageEnum::PageProtocolXraySettings;
    case Proto::OXray: return PageLoader::PageEnum::PageProtocolOxraySettings;
    
    // non-vpn
    case Proto::TorWebSite: return PageLoader::PageEnum::PageServiceTorWebsiteSettings;
    case Proto::Dns: return PageLoader::PageEnum::PageServiceDnsSettings;
    case Proto::Sftp: return PageLoader::PageEnum::PageServiceSftpSettings;
    case Proto::Socks5Proxy: return PageLoader::PageEnum::PageServiceSocksProxySettings;
    default: return PageLoader::PageEnum::PageProtocolOpenVpnSettings;
    }
}

PageLoader::PageEnum ProtocolsModel::clientProtocolPage(Proto protocol) const
{
    switch (protocol) {
    case Proto::WireGuard: return PageLoader::PageEnum::PageProtocolWireGuardClientSettings;
    case Proto::Awg: return PageLoader::PageEnum::PageProtocolAwgClientSettings;
    default: return PageLoader::PageEnum::PageProtocolOpenVpnSettings;
    }
}
