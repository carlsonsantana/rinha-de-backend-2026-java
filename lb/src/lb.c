#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAX_WORKERS 8

/* kind is in bits 32-39 of the epoll tag; worker index in bits 24-31 */
#define TAG_TCP_LISTEN  0x0100000000ULL
#define TAG_WORKER_CTRL 0x0200000000ULL
#define TAG_RECONNECT_T 0x0300000000ULL

static const char RESP_503[] =
    "HTTP/1.1 503 Service Unavailable\r\n"
    "Content-Length: 0\r\n"
    "Connection: close\r\n\r\n";

typedef struct {
    int  ctrl_fd;
    int  timer_fd;
    char path[108];
} Worker;

static Worker workers[MAX_WORKERS];
static int    nworkers      = 0;
static int    epfd          = -1;
static int    tcp_listen_fd = -1;

static void epoll_add(int fd, uint32_t events, uint64_t tag) {
    struct epoll_event ev;
    ev.events   = events;
    ev.data.u64 = tag | (uint32_t)fd;
    epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev);
}

static void epoll_del(int fd) {
    epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
}

/* send payload_fd to peer via SCM_RIGHTS on ctrl_fd */
static int send_fd(int ctrl_fd, int payload_fd) {
    char dummy = 'F';
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov   = { .iov_base = &dummy, .iov_len = 1 };
    struct msghdr msg  = {
        .msg_name       = NULL,
        .msg_namelen    = 0,
        .msg_iov        = &iov,
        .msg_iovlen     = 1,
        .msg_control    = cbuf,
        .msg_controllen = sizeof(cbuf),
    };
    struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
    cm->cmsg_level = SOL_SOCKET;
    cm->cmsg_type  = SCM_RIGHTS;
    cm->cmsg_len   = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cm), &payload_fd, sizeof(int));
    return (int)sendmsg(ctrl_fd, &msg, MSG_NOSIGNAL);
}

static void schedule_reconnect(int wi);

/* try once; returns 1 on success, 0 on failure */
static int try_connect_worker_once(int wi) {
    Worker *w = &workers[wi];
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return 0;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, w->path, sizeof(sa.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        close(fd);
        return 0;
    }
    w->ctrl_fd = fd;
    epoll_add(fd, EPOLLRDHUP | EPOLLERR | EPOLLHUP,
              TAG_WORKER_CTRL | (uint32_t)wi << 24);
    fprintf(stderr, "[lb] worker %d connected: %s\n", wi, w->path);
    return 1;
}

/* retry-based connect used during epoll runtime (reconnect after disconnect) */
static void try_connect_worker(int wi) {
    if (!try_connect_worker_once(wi)) schedule_reconnect(wi);
}

static void schedule_reconnect(int wi) {
    Worker *w = &workers[wi];
    if (w->timer_fd >= 0) return; /* already scheduled */
    int tfd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (tfd < 0) return;
    struct itimerspec its;
    memset(&its, 0, sizeof(its));
    its.it_value.tv_nsec = 50 * 1000000L; /* 50 ms */
    timerfd_settime(tfd, 0, &its, NULL);
    w->timer_fd = tfd;
    epoll_add(tfd, EPOLLIN, TAG_RECONNECT_T | (uint32_t)wi << 24);
}

static int pick_worker(void) {
    static int rr = 0;
    for (int i = 0; i < nworkers; i++) {
        int wi = (rr + i) % nworkers;
        if (workers[wi].ctrl_fd >= 0) {
            rr = (wi + 1) % nworkers;
            return wi;
        }
    }
    return -1;
}

static void handle_tcp_accept(void) {
    while (1) {
        int fd = accept4(tcp_listen_fd, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) break;
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        int wi = pick_worker();
        if (wi < 0 || workers[wi].ctrl_fd < 0) {
            write(fd, RESP_503, sizeof(RESP_503) - 1);
            close(fd);
            continue;
        }
        if (send_fd(workers[wi].ctrl_fd, fd) < 0) {
            write(fd, RESP_503, sizeof(RESP_503) - 1);
            close(fd);
            continue;
        }
        close(fd); /* LB drops its copy — worker owns the fd now */
    }
}

static void handle_worker_ctrl(int wi) {
    Worker *w = &workers[wi];
    if (w->ctrl_fd >= 0) {
        epoll_del(w->ctrl_fd);
        close(w->ctrl_fd);
        w->ctrl_fd = -1;
        fprintf(stderr, "[lb] worker %d disconnected, reconnecting\n", wi);
    }
    schedule_reconnect(wi);
}

static void handle_reconnect_timer(int wi) {
    Worker *w = &workers[wi];
    if (w->timer_fd >= 0) {
        epoll_del(w->timer_fd);
        close(w->timer_fd);
        w->timer_fd = -1;
    }
    try_connect_worker(wi);
}

static int make_tcp_listen(int port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family      = AF_INET;
    sa.sin_addr.s_addr = INADDR_ANY;
    sa.sin_port        = htons((uint16_t)port);
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { perror("bind TCP"); exit(1); }
    listen(fd, 128);
    return fd;
}

int main(void) {
    for (int i = 0; i < MAX_WORKERS; i++) {
        workers[i].ctrl_fd  = -1;
        workers[i].timer_fd = -1;
    }

    int port = 9999;
    const char *port_env = getenv("PORT");
    if (port_env) port = atoi(port_env);

    const char *workers_env = getenv("WORKERS");
    if (!workers_env) workers_env = "/run/sockets/api1.ctrl,/run/sockets/api2.ctrl";

    char buf[512];
    strncpy(buf, workers_env, sizeof(buf) - 1);
    char *p = buf;
    while (p && nworkers < MAX_WORKERS) {
        char *comma = strchr(p, ',');
        if (comma) *comma = '\0';
        size_t plen = strnlen(p, sizeof(workers[nworkers].path) - 1);
        memcpy(workers[nworkers].path, p, plen);
        workers[nworkers].path[plen] = '\0';
        nworkers++;
        p = comma ? comma + 1 : NULL;
    }

    epfd = epoll_create1(EPOLL_CLOEXEC);

    /* Block until all workers are connected before opening TCP on :9999.
     * This eliminates the startup race: by the time the first TCP accept
     * fires, every worker slot is live. APIs bind their ctrl socket early
     * (before IVF loads), so this typically resolves in < 2 s. */
    for (int i = 0; i < nworkers; i++) {
        fprintf(stderr, "[lb] waiting for worker %d: %s\n", i, workers[i].path);
        while (!try_connect_worker_once(i)) {
            usleep(50 * 1000); /* 50 ms */
        }
    }

    tcp_listen_fd = make_tcp_listen(port);
    fprintf(stderr, "[lb] TCP listen on :%d\n", port);
    epoll_add(tcp_listen_fd, EPOLLIN | EPOLLET, TAG_TCP_LISTEN);

    struct epoll_event evs[64];
    while (1) {
        int n = epoll_wait(epfd, evs, 64, -1);
        for (int i = 0; i < n; i++) {
            uint64_t tag  = evs[i].data.u64;
            uint32_t kind = (uint32_t)(tag >> 32);
            int      wi   = (int)((tag >> 24) & 0xFF);

            if (kind == 0x01) {
                handle_tcp_accept();
            } else if (kind == 0x02) {
                handle_worker_ctrl(wi);
            } else if (kind == 0x03) {
                handle_reconnect_timer(wi);
            }
        }
    }
    return 0;
}
