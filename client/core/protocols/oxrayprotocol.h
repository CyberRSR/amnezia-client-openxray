#ifndef OXRAYPROTOCOL_H
#define OXRAYPROTOCOL_H

#include <QScopedPointer>

#include "vpnprotocol.h"

class OpenVpnProtocol;
class XrayProtocol;

class OxrayProtocol : public VpnProtocol
{
    Q_OBJECT

public:
    explicit OxrayProtocol(const QJsonObject &configuration, QObject *parent = nullptr);
    ~OxrayProtocol() override;

    ErrorCode prepare() override;
    ErrorCode start() override;
    void stop() override;

private slots:
    void onOpenVpnStateChanged(Vpn::ConnectionState state);
    void onXrayStateChanged(Vpn::ConnectionState state);
    void onChildProtocolError(amnezia::ErrorCode errorCode);
    void onOpenVpnBytesChanged(quint64 receivedBytes, quint64 sentBytes);
    void onXrayBytesChanged(quint64 receivedBytes, quint64 sentBytes);

private:
    void createOpenVpnProtocol();
    void createXrayProtocol();
    void stopXrayProtocol();
    void prepareXrayConfiguration();

    QJsonObject m_openVpnConfiguration;
    QJsonObject m_xrayConfiguration;
    QJsonObject m_xrayBaseConfiguration;

    QScopedPointer<OpenVpnProtocol> m_openVpnProtocol;
    QScopedPointer<XrayProtocol> m_xrayProtocol;
    bool m_isStartingXray = false;
    QString m_openVpnRemoteAddress;
    QString m_xrayRemoteHost;
    QString m_xrayRemoteAddress;
};

#endif // OXRAYPROTOCOL_H
