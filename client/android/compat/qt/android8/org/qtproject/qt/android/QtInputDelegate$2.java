// Copyright (C) 2026
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only

package org.qtproject.qt.android;

import android.app.Activity;
import android.view.View;

/*
 * Android 8 cannot load the upstream Qt 6.10 synthetic class because it extends
 * android.view.WindowInsetsAnimation.Callback, which was added in API 30. The
 * guarded code path that instantiates it is unreachable below API 30, so an
 * API-26-safe placeholder is enough to let the Qt input delegate load.
 */
class QtInputDelegate$2 {
    QtInputDelegate$2(QtInputDelegate delegate, int dispatchMode, View decorView, Activity activity,
            int x, int y, int width, int height, int inputHints, int enterKeyType) {
    }
}
