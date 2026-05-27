import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import SortFilterProxyModel 0.2

import Style 1.0

import "./"
import "../Controls2"
import "../Controls2/TextTypes"
import "../Config"
import "../Components"

PageType {
    id: root

    BackButtonType {
        id: backButton

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 20 + PageController.safeAreaTopMargin

        onActiveFocusChanged: {
            if (backButton.enabled && backButton.activeFocus) {
                listView.positionViewAtBeginning()
            }
        }
    }

    SmartScroll {
        id: smartScroll
        listView: listView
    }

    ListViewType {
        id: listView

        anchors.top: backButton.bottom
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right

        model: OwgConfigModel

        delegate: ColumnLayout {
            id: delegateItem

            width: listView.width
            spacing: 0

            BaseHeaderType {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16

                headerText: qsTr("OWG settings")
                descriptionText: qsTr("OpenVPN underlay with AmneziaWG v2 over it.")
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("OpenVPN")
            }

            TextFieldWithHeaderType {
                id: openVpnSubnetField

                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16

                headerText: qsTr("VPN address subnet")
                textField.text: openVpnSubnetAddress
                checkEmptyText: true

                textField.onEditingFinished: {
                    if (textField.text !== openVpnSubnetAddress) {
                        openVpnSubnetAddress = textField.text
                    }
                }
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(openVpnSubnetField)
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
                    var nextProto = currentIndex === 1 ? "tcp" : "udp"
                    if (nextProto !== openVpnTransportProto) {
                        openVpnTransportProto = nextProto
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

                textField.onEditingFinished: {
                    if (textField.text !== openVpnPort) {
                        openVpnPort = textField.text
                    }
                }
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(openVpnPortField)
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
                implicitHeight: openVpnChecks.implicitHeight
                color: AmneziaStyle.color.onyxBlack
                radius: 16

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
                id: openVpnClientExtraSwitcher

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

                visible: openVpnClientExtraSwitcher.checked
                textAreaText: openVpnAdditionalClientCommands
                placeholderText: qsTr("Commands:")
                textArea.onEditingFinished: {
                    if (openVpnAdditionalClientCommands !== textAreaText) {
                        openVpnAdditionalClientCommands = textAreaText
                    }
                }
            }

            SwitcherType {
                id: openVpnServerExtraSwitcher

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

                visible: openVpnServerExtraSwitcher.checked
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
                headerText: qsTr("AmneziaWG v2")
            }

            TextFieldWithHeaderType {
                id: awgHostField

                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16

                headerText: qsTr("AWG endpoint host")
                textField.text: awgHostName
                checkEmptyText: true

                textField.onEditingFinished: if (textField.text !== awgHostName) awgHostName = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(awgHostField)
            }

            TextFieldWithHeaderType {
                id: awgPortField

                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16

                headerText: qsTr("AWG port")
                textField.text: awgPort
                textField.maximumLength: 5
                textField.validator: IntValidator { bottom: 1; top: 65535 }
                checkEmptyText: true

                textField.onEditingFinished: if (textField.text !== awgPort) awgPort = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(awgPortField)
            }

            AwgTextField {
                id: awgMtuField

                Layout.leftMargin: 16
                Layout.rightMargin: 16

                headerText: qsTr("MTU")
                textField.text: awgClientMtu
                textField.validator: IntValidator { bottom: 576; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgClientMtu) awgClientMtu = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(awgMtuField)
            }

            AwgTextField {
                id: junkPacketCountField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Jc - Junk packet count")
                textField.text: awgJunkPacketCount
                textField.validator: IntValidator { bottom: 0; top: 128 }
                textField.onEditingFinished: if (textField.text !== awgJunkPacketCount) awgJunkPacketCount = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(junkPacketCountField)
            }

            AwgTextField {
                id: junkPacketMinSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Jmin - Junk packet minimum size")
                textField.text: awgJunkPacketMinSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgJunkPacketMinSize) awgJunkPacketMinSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(junkPacketMinSizeField)
            }

            AwgTextField {
                id: junkPacketMaxSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Jmax - Junk packet maximum size")
                textField.text: awgJunkPacketMaxSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgJunkPacketMaxSize) awgJunkPacketMaxSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(junkPacketMaxSizeField)
            }

            AwgTextField {
                id: initPacketJunkSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("S1 - Init packet junk size")
                textField.text: awgInitPacketJunkSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgInitPacketJunkSize) awgInitPacketJunkSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(initPacketJunkSizeField)
            }

            AwgTextField {
                id: responsePacketJunkSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("S2 - Response packet junk size")
                textField.text: awgResponsePacketJunkSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgResponsePacketJunkSize) awgResponsePacketJunkSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(responsePacketJunkSizeField)
            }

            AwgTextField {
                id: cookieReplyPacketJunkSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("S3 - Cookie reply packet junk size")
                textField.text: awgCookieReplyPacketJunkSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgCookieReplyPacketJunkSize) awgCookieReplyPacketJunkSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(cookieReplyPacketJunkSizeField)
            }

            AwgTextField {
                id: transportPacketJunkSizeField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("S4 - Transport packet junk size")
                textField.text: awgTransportPacketJunkSize
                textField.validator: IntValidator { bottom: 0; top: 1500 }
                textField.onEditingFinished: if (textField.text !== awgTransportPacketJunkSize) awgTransportPacketJunkSize = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(transportPacketJunkSizeField)
            }

            AwgTextField {
                id: initPacketMagicHeaderField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("H1 - Init packet magic header")
                textField.text: awgInitPacketMagicHeader
                textField.validator: RegularExpressionValidator { regularExpression: /^(\d+)(-\d+)?$/ }
                textField.onEditingFinished: if (textField.text !== awgInitPacketMagicHeader) awgInitPacketMagicHeader = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(initPacketMagicHeaderField)
            }

            AwgTextField {
                id: responsePacketMagicHeaderField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("H2 - Response packet magic header")
                textField.text: awgResponsePacketMagicHeader
                textField.validator: RegularExpressionValidator { regularExpression: /^(\d+)(-\d+)?$/ }
                textField.onEditingFinished: if (textField.text !== awgResponsePacketMagicHeader) awgResponsePacketMagicHeader = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(responsePacketMagicHeaderField)
            }

            AwgTextField {
                id: underloadPacketMagicHeaderField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("H3 - Underload packet magic header")
                textField.text: awgUnderloadPacketMagicHeader
                textField.validator: RegularExpressionValidator { regularExpression: /^(\d+)(-\d+)?$/ }
                textField.onEditingFinished: if (textField.text !== awgUnderloadPacketMagicHeader) awgUnderloadPacketMagicHeader = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(underloadPacketMagicHeaderField)
            }

            AwgTextField {
                id: transportPacketMagicHeaderField
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("H4 - Transport packet magic header")
                textField.text: awgTransportPacketMagicHeader
                textField.validator: RegularExpressionValidator { regularExpression: /^(\d+)(-\d+)?$/ }
                textField.onEditingFinished: if (textField.text !== awgTransportPacketMagicHeader) awgTransportPacketMagicHeader = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(transportPacketMagicHeaderField)
            }

            AwgTextField {
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("I1 - Special junk 1")
                textField.text: awgSpecialJunk1
                checkEmptyText: false
                textField.onEditingFinished: if (textField.text !== awgSpecialJunk1) awgSpecialJunk1 = textField.text
            }

            AwgTextField {
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("I2 - Special junk 2")
                textField.text: awgSpecialJunk2
                checkEmptyText: false
                textField.onEditingFinished: if (textField.text !== awgSpecialJunk2) awgSpecialJunk2 = textField.text
            }

            AwgTextField {
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("I3 - Special junk 3")
                textField.text: awgSpecialJunk3
                checkEmptyText: false
                textField.onEditingFinished: if (textField.text !== awgSpecialJunk3) awgSpecialJunk3 = textField.text
            }

            AwgTextField {
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("I4 - Special junk 4")
                textField.text: awgSpecialJunk4
                checkEmptyText: false
                textField.onEditingFinished: if (textField.text !== awgSpecialJunk4) awgSpecialJunk4 = textField.text
            }

            AwgTextField {
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("I5 - Special junk 5")
                textField.text: awgSpecialJunk5
                checkEmptyText: false
                textField.onEditingFinished: if (textField.text !== awgSpecialJunk5) awgSpecialJunk5 = textField.text
            }

            BasicButtonType {
                id: saveButton

                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.bottomMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16

                enabled: openVpnSubnetField.errorText === ""
                         && openVpnPortField.errorText === ""
                         && awgHostField.errorText === ""
                         && awgPortField.errorText === ""
                         && awgMtuField.errorText === ""
                         && junkPacketCountField.errorText === ""
                         && junkPacketMinSizeField.errorText === ""
                         && junkPacketMaxSizeField.errorText === ""
                         && initPacketJunkSizeField.errorText === ""
                         && responsePacketJunkSizeField.errorText === ""
                         && cookieReplyPacketJunkSizeField.errorText === ""
                         && transportPacketJunkSizeField.errorText === ""
                         && initPacketMagicHeaderField.errorText === ""
                         && responsePacketMagicHeaderField.errorText === ""
                         && underloadPacketMagicHeaderField.errorText === ""
                         && transportPacketMagicHeaderField.errorText === ""

                text: qsTr("Save")

                clickedFunc: function() {
                    forceActiveFocus()

                    if (OwgConfigModel.isHeadersEqual(initPacketMagicHeaderField.textField.text,
                                                      responsePacketMagicHeaderField.textField.text,
                                                      underloadPacketMagicHeaderField.textField.text,
                                                      transportPacketMagicHeaderField.textField.text)) {
                        PageController.showErrorMessage(qsTr("The values of the H1-H4 fields must be unique"))
                        return
                    }

                    if (OwgConfigModel.isPacketSizeEqual(parseInt(initPacketJunkSizeField.textField.text),
                                                         parseInt(responsePacketJunkSizeField.textField.text),
                                                         parseInt(cookieReplyPacketJunkSizeField.textField.text),
                                                         parseInt(transportPacketJunkSizeField.textField.text))) {
                        PageController.showErrorMessage(qsTr("The value of the field S1 + message initiation size (148) must not equal S2 + message response size (92) + S3 + cookie reply size (64) + S4 + transport packet size (32)"))
                        return
                    }

                    if (ServersUiController.updateProcessedContainerConfig(ServersUiController.processedContainerIndex, OwgConfigModel.getConfig())) {
                        if (ConnectionController.isConnected && ServersModel.getDefaultServerData("defaultContainer") === ServersUiController.processedContainerIndex) {
                            PageController.showNotificationMessage(qsTr("Settings saved. Reconnect to apply changes."))
                        } else {
                            PageController.showNotificationMessage(qsTr("Settings updated successfully"))
                        }
                    } else {
                        PageController.showErrorMessage(qsTr("Unable to save OWG settings"))
                    }
                }
            }
        }
    }
}
