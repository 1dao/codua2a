/* The Activity owns process lifecycle. Never replace ART's signal handlers. */
#include "xdaemon.h"
int xdaemon_init(int daemonize) { return daemonize ? -1 : 0; }
void xdaemon_uninit(void) {}
int xdaemon_is_daemon(void) { return 0; }
int xdaemon_should_stop(void) { return 0; }
