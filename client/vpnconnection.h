#ifndef VPNCONNECTION_H
#define VPNCONNECTION_H

#include <QObject>
#include <QMetaObject>
#include <QNetworkAccessManager>
#include <QString>
#include <QScopedPointer>
#include <QRemoteObjectNode>
#include <QSet>
#include <QTimer>

#include "protocols/vpnprotocol.h"
#include "core/defs.h"
#include "settings.h"

#ifdef AMNEZIA_DESKTOP
#include "core/ipcclient.h"
#endif

#ifdef Q_OS_ANDROID
#include "protocols/android_vpnprotocol.h"
#endif

using namespace amnezia;

class VpnConnection : public QObject
{
    Q_OBJECT

public:
    explicit VpnConnection(std::shared_ptr<Settings> settings, QObject* parent = nullptr);
    ~VpnConnection() override;

    static QString bytesPerSecToText(quint64 bytes);

    ErrorCode lastError() const;

    QSharedPointer<VpnProtocol> vpnProtocol() const;

    const QString &remoteAddress() const;
    void addSitesRoutes(const QString &gw, Settings::RouteMode mode);

#ifdef Q_OS_ANDROID
    void restoreConnection();
#endif

public slots:
    void connectToVpn(int serverIndex, const ServerCredentials &credentials, DockerContainer container, const QJsonObject &vpnConfiguration);
    void reconnectToVpn();
    void disconnectFromVpn();

    void onKillSwitchModeChanged(bool enabled);
    void disconnectSlots();

signals:
    void bytesChanged(quint64 receivedBytes, quint64 sentBytes);
    void connectionStateChanged(Vpn::ConnectionState state);
    void vpnProtocolError(amnezia::ErrorCode error);

    void serviceIsNotReady();

protected slots:
    void onBytesChanged(quint64 receivedBytes, quint64 sentBytes);
    void onConnectionStateChanged(Vpn::ConnectionState state);

    void setConnectionState(Vpn::ConnectionState state);

#ifdef AMNEZIA_DESKTOP
    void runHealthCheck();
    void onHealthCheckReplyFinished();
#endif

protected:
    QSharedPointer<VpnProtocol> m_vpnProtocol;

private:
    std::shared_ptr<Settings> m_settings;
    QJsonObject m_vpnConfiguration;
    QJsonObject m_routeMode;
    QString m_remoteAddress;

    // Only for iOS for now, check counters
    QTimer m_checkTimer;
#ifdef AMNEZIA_DESKTOP
    QTimer m_healthCheckTimer;
    QNetworkAccessManager *m_healthCheckManager = nullptr;
    QSet<QNetworkReply *> m_healthCheckReplies;
    quint64 m_healthCheckRoundId = 0;
    int m_pendingHealthCheckReplies = 0;
    int m_failedHealthCheckRounds = 0;
    bool m_healthCheckRoundActive = false;
    bool m_healthCheckRoundSucceeded = false;
#endif

#ifdef Q_OS_ANDROID
   AndroidVpnProtocol* androidVpnProtocol = nullptr;

   AndroidVpnProtocol* createDefaultAndroidVpnProtocol();
   void createAndroidConnections();
#endif

   Vpn::ConnectionState m_connectionState;

   void createProtocolConnections();

   void appendSplitTunnelingConfig();
   void appendKillSwitchConfig();

#ifdef AMNEZIA_DESKTOP
   void startHealthCheckMonitor();
   void stopHealthCheckMonitor();
   void abortHealthCheckReplies();
   void finalizeHealthCheckRound();
#endif
};

#endif // VPNCONNECTION_H
