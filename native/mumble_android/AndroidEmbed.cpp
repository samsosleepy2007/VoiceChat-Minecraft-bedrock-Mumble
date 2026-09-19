#include <QCoreApplication>
#include <QMetaObject>
#include <QtGlobal>

extern "C" Q_DECL_EXPORT void vc_mumble_server_request_stop() {
    QCoreApplication *application = QCoreApplication::instance();
    if (application == nullptr) {
        return;
    }

    QMetaObject::invokeMethod(application, "quit", Qt::QueuedConnection);
}
