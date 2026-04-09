/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include <QDebug>
#include "systemtray_notificationhandler.h"


#ifdef Q_OS_MAC
#  include "platforms/macos/macosutils.h"
#endif

#include <QApplication>
#include <QColor>
#include <QDesktopServices>
#include <QIcon>
#include <QPainter>
#include <QWindow>

#include "version.h"

SystemTrayNotificationHandler::SystemTrayNotificationHandler(QObject* parent) :
    NotificationHandler(parent),
    m_systemTrayIcon(parent)

{
    m_connectedTrayIcon = createTrayIcon(QColor(QStringLiteral("#FFEA00")));
    m_disconnectedTrayIcon = createTrayIcon(Qt::white);

    m_trayActionShow =  m_menu.addAction(QIcon(":/images/tray/application.png"), tr("Show") + " " + APPLICATION_NAME, this, [this](){
        emit raiseRequested();
    });
    m_menu.addSeparator();
    m_trayActionConnect = m_menu.addAction(tr("Connect"), this, [this](){ emit connectRequested(); });
    m_trayActionDisconnect = m_menu.addAction(tr("Disconnect"), this, [this](){ emit disconnectRequested(); });

    m_menu.addSeparator();

    m_trayActionVisitWebSite = m_menu.addAction(QIcon(":/images/tray/link.png"), tr("Visit Website"), [&](){
        QDesktopServices::openUrl(QUrl(websiteUrl));
    });

    // Quit action: disconnect VPN first on macOS NE, else quit directly
    m_trayActionQuit = m_menu.addAction(QIcon(":/images/tray/cancel.png"),
                                       tr("Quit") + " " + APPLICATION_NAME,
                                       this,
                                       [&](){ qApp->quit(); });

    m_systemTrayIcon.setContextMenu(&m_menu);
    setTrayState(Vpn::ConnectionState::Disconnected);
    m_systemTrayIcon.show();
    connect(&m_systemTrayIcon, &QSystemTrayIcon::activated, this, &SystemTrayNotificationHandler::onTrayActivated);
}

SystemTrayNotificationHandler::~SystemTrayNotificationHandler() {
}

void SystemTrayNotificationHandler::setConnectionState(Vpn::ConnectionState state)
{
    setTrayState(state);
    NotificationHandler::setConnectionState(state);
}

void SystemTrayNotificationHandler::onTranslationsUpdated()
{
    m_trayActionShow->setText(tr("Show") + " " + APPLICATION_NAME);
    m_trayActionConnect->setText(tr("Connect"));
    m_trayActionDisconnect->setText(tr("Disconnect"));
    m_trayActionVisitWebSite->setText(tr("Visit Website"));
    m_trayActionQuit->setText(tr("Quit")+ " " + APPLICATION_NAME);
}

void SystemTrayNotificationHandler::updateWebsiteUrl(const QString &newWebsiteUrl) {
    qDebug() << "Updated website URL:" << newWebsiteUrl;
    websiteUrl = newWebsiteUrl;
}

QIcon SystemTrayNotificationHandler::createTrayIcon(const QColor &fillColor) const
{
    QPixmap pixmap(64, 64);
    pixmap.fill(Qt::transparent);

    QPainter painter(&pixmap);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setPen(QPen(QColor(0, 0, 0, 96), 2));
    painter.setBrush(fillColor);
    painter.drawEllipse(QRectF(12, 12, 40, 40));

    return QIcon(pixmap);
}

void SystemTrayNotificationHandler::setTrayIcon(const QIcon &icon)
{
    m_systemTrayIcon.setIcon(icon);
}

void SystemTrayNotificationHandler::onTrayActivated(QSystemTrayIcon::ActivationReason reason)
{
#ifndef Q_OS_MAC
    if(reason == QSystemTrayIcon::DoubleClick || reason == QSystemTrayIcon::Trigger) {
        emit raiseRequested();
    }
#endif
}

void SystemTrayNotificationHandler::setTrayState(Vpn::ConnectionState state)
{
    switch (state) {
    case Vpn::ConnectionState::Disconnected:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(true);
        m_trayActionDisconnect->setEnabled(false);
        break;
    case Vpn::ConnectionState::Preparing:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        break;
    case Vpn::ConnectionState::Connecting:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        break;
    case Vpn::ConnectionState::Connected:
        setTrayIcon(m_connectedTrayIcon);
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        break;
    case Vpn::ConnectionState::Disconnecting:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        break;
    case Vpn::ConnectionState::Reconnecting:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        break;
    case Vpn::ConnectionState::Error:
        setTrayIcon(m_disconnectedTrayIcon);
        m_trayActionConnect->setEnabled(true);
        m_trayActionDisconnect->setEnabled(false);
        break;
    case Vpn::ConnectionState::Unknown:
    default:
        m_trayActionConnect->setEnabled(false);
        m_trayActionDisconnect->setEnabled(true);
        setTrayIcon(m_disconnectedTrayIcon);
    }

    updateToolTip(state);
}

void SystemTrayNotificationHandler::updateToolTip(Vpn::ConnectionState state)
{
    m_systemTrayIcon.setToolTip(QStringLiteral("%1: %2").arg(APPLICATION_NAME, VpnProtocol::textConnectionState(state)));
}

void SystemTrayNotificationHandler::notify(NotificationHandler::Message type,
                                           const QString& title,
                                           const QString& message,
                                           int timerMsec) {
  Q_UNUSED(type);

  m_systemTrayIcon.showMessage(title, message, m_systemTrayIcon.icon(), timerMsec);
}

void SystemTrayNotificationHandler::showHideWindow() {
//  QmlEngineHolder* engine = QmlEngineHolder::instance();
//  if (engine->window()->isVisible()) {
//    engine->hideWindow();
//#ifdef MVPN_MACOS
//    MacOSUtils::hideDockIcon();
//#endif
//  } else {
//    engine->showWindow();
//#ifdef MVPN_MACOS
//    MacOSUtils::showDockIcon();
//#endif
//  }
}

