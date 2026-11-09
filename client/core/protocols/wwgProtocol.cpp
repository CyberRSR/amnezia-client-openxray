#include "wwgProtocol.h"

#include "core/protocols/protocolUtils.h"
#include "core/utils/constants/configKeys.h"
#include "core/utils/networkUtilities.h"

using namespace amnezia;

WwgProtocol::WwgProtocol(const QJsonObject &configuration, QObject *parent)
    : WireguardProtocol(configuration, parent)
{
}

WwgProtocol::~WwgProtocol() = default;

ErrorCode WwgProtocol::start()
{
    auto underlay = m_rawConfig.value(configKey::awgUnderlayConfigData).toObject();
    auto overlay = m_rawConfig.value(configKey::awgOverlayConfigData).toObject();

    if (underlay.isEmpty() || overlay.isEmpty()) {
        qCritical() << "WWG configuration is missing an AmneziaWG layer";
        setLastError(ErrorCode::InternalError);
        return ErrorCode::InternalError;
    }

    const QString underlayHost = NetworkUtilities::getIPAddress(underlay.value(configKey::hostName).toString());
    const QString overlayHost = NetworkUtilities::getIPAddress(overlay.value(configKey::hostName).toString());
    if (underlayHost.isEmpty() || overlayHost.isEmpty()) {
        qCritical() << "Unable to resolve WWG endpoints";
        setLastError(ErrorCode::InternalError);
        return ErrorCode::InternalError;
    }

    underlay[configKey::hostName] = underlayHost;
    overlay[configKey::hostName] = overlayHost;

    m_rawConfig[configKey::awgUnderlayConfigData] = underlay;
    m_rawConfig.insert(ProtocolUtils::key_proto_config_data(Proto::Awg), overlay);
    m_rawConfig[configKey::vpnProto] = ProtocolUtils::protoToString(Proto::Awg);
    m_rawConfig[configKey::hostName] = underlayHost;

    return WireguardProtocol::start();
}
