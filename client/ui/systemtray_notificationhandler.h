/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef SYSTEMTRAY_NOTIFICATIONHANDLER_H
#define SYSTEMTRAY_NOTIFICATIONHANDLER_H

#include "notificationhandler.h"

#include <QColor>
#include <QIcon>
#include <QMenu>
#include <QSystemTrayIcon>

class SystemTrayNotificationHandler : public NotificationHandler {
    Q_OBJECT

public:
    explicit SystemTrayNotificationHandler(QObject* parent);
    ~SystemTrayNotificationHandler();

    void setConnectionState(Vpn::ConnectionState state) override;

    void onTranslationsUpdated() override;

public slots:
    void updateWebsiteUrl(const QString &newWebsiteUrl);

protected:
    virtual void notify(Message type, const QString& title,
                        const QString& message, int timerMsec) override;

private:
    void showHideWindow();

    void setTrayState(Vpn::ConnectionState state);
    void onTrayActivated(QSystemTrayIcon::ActivationReason reason);

    QIcon createTrayIcon(const QColor &fillColor) const;
    void setTrayIcon(const QIcon &icon);
    void updateToolTip(Vpn::ConnectionState state);

private:
    QMenu m_menu;
    QSystemTrayIcon m_systemTrayIcon;

    QAction* m_trayActionShow = nullptr;
    QAction* m_trayActionConnect = nullptr;
    QAction* m_trayActionDisconnect = nullptr;
    QAction* m_trayActionVisitWebSite = nullptr;
    QAction* m_trayActionQuit = nullptr;
    QAction* m_statusLabel = nullptr;    
    QAction* m_separator = nullptr;
    QIcon m_connectedTrayIcon;
    QIcon m_disconnectedTrayIcon;
    QString  websiteUrl = "https://amnezia.org";
};

#endif  // SYSTEMTRAY_NOTIFICATIONHANDLER_H
