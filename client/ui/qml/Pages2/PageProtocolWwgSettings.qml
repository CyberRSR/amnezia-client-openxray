import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import Style 1.0

import "./"
import "../Controls2"
import "../Controls2/TextTypes"
import "../Components"

PageType {
    id: root

    BackButtonType {
        id: backButton
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 20 + PageController.safeAreaTopMargin
        onActiveFocusChanged: if (backButton.enabled && backButton.activeFocus) listView.positionViewAtBeginning()
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
        model: WwgConfigModel

        delegate: ColumnLayout {
            width: listView.width
            spacing: 0

            BaseHeaderType {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("WWG settings")
                descriptionText: qsTr("Two %1 layers. The entry layer reaches only the exit endpoint; the exit layer owns the Android VPN interface.").arg(modeName)
            }

            ParagraphTextType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                color: AmneziaStyle.color.paleGray
                text: qsTr("Mode: %1. AWG3 keeps protocol_version=2 and is detected by its header-protection fields.").arg(modeName)
            }

            ParagraphTextType {
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                visible: !valid
                color: AmneziaStyle.color.vibrantRed
                text: validationError.length > 0
                      ? validationError
                      : qsTr("Both layers must use the same valid AmneziaWG mode. Re-import the two .conf files if validation fails.")
            }

            ParagraphTextType {
                Layout.fillWidth: true
                Layout.topMargin: 12
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                color: AmneziaStyle.color.goldenApricot
                text: qsTr("Use one WWG profile per device. Reusing one key and tunnel IP on several devices causes endpoint competition and reconnects.")
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Entry %1").arg(modeName)
            }

            TextFieldWithHeaderType {
                id: underlayHostField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Entry endpoint host")
                textField.text: underlayHostName
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== underlayHostName) underlayHostName = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(underlayHostField)
            }

            TextFieldWithHeaderType {
                id: underlayPortField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Entry port")
                textField.text: underlayPort
                textField.maximumLength: 5
                textField.validator: IntValidator { bottom: 1; top: 65535 }
                checkEmptyText: true
                textField.onEditingFinished: if (parseInt(textField.text) !== underlayPort) underlayPort = parseInt(textField.text)
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(underlayPortField)
            }

            TextFieldWithHeaderType {
                id: underlayMtuField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Entry MTU")
                textField.text: underlayMtu
                textField.validator: IntValidator { bottom: 576; top: 1500 }
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== underlayMtu) underlayMtu = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(underlayMtuField)
            }

            Header2Type {
                Layout.fillWidth: true
                Layout.topMargin: 32
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Exit %1").arg(modeName)
            }

            TextFieldWithHeaderType {
                id: overlayHostField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Exit endpoint host")
                textField.text: overlayHostName
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== overlayHostName) overlayHostName = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(overlayHostField)
            }

            TextFieldWithHeaderType {
                id: overlayPortField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Exit port")
                textField.text: overlayPort
                textField.maximumLength: 5
                textField.validator: IntValidator { bottom: 1; top: 65535 }
                checkEmptyText: true
                textField.onEditingFinished: if (parseInt(textField.text) !== overlayPort) overlayPort = parseInt(textField.text)
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(overlayPortField)
            }

            TextFieldWithHeaderType {
                id: overlayMtuField
                Layout.fillWidth: true
                Layout.topMargin: 16
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                headerText: qsTr("Exit MTU")
                textField.text: overlayMtu
                textField.validator: IntValidator { bottom: 576; top: 1500 }
                checkEmptyText: true
                textField.onEditingFinished: if (textField.text !== overlayMtu) overlayMtu = textField.text
                textField.onActiveFocusChanged: if (textField.activeFocus) smartScroll.scrollToItem(overlayMtuField)
            }

            BasicButtonType {
                Layout.fillWidth: true
                Layout.topMargin: 24
                Layout.bottomMargin: 24
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                enabled: valid
                         && underlayHostField.errorText === ""
                         && underlayPortField.errorText === ""
                         && underlayMtuField.errorText === ""
                         && overlayHostField.errorText === ""
                         && overlayPortField.errorText === ""
                         && overlayMtuField.errorText === ""
                text: qsTr("Save")
                clickedFunc: function() {
                    forceActiveFocus()
                    if (ServersUiController.updateProcessedContainerConfig(ServersUiController.processedContainerIndex,
                                                                             WwgConfigModel.getConfig())) {
                        PageController.showNotificationMessage(qsTr("Settings saved. Reconnect to apply changes."))
                    } else {
                        PageController.showErrorMessage(qsTr("Unable to save WWG settings"))
                    }
                }
            }
        }
    }
}
