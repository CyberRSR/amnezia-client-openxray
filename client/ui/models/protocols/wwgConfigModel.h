#ifndef WWGCONFIGMODEL_H
#define WWGCONFIGMODEL_H

#include <QAbstractListModel>
#include <QJsonObject>

#include "core/models/protocols/wwgProtocolConfig.h"
#include "core/utils/containerEnum.h"

class WwgConfigModel : public QAbstractListModel
{
    Q_OBJECT

public:
    enum Roles {
        UnderlayHostNameRole = Qt::UserRole + 1,
        UnderlayPortRole,
        UnderlayMtuRole,
        OverlayHostNameRole,
        OverlayPortRole,
        OverlayMtuRole,
        ValidV2Role,
        ValidV3Role,
        ValidRole,
        ModeRole,
        ValidationErrorRole,
    };

    explicit WwgConfigModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    bool setData(const QModelIndex &index, const QVariant &value, int role) override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;

public slots:
    void updateModel(amnezia::DockerContainer container, const amnezia::WwgProtocolConfig &protocolConfig);
    QJsonObject getConfig();
    bool isValid() const;
    bool isValidV2() const;
    bool isValidV3() const;
    QString mode() const;
    QString validationError() const;

protected:
    QHash<int, QByteArray> roleNames() const override;

private:
    void applyDefaults();

    amnezia::DockerContainer m_container = amnezia::DockerContainer::WWG;
    amnezia::WwgProtocolConfig m_protocolConfig;
};

#endif // WWGCONFIGMODEL_H
