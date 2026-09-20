#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/*
 * Android compatibility launcher for the checksum-verified PortWarp Linux
 * ARM64 binary.
 *
 * The official static Go binary reads /etc/resolv.conf. During CI that exact
 * 16-byte string is replaced with /proc/self/fd/10. This launcher opens the
 * Android-generated resolver file, duplicates it onto fd 10, and execs pwrp.
 *
 * fd 10 intentionally remains open across exec so pwrp and any child daemon
 * can resolve hostnames through the same snapshot of Android DNS servers.
 */
int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: portwarp-dns-launcher <resolv.conf> <pwrp> [args...]\n");
        return 64;
    }

    const char *resolv_path = argv[1];
    const char *pwrp_path = argv[2];

    int fd = open(resolv_path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "open resolver file failed: %s\n", strerror(errno));
        return 65;
    }

    if (fd != 10) {
        if (dup2(fd, 10) < 0) {
            fprintf(stderr, "dup2 resolver fd failed: %s\n", strerror(errno));
            close(fd);
            return 66;
        }
        close(fd);
    }

    int flags = fcntl(10, F_GETFD);
    if (flags >= 0) {
        (void) fcntl(10, F_SETFD, flags & ~FD_CLOEXEC);
    }

    argv[2] = (char *) pwrp_path;
    execv(pwrp_path, &argv[2]);

    fprintf(stderr, "exec pwrp failed: %s\n", strerror(errno));
    return 67;
}
