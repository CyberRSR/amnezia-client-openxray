#ifndef OWGCONFIGMODEL_H
#define OWGCONFIGMODEL_H

#include <QAbstractListModel>
#include <QJsonObject>

#include "core/models/protocols/owgProtocolConfig.h"
#include "core/utils/containerEnum.h"

class OwgConfigModel : public QAbstractListModel
{
    Q_OBJECT

public:
    enum Roles {
        OpenVpnSubnetAddressRole = Qt::UserRole + 1,
        OpenVpnTransportProtoRole,
        OpenVpnPortRole,
        OpenVpnAutoNegotiateEncryptionRole,
        OpenVpnHashRole,
        OpenVpnCipherRole,
        OpenVpnTlsAuthRole,
        OpenVpnBlockDnsRole,
        OpenVpnAdditionalClientCommandsRole,
        OpenVpnAdditionalServerCommandsRole,

        AwgHostNameRole,
        AwgPortRole,
        AwgClientMtuRole,
        AwgJunkPacketCountRole,
        AwgJunkPacketMinSizeRole,
        AwgJunkPacketMaxSizeRole,
        AwgInitPacketJunkSizeRole,
        AwgResponsePacketJunkSizeRole,
        AwgCookieReplyPacketJunkSizeRole,
        AwgTransportPacketJunkSizeRole,
        AwgInitPacketMagicHeaderRole,
        AwgResponsePacketMagicHeaderRole,
        AwgUnderloadPacketMagicHeaderRole,
        AwgTransportPacketMagicHeaderRole,
        AwgSpecialJunk1Role,
        AwgSpecialJunk2Role,
        AwgSpecialJunk3Role,
        AwgSpecialJunk4Role,
        AwgSpecialJunk5Role,
    };

    explicit OwgConfigModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    bool setData(const QModelIndex &index, const QVariant &value, int role) override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;

public slots:
    void updateModel(amnezia::DockerContainer container, const amnezia::OwgProtocolConfig &protocolConfig);
    QJsonObject getConfig();
    bool isHeadersEqual(const QString &h1, const QString &h2, const QString &h3, const QString &h4);
    bool isPacketSizeEqual(const int s1, const int s2, const int s3, const int s4);

protected:
    QHash<int, QByteArray> roleNames() const override;

private:
    void applyDefaults();

    amnezia::DockerContainer m_container = amnezia::DockerContainer::OWG;
    amnezia::OwgProtocolConfig m_protocolConfig;
};

#endif // OWGCONFIGMODEL_H
