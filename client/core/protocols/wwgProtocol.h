#ifndef WWGPROTOCOL_H
#define WWGPROTOCOL_H

#include "core/protocols/wireGuardProtocol.h"

// Runs a WWG profile as one AmneziaWG interface for the service: the exit layer
// is the visible tunnel, and the entry layer travels with the request so the
// service can nest it below the exit layer.
class WwgProtocol : public WireguardProtocol
{
    Q_OBJECT

public:
    explicit WwgProtocol(const QJsonObject &configuration, QObject *parent = nullptr);
    ~WwgProtocol() override;

    ErrorCode start() override;
};

#endif // WWGPROTOCOL_H
