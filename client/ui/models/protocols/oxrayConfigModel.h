#ifndef OXRAYCONFIGMODEL_H
#define OXRAYCONFIGMODEL_H

#include <QAbstractListModel>
#include <QJsonObject>

class OxrayConfigModel : public QAbstractListModel
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

        XraySiteRole,
        XrayPortRole
    };

    explicit OxrayConfigModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    bool setData(const QModelIndex &index, const QVariant &value, int role) override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;

public slots:
    void updateModel(const QJsonObject &config);
    QJsonObject getConfig();

protected:
    QHash<int, QByteArray> roleNames() const override;

private:
    QJsonObject m_fullConfig;
    QJsonObject m_openVpnProtocolConfig;
    QJsonObject m_xrayProtocolConfig;
};

#endif // OXRAYCONFIGMODEL_H
