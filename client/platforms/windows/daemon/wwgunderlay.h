#ifndef WWGUNDERLAY_H
#define WWGUNDERLAY_H

#include <QObject>
#include <QJsonObject>
#include <QTimer>

class QLibrary;

// Hosts the WWG entry layer inside the privileged service: an AmneziaWG device
// running on an in-process netstack (awgnetstack.dll) plus the loopback UDP
// relay that carries the exit layer's datagrams into it.  The exit layer then
// uses 127.0.0.1:<relayPort> as its peer endpoint, so the whole exit tunnel
// travels inside the entry tunnel.
class WwgUnderlay : public QObject
{
    Q_OBJECT

public:
    struct Request
    {
        QJsonObject configData;
        QString relayHost;
        quint16 relayPort = 0;
        quint32 bindInterfaceIndex = 0;
        int overlayMtu = 0;
        int overlayS4 = 0;
        bool overlayIsAwgV3 = false;
    };

    explicit WwgUnderlay(QObject *parent = nullptr);
    ~WwgUnderlay() override;

    WwgUnderlay(const WwgUnderlay &) = delete;
    WwgUnderlay &operator=(const WwgUnderlay &) = delete;

    bool start(const Request &request, QString *error);
    void stop();

    bool isActive() const;
    bool isHealthy() const;
    quint16 relayPort() const;
    QString lastLog() const;

    static int minimumV3UnderlayMtu(int overlayMtu, int overlayS4);
    static int effectiveUnderlayMtu(int underlayMtu, int overlayMtu, int overlayS4, bool overlayIsAwgV3);
    static QString buildUapiSettings(const QJsonObject &configData);

signals:
    void healthLost();

private:
    bool loadLibrary();

    QLibrary *m_library = nullptr;
    QTimer m_healthTimer;
    int m_handle = -1;
    quint16 m_relayPort = 0;
};

#endif // WWGUNDERLAY_H
