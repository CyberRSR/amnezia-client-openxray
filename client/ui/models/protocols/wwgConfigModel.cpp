#include "wwgConfigModel.h"

#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"
#include "core/utils/containers/containerUtils.h"

using namespace amnezia;

WwgConfigModel::WwgConfigModel(QObject *parent)
    : QAbstractListModel(parent)
{
}

int WwgConfigModel::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent);
    return 1;
}

bool WwgConfigModel::setData(const QModelIndex &index, const QVariant &value, int role)
{
    if (!index.isValid() || index.row() != 0) {
        return false;
    }

    if (!m_protocolConfig.underlayAwgConfig.clientConfig.has_value()) {
        m_protocolConfig.underlayAwgConfig.clientConfig = AwgClientConfig {};
    }
    if (!m_protocolConfig.overlayAwgConfig.clientConfig.has_value()) {
        m_protocolConfig.overlayAwgConfig.clientConfig = AwgClientConfig {};
    }

    auto &underlay = m_protocolConfig.underlayAwgConfig.clientConfig.value();
    auto &overlay = m_protocolConfig.overlayAwgConfig.clientConfig.value();
    const QString text = value.toString().trimmed();
    switch (role) {
    case UnderlayHostNameRole: underlay.hostName = text; break;
    case UnderlayPortRole:
        underlay.port = value.toInt();
        m_protocolConfig.underlayAwgConfig.serverConfig.port = QString::number(underlay.port);
        break;
    case UnderlayMtuRole: underlay.mtu = text; break;
    case OverlayHostNameRole: overlay.hostName = text; break;
    case OverlayPortRole:
        overlay.port = value.toInt();
        m_protocolConfig.overlayAwgConfig.serverConfig.port = QString::number(overlay.port);
        break;
    case OverlayMtuRole: overlay.mtu = text; break;
    default: return false;
    }

    emit dataChanged(index, index, { role, ValidV2Role });
    return true;
}

QVariant WwgConfigModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() != 0) {
        return {};
    }

    const auto underlay = m_protocolConfig.underlayAwgConfig.clientConfig.value_or(AwgClientConfig {});
    const auto overlay = m_protocolConfig.overlayAwgConfig.clientConfig.value_or(AwgClientConfig {});
    switch (role) {
    case UnderlayHostNameRole: return underlay.hostName;
    case UnderlayPortRole: return underlay.port;
    case UnderlayMtuRole: return underlay.mtu;
    case OverlayHostNameRole: return overlay.hostName;
    case OverlayPortRole: return overlay.port;
    case OverlayMtuRole: return overlay.mtu;
    case ValidV2Role: return isValidV2();
    default: return {};
    }
}

void WwgConfigModel::updateModel(DockerContainer container, const WwgProtocolConfig &protocolConfig)
{
    beginResetModel();
    m_container = container;
    m_protocolConfig = protocolConfig;
    applyDefaults();
    endResetModel();
}

QJsonObject WwgConfigModel::getConfig()
{
    applyDefaults();
    QJsonObject container;
    container[configKey::container] = ContainerUtils::containerToString(m_container);
    container[configKey::wwg] = m_protocolConfig.toJson();
    return container;
}

bool WwgConfigModel::isValidV2() const
{
    return m_protocolConfig.isValidV2();
}

void WwgConfigModel::applyDefaults()
{
    const auto apply = [](AwgProtocolConfig &config) {
        config.serverConfig.protocolVersion = protocols::awg::awgV2;
        if (!config.clientConfig.has_value()) {
            config.clientConfig = AwgClientConfig {};
        }
        auto &client = config.clientConfig.value();
        if (client.mtu.isEmpty()) {
            client.mtu = protocols::awg::defaultMtu;
        }
        client.isObfuscationEnabled = true;
        if (config.serverConfig.port.isEmpty() && client.port > 0) {
            config.serverConfig.port = QString::number(client.port);
        }
    };
    apply(m_protocolConfig.underlayAwgConfig);
    apply(m_protocolConfig.overlayAwgConfig);
}

QHash<int, QByteArray> WwgConfigModel::roleNames() const
{
    return {
        { UnderlayHostNameRole, "underlayHostName" },
        { UnderlayPortRole, "underlayPort" },
        { UnderlayMtuRole, "underlayMtu" },
        { OverlayHostNameRole, "overlayHostName" },
        { OverlayPortRole, "overlayPort" },
        { OverlayMtuRole, "overlayMtu" },
        { ValidV2Role, "validV2" },
    };
}
