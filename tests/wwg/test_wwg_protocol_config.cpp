#include <QByteArray>
#include <QJsonObject>
#include <QtTest>

#include "core/models/protocols/wwgProtocolConfig.h"
#include "core/utils/constants/configKeys.h"
#include "core/utils/constants/protocolConstants.h"

using namespace amnezia;

namespace
{
AwgProtocolConfig makeLayer(bool v3, const QString &headerProtectionKey = {})
{
    AwgProtocolConfig layer;
    layer.serverConfig.protocolVersion = protocols::awg::awgV2;

    AwgClientConfig client;
    client.hostName = QStringLiteral("vpn.example.test");
    client.port = 55425;
    client.clientIp = QStringLiteral("10.9.1.2/32");
    client.clientPrivateKey = QStringLiteral("client-private-key");
    client.serverPublicKey = QStringLiteral("server-public-key");
    client.allowedIps = { QStringLiteral("0.0.0.0/0"), QStringLiteral("::/0") };
    client.persistentKeepAlive = QStringLiteral("25");
    client.mtu = QStringLiteral("1280");
    client.junkPacketCount = QStringLiteral("4");
    client.junkPacketMinSize = QStringLiteral("10");
    client.junkPacketMaxSize = QStringLiteral("50");
    client.initPacketJunkSize = v3 ? QStringLiteral("8") : QStringLiteral("0");
    client.responsePacketJunkSize = v3 ? QStringLiteral("9") : QStringLiteral("0");
    client.cookieReplyPacketJunkSize = v3 ? QStringLiteral("10") : QStringLiteral("0");
    client.transportPacketJunkSize = v3 ? QStringLiteral("14") : QStringLiteral("0");
    client.initPacketMagicHeader = QStringLiteral("101");
    client.responsePacketMagicHeader = QStringLiteral("102");
    client.underloadPacketMagicHeader = QStringLiteral("103");
    client.transportPacketMagicHeader = QStringLiteral("104");

    if (v3) {
        layer.serverConfig.headerProtectionKey = headerProtectionKey;
        client.headerProtectionKey = headerProtectionKey;
        client.contentPaddingAddition = QStringLiteral("0-16");
        client.rekeyAfterTime = QStringLiteral("120");
        client.rekeyTimeout = QStringLiteral("5-10");
        client.rejectAfterTime = QStringLiteral("180");
        client.keepaliveTimeout = QStringLiteral("15");
        client.maxHandshakeAttempts = QStringLiteral("10");
    }

    layer.clientConfig = client;
    return layer;
}

WwgProtocolConfig makeWwg(bool v3)
{
    const QString entryHpk = QString::fromLatin1(QByteArray(32, '\x5a').toBase64());
    const QString exitHpk = QString::fromLatin1(QByteArray(32, '\x6b').toBase64());
    WwgProtocolConfig config;
    config.underlayAwgConfig = makeLayer(v3, entryHpk);
    config.overlayAwgConfig = makeLayer(v3, exitHpk);
    config.overlayAwgConfig.clientConfig->hostName = QStringLiteral("exit.example.test");
    config.overlayAwgConfig.clientConfig->port = 35162;
    config.overlayAwgConfig.clientConfig->clientIp = QStringLiteral("10.8.2.2/32");
    return config;
}

WwgProvisioningConfig makeProvisioning()
{
    WwgProvisioningConfig provisioning;
    provisioning.underlay.url = QStringLiteral("https://entry.example.test:55426/v1");
    provisioning.underlay.token = QString(48, QLatin1Char('a'));
    provisioning.underlay.certificateSha256 = QString(64, QLatin1Char('b'));
    provisioning.overlay.url = QStringLiteral("https://exit.example.test:35163/v1");
    provisioning.overlay.token = QString(48, QLatin1Char('c'));
    provisioning.overlay.certificateSha256 = QString(64, QLatin1Char('d'));
    return provisioning;
}
} // namespace

class WwgProtocolConfigTest : public QObject
{
    Q_OBJECT

private slots:
    void acceptsV2AndPreservesCompatibility()
    {
        const WwgProtocolConfig config = makeWwg(false);
        QVERIFY2(config.isValidV2(), qPrintable(config.validationError()));
        QCOMPARE(config.mode(), WwgProtocolConfig::Mode::V2);
        QCOMPARE(config.underlayAwgConfig.serverConfig.protocolVersion, QStringLiteral("2"));
    }

    void acceptsV3AndRoundTripsAllFields()
    {
        const WwgProtocolConfig config = makeWwg(true);
        QVERIFY2(config.isValidV3(), qPrintable(config.validationError()));
        QCOMPARE(config.mode(), WwgProtocolConfig::Mode::V3);

        const WwgProtocolConfig restored = WwgProtocolConfig::fromJson(config.toJson());
        QVERIFY2(restored.isValidV3(), qPrintable(restored.validationError()));
        QCOMPARE(restored.underlayAwgConfig.serverConfig.headerProtectionKey,
                 config.underlayAwgConfig.serverConfig.headerProtectionKey);
        QCOMPARE(restored.overlayAwgConfig.clientConfig->contentPaddingAddition, QStringLiteral("0-16"));
        QCOMPARE(restored.overlayAwgConfig.clientConfig->rekeyAfterTime, QStringLiteral("120"));
        QCOMPARE(restored.overlayAwgConfig.clientConfig->rekeyTimeout, QStringLiteral("5-10"));
        QCOMPARE(restored.overlayAwgConfig.clientConfig->rejectAfterTime, QStringLiteral("180"));
        QCOMPARE(restored.overlayAwgConfig.clientConfig->keepaliveTimeout, QStringLiteral("15"));
        QCOMPARE(restored.overlayAwgConfig.clientConfig->maxHandshakeAttempts, QStringLiteral("10"));
    }

    void rejectsMixedModesAndBrokenHeaderProtectionKeys()
    {
        WwgProtocolConfig mixed = makeWwg(true);
        mixed.overlayAwgConfig = makeLayer(false);
        QVERIFY(!mixed.isValid());
        QVERIFY(mixed.validationError().contains(QStringLiteral("cannot mix")));

        WwgProtocolConfig broken = makeWwg(true);
        broken.overlayAwgConfig.clientConfig->headerProtectionKey = QStringLiteral("not-base64");
        QVERIFY(!broken.isValid());
        QVERIFY(broken.validationError().contains(QStringLiteral("HeaderProtectionKey")));

        WwgProtocolConfig mismatched = makeWwg(true);
        mismatched.overlayAwgConfig.clientConfig->headerProtectionKey =
                QString::fromLatin1(QByteArray(32, '\x33').toBase64());
        QVERIFY(!mismatched.isValid());
        QVERIFY(mismatched.validationError().contains(QStringLiteral("do not match")));
    }

    void rejectsInvalidV3PaddingAndRanges()
    {
        WwgProtocolConfig config = makeWwg(true);
        config.underlayAwgConfig.clientConfig->transportPacketJunkSize = QStringLiteral("7");
        QVERIFY(!config.isValid());
        QVERIFY(config.validationError().contains(QStringLiteral("S4 >= 8")));

        config = makeWwg(true);
        config.overlayAwgConfig.clientConfig->rekeyTimeout = QStringLiteral("20-10");
        QVERIFY(!config.isValid());
        QVERIFY(config.validationError().contains(QStringLiteral("range")));
    }

    void acceptsOfficialOffRangeValue()
    {
        WwgProtocolConfig config = makeWwg(true);
        config.underlayAwgConfig.clientConfig->contentPaddingAddition = QStringLiteral("(off)");
        config.overlayAwgConfig.clientConfig->persistentKeepAlive = QStringLiteral("(OFF)");
        QVERIFY2(config.isValid(), qPrintable(config.validationError()));
    }

    void omitsEmptySpecialJunkFields()
    {
        WwgProtocolConfig config = makeWwg(false);
        config.underlayAwgConfig.serverConfig.specialJunk1 = QStringLiteral("  ");
        config.underlayAwgConfig.clientConfig->specialJunk1 = QStringLiteral("\t");
        const QJsonObject serverJson = config.underlayAwgConfig.serverConfig.toJson();
        const QJsonObject clientJson = config.underlayAwgConfig.clientConfig->toJson();

        QVERIFY(!serverJson.contains(configKey::specialJunk1));
        QVERIFY(!serverJson.contains(configKey::specialJunk5));
        QVERIFY(!clientJson.contains(configKey::specialJunk1));
        QVERIFY(!clientJson.contains(configKey::specialJunk5));
    }

    void comparesAllAwg3ServerSettings()
    {
        AwgServerConfig baseline = makeLayer(true,
                QString::fromLatin1(QByteArray(32, '\x5a').toBase64())).serverConfig;
        AwgServerConfig changed = baseline;
        QVERIFY(baseline.hasEqualServerSettings(changed));

        changed.rekeyTimeout = QStringLiteral("9-12");
        QVERIFY(!baseline.hasEqualServerSettings(changed));
    }

    void preservesValidatedProvisioningCapability()
    {
        WwgProtocolConfig config = makeWwg(true);
        config.provisioning = makeProvisioning();
        QVERIFY(config.canProvisionPeers());

        const WwgProtocolConfig restored = WwgProtocolConfig::fromJson(config.toJson());
        QVERIFY(restored.canProvisionPeers());
        QCOMPARE(restored.provisioning->underlay.url, config.provisioning->underlay.url);
        QCOMPARE(restored.provisioning->overlay.token, config.provisioning->overlay.token);
        QCOMPARE(restored.provisioning->overlay.certificateSha256,
                 config.provisioning->overlay.certificateSha256);
    }

    void deviceOnlyCopyDropsProvisioningCapability()
    {
        WwgProtocolConfig master = makeWwg(true);
        master.provisioning = makeProvisioning();
        QVERIFY(master.canProvisionPeers());

        WwgProtocolConfig deviceOnly = master;
        deviceOnly.clearProvisioning();

        QVERIFY(!deviceOnly.canProvisionPeers());
        QVERIFY(!deviceOnly.toJson().contains(configKey::wwgProvisioning));
        QVERIFY(master.canProvisionPeers());
        QVERIFY(master.toJson().contains(configKey::wwgProvisioning));
        QVERIFY2(deviceOnly.isValid(), qPrintable(deviceOnly.validationError()));
    }

    void rejectsUnsafeProvisioningCapability()
    {
        WwgProtocolConfig config = makeWwg(true);
        config.provisioning = makeProvisioning();
        config.provisioning->underlay.url = QStringLiteral("http://entry.example.test:55426/v1");
        QVERIFY(!config.canProvisionPeers());

        config.provisioning = makeProvisioning();
        config.provisioning->underlay.url =
                QStringLiteral("https://embedded:credentials@entry.example.test:55426/v1");
        QVERIFY(!config.canProvisionPeers());

        config.provisioning = makeProvisioning();
        config.provisioning->underlay.url =
                QStringLiteral("https://entry.example.test:55426/v1?redirect=elsewhere");
        QVERIFY(!config.canProvisionPeers());

        config.provisioning = makeProvisioning();
        config.provisioning->overlay.token = QStringLiteral("short");
        QVERIFY(!config.canProvisionPeers());

        config.provisioning = makeProvisioning();
        config.provisioning->overlay.certificateSha256 = QStringLiteral("not-a-pin");
        QVERIFY(!config.canProvisionPeers());
    }
};

QTEST_APPLESS_MAIN(WwgProtocolConfigTest)

#include "test_wwg_protocol_config.moc"
