import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import PageEnum 1.0
import Style 1.0

import "./"
import "../Controls2"
import "../Controls2/TextTypes"
import "../Config"
import "../Components"

PageType {
    id: root

    property bool useCustomDns: ServersModel.isProcessedServerCustomDnsEnabled()
    property string primaryDnsValue: ServersModel.processedServerPrimaryDns()
    property string secondaryDnsValue: ServersModel.processedServerSecondaryDns()

    BackButtonType {
        id: backButton

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 20 + SettingsController.safeAreaTopMargin
    }

    ListViewType {
        id: listView

        anchors.top: backButton.bottom
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right

        model: OxrayConfigModel

        delegate: ColumnLayout {
            width: listView.width
            spacing: 0

            BaseHeaderType {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("OXray Settings")
                descriptionText: qsTr("Traffic chain: device -> OpenVPN server -> XRay server -> internet.")
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("OpenVPN")
            }

            TextFieldWithHeaderType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("VPN address subnet")
                textField.text: openVpnSubnetAddress
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== openVpnSubnetAddress) openVpnSubnetAddress = textField.text
            }

            ParagraphTextType {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: qsTr("Network protocol")
            }

            TransportProtoSelector {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                rootWidth: root.width
                currentIndex: openVpnTransportProto === "tcp" ? 1 : 0
                onCurrentIndexChanged: {
                    var value = currentIndex === 1 ? "tcp" : "udp"
                    if (value !== openVpnTransportProto) {
                        openVpnTransportProto = value
                    }
                }
            }

            TextFieldWithHeaderType {
                id: openVpnPortField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("OpenVPN port")
                textField.text: openVpnPort
                textField.maximumLength: 5
                textField.validator: IntValidator { bottom: 1; top: 65535 }
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== openVpnPort) openVpnPort = textField.text
            }

            SwitcherType {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: qsTr("Auto-negotiate encryption")
                checked: openVpnAutoNegotiateEncryption
                onToggled: if (checked !== openVpnAutoNegotiateEncryption) openVpnAutoNegotiateEncryption = checked
            }

            TextFieldWithHeaderType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Hash")
                textField.text: openVpnHash
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== openVpnHash) openVpnHash = textField.text
            }

            TextFieldWithHeaderType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Cipher")
                textField.text: openVpnCipher
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== openVpnCipher) openVpnCipher = textField.text
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                radius: 16
                color: AmneziaStyle.color.onyxBlack
                implicitHeight: openVpnChecks.implicitHeight

                ColumnLayout {
                    id: openVpnChecks
                    anchors.fill: parent

                    CheckBoxType {
                        Layout.fillWidth: true
                        text: qsTr("TLS auth")
                        checked: openVpnTlsAuth
                        onCheckedChanged: if (checked !== openVpnTlsAuth) openVpnTlsAuth = checked
                    }

                    DividerType {}

                    CheckBoxType {
                        Layout.fillWidth: true
                        text: qsTr("Block DNS requests outside of VPN")
                        checked: openVpnBlockDns
                        onCheckedChanged: if (checked !== openVpnBlockDns) openVpnBlockDns = checked
                    }
                }
            }

            SwitcherType {
                id: extraClientSwitcher
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                checked: openVpnAdditionalClientCommands !== ""
                text: qsTr("Additional client configuration commands")
                onToggled: if (!checked) openVpnAdditionalClientCommands = ""
            }

            TextAreaType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                visible: extraClientSwitcher.checked
                textAreaText: openVpnAdditionalClientCommands
                placeholderText: qsTr("Commands:")
                textArea.onEditingFinished: {
                    if (openVpnAdditionalClientCommands !== textAreaText) {
                        openVpnAdditionalClientCommands = textAreaText
                    }
                }
            }

            SwitcherType {
                id: extraServerSwitcher
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                checked: openVpnAdditionalServerCommands !== ""
                text: qsTr("Additional server configuration commands")
                onToggled: if (!checked) openVpnAdditionalServerCommands = ""
            }

            TextAreaType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                visible: extraServerSwitcher.checked
                textAreaText: openVpnAdditionalServerCommands
                placeholderText: qsTr("Commands:")
                textArea.onEditingFinished: {
                    if (openVpnAdditionalServerCommands !== textAreaText) {
                        openVpnAdditionalServerCommands = textAreaText
                    }
                }
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 32
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("XRay")
            }

            TextFieldWithHeaderType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Disguised as traffic from")
                textField.text: xraySite
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== xraySite) xraySite = textField.text
            }

            TextFieldWithHeaderType {
                id: xrayPortField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("XRay port")
                textField.text: xrayPort
                textField.maximumLength: 5
                textField.validator: IntValidator { bottom: 1; top: 65535 }
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== xrayPort) xrayPort = textField.text
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 32
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("DNS")
            }

            SwitcherType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: qsTr("Use custom DNS")
                checked: root.useCustomDns
                onToggled: root.useCustomDns = checked
            }

            TextFieldWithHeaderType {
                id: primaryDnsField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                visible: root.useCustomDns
                headerText: qsTr("Primary DNS")
                checkEmptyText: root.useCustomDns
                textField.text: root.primaryDnsValue
                textField.validator: RegularExpressionValidator {
                    regularExpression: InstallController.ipAddressRegExp()
                }
                textField.onEditingFinished: root.primaryDnsValue = textField.text
            }

            TextFieldWithHeaderType {
                id: secondaryDnsField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                visible: root.useCustomDns
                headerText: qsTr("Secondary DNS")
                textField.text: root.secondaryDnsValue
                textField.validator: RegularExpressionValidator {
                    regularExpression: InstallController.ipAddressRegExp()
                }
                textField.onEditingFinished: root.secondaryDnsValue = textField.text
            }

            BasicButtonType {
                Layout.fillWidth: true
                Layout.topMargin: 32
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                enabled: openVpnPortField.errorText === ""
                         && xrayPortField.errorText === ""
                         && (!root.useCustomDns
                             || (primaryDnsField.errorText === ""
                                 && secondaryDnsField.errorText === ""
                                 && primaryDnsField.textField.text !== ""))
                text: qsTr("Save")
                onClicked: {
                    ServersModel.updateProcessedServerDns(root.useCustomDns, root.primaryDnsValue, root.secondaryDnsValue)
                    ServersModel.updateContainerConfig(ContainersModel.getProcessedContainerIndex(), OxrayConfigModel.getConfig())
                    if (ConnectionController.isConnected && ServersModel.getDefaultServerData("defaultContainer") === ContainersModel.getProcessedContainerIndex()) {
                        PageController.showNotificationMessage(qsTr("Settings saved. Reconnect to apply changes."))
                    } else {
                        PageController.showNotificationMessage(qsTr("Settings updated successfully"))
                    }
                }
            }

            BasicButtonType {
                Layout.fillWidth: true
                Layout.topMargin: 8
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                defaultColor: AmneziaStyle.color.transparent
                hoveredColor: AmneziaStyle.color.translucentWhite
                pressedColor: AmneziaStyle.color.sheerWhite
                disabledColor: AmneziaStyle.color.mutedGray
                textColor: AmneziaStyle.color.paleGray
                borderWidth: 1
                text: qsTr("Import from file")
                onClicked: {
                    var fileName = SystemController.getFileName(qsTr("Open OXray config"),
                                                                qsTr("Config files (*.vpn *.json *.ovpn *.conf)"))
                    if (fileName !== "" && ImportController.extractConfigFromFile(fileName)) {
                        PageController.goToPage(PageEnum.PageSetupWizardViewConfig)
                    }
                }
            }

            BasicButtonType {
                Layout.fillWidth: true
                Layout.topMargin: 8
                Layout.bottomMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                defaultColor: AmneziaStyle.color.transparent
                hoveredColor: AmneziaStyle.color.translucentWhite
                pressedColor: AmneziaStyle.color.sheerWhite
                disabledColor: AmneziaStyle.color.mutedGray
                textColor: AmneziaStyle.color.paleGray
                borderWidth: 1
                text: qsTr("Export native file / QR")
                onClicked: {
                    PageController.showBusyIndicator(true)
                    ExportController.generateOxrayNativeConfig()
                    PageController.showBusyIndicator(false)

                    var serverName = ServersModel.getProcessedServerData("name")
                    if (serverName === "") {
                        serverName = ServersModel.getProcessedServerData("hostName")
                    }

                    PageController.goToShareConnectionPage(
                                qsTr("OXray connection to ") + serverName,
                                qsTr("File with OXray native settings to ") + serverName,
                                qsTr("Save OXray native config"),
                                ".json",
                                "amnezia_for_oxray_native")
                }
            }
        }
    }
}
