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
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_CLIENTS   64
#define BUF_SIZE      4096
#define MAX_WORKERS   8
#define MAX_UPSTREAMS 8

/* ---- fd tag encoded in epoll data.u64 ---- */
#define TAG_TCP_LISTEN   0x0100000000ULL
#define TAG_UDS_LISTEN   0x0200000000ULL
#define TAG_TCP_CLIENT   0x0300000000ULL
#define TAG_UDS_WORKER   0x0400000000ULL
#define FD_FROM_TAG(t)   ((int)((t) & 0xFFFFFFFF))

static const char RESP_503[] =
    "HTTP/1.1 503 Service Unavailable\r\n"
    "Content-Length: 0\r\n"
    "Connection: close\r\n\r\n";

static const char RESP_400[] =
    "HTTP/1.1 400 Bad Request\r\n"
    "Content-Length: 0\r\n"
    "Connection: close\r\n\r\n";

/* ---- client state ---- */
typedef struct {
    int      tcp_fd;          /* -1 = slot free */
    uint8_t  req_buf[BUF_SIZE];
    uint32_t req_have;        /* bytes read from TCP so far */
    int32_t  req_need;        /* -1 until headers parsed; then total request length */
    int      worker_idx;      /* -1 until dispatched */
    int      client_gone;     /* TCP side hung up while BUSY */
} Client;

/* ---- worker state ---- */
typedef enum { W_WAIT = 0, W_IDLE, W_BUSY } WorkerState;

typedef struct {
    int          uds_listen_fd;  /* LB's listening UDS fd for this upstream */
    int          uds_fd;         /* connected worker fd, -1 if not connected */
    WorkerState  state;
    int          client_idx;     /* which client we're serving (-1 if IDLE/WAIT) */
    /* frame header accumulation */
    uint8_t      hdr_buf[4];
    uint8_t      hdr_have;
    /* response body */
    uint8_t      resp_buf[BUF_SIZE];
    uint32_t     resp_len;
    uint32_t     resp_read;
    /* write-side buffering */
    uint32_t     write_off;      /* bytes of resp already written back to TCP */
} Worker;

static Client   clients[MAX_CLIENTS];
static Worker   workers[MAX_WORKERS];
static int      nworkers = 0;
static int      epfd     = -1;
static int      tcp_listen_fd = -1;
static int      pending_count = 0;  /* clients not yet dispatched */
static int      tcp_paused    = 0;

/* ---- helpers ---- */
static void set_nonblock(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

static void epoll_add(int fd, uint32_t events, uint64_t tag) {
    struct epoll_event ev;
    ev.events   = events;
    ev.data.u64 = tag | (uint32_t)fd;
    epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev);
}

static void epoll_mod(int fd, uint32_t events, uint64_t tag) {
    struct epoll_event ev;
    ev.events   = events;
    ev.data.u64 = tag | (uint32_t)fd;
    epoll_ctl(epfd, EPOLL_CTL_MOD, fd, &ev);
}

static void epoll_del(int fd) {
    epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
}

static uint32_t le32(const uint8_t *b) {
    return (uint32_t)b[0] | ((uint32_t)b[1]<<8) | ((uint32_t)b[2]<<16) | ((uint32_t)b[3]<<24);
}

static void u32_to_le(uint32_t v, uint8_t *b) {
    b[0] = v & 0xFF; b[1] = (v>>8)&0xFF; b[2] = (v>>16)&0xFF; b[3] = (v>>24)&0xFF;
}

/* ---- client management ---- */
static int client_alloc(int tcp_fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].tcp_fd == -1) {
            clients[i].tcp_fd     = tcp_fd;
            clients[i].req_have   = 0;
            clients[i].req_need   = -1;
            clients[i].worker_idx = -1;
            clients[i].client_gone = 0;
            return i;
        }
    }
    return -1;
}

static void client_free(int idx) {
    Client *c = &clients[idx];
    if (c->tcp_fd >= 0) {
        epoll_del(c->tcp_fd);
        close(c->tcp_fd);
        c->tcp_fd = -1;
    }
    if (c->worker_idx == -1) {
        pending_count--;
        if (tcp_paused && pending_count < MAX_CLIENTS) {
            epoll_mod(tcp_listen_fd, EPOLLIN|EPOLLET, TAG_TCP_LISTEN);
            tcp_paused = 0;
        }
    }
}

static void client_send_error(int idx, const char *resp, size_t len) {
    Client *c = &clients[idx];
    if (c->tcp_fd >= 0) {
        write(c->tcp_fd, resp, len);
    }
    client_free(idx);
}

/* ---- parse Content-Length from headers (returns -1 if not found / invalid) ---- */
static int32_t parse_content_length(const uint8_t *buf, int hdr_end) {
    /* hdr_end = index of first \n after \r\n\r\n (i.e., offset after header block) */
    const char *h = (const char *)buf;
    const char *end = h + hdr_end;
    const char *p = h;
    while (p < end) {
        if (strncasecmp(p, "content-length:", 15) == 0) {
            p += 15;
            while (p < end && (*p == ' ' || *p == '\t')) p++;
            long v = strtol(p, NULL, 10);
            if (v < 0 || v > BUF_SIZE) return -1;
            return (int32_t)v;
        }
        /* advance to next line */
        while (p < end && *p != '\n') p++;
        if (p < end) p++;
    }
    return -1;
}

/* ---- find end of HTTP headers: returns offset after \r\n\r\n, or -1 ---- */
static int find_headers_end(const uint8_t *buf, uint32_t len) {
    for (uint32_t i = 3; i < len; i++) {
        if (buf[i-3]=='\r' && buf[i-2]=='\n' && buf[i-1]=='\r' && buf[i]=='\n')
            return (int)(i + 1);
    }
    return -1;
}

/* ---- try to parse and set req_need for a client ---- */
static void client_try_parse(int idx) {
    Client *c = &clients[idx];
    if (c->req_need >= 0) return;
    int hdr_end = find_headers_end(c->req_buf, c->req_have);
    if (hdr_end < 0) return;

    /* GET /ready: no body */
    if (c->req_buf[0] == 'G') {
        c->req_need = hdr_end;
        return;
    }
    /* POST: need Content-Length */
    int32_t cl = parse_content_length(c->req_buf, hdr_end);
    if (cl < 0) {
        client_send_error(idx, RESP_400, sizeof(RESP_400)-1);
        return;
    }
    int32_t total = hdr_end + cl;
    if (total > BUF_SIZE) {
        client_send_error(idx, RESP_400, sizeof(RESP_400)-1);
        return;
    }
    c->req_need = total;
}

/* ---- find idle worker ---- */
static int find_idle_worker(void) {
    static int rr = 0;
    for (int i = 0; i < nworkers; i++) {
        int wi = (rr + i) % nworkers;
        if (workers[wi].state == W_IDLE) {
            rr = (wi + 1) % nworkers;
            return wi;
        }
    }
    return -1;
}

/* ---- dispatch a ready client to an idle worker ---- */
static void try_dispatch(int cidx) {
    Client *c = &clients[cidx];
    if (c->req_need < 0 || (int32_t)c->req_have < c->req_need) return;
    if (c->worker_idx >= 0) return;

    int wi = find_idle_worker();
    if (wi < 0) return; /* no idle worker; stay in pending queue */

    Worker *w = &workers[wi];
    c->worker_idx = wi;
    pending_count--;

    /* send length-prefix frame to worker */
    uint8_t pfx[4];
    u32_to_le((uint32_t)c->req_need, pfx);
    if (write(w->uds_fd, pfx, 4) < 0 || write(w->uds_fd, c->req_buf, c->req_need) < 0) {
        /* write failed — 503 and cleanup */
        client_send_error(cidx, RESP_503, sizeof(RESP_503)-1);
        w->state = W_IDLE;
        return;
    }

    w->state      = W_BUSY;
    w->client_idx = cidx;
    w->hdr_have   = 0;
    w->resp_len   = 0;
    w->resp_read  = 0;
    w->write_off  = 0;
    memset(w->hdr_buf, 0, 4);

    /* stop reading from this client's TCP fd (already fully read) */
    epoll_mod(c->tcp_fd, EPOLLRDHUP|EPOLLET, TAG_TCP_CLIENT);

    if (tcp_paused && pending_count < MAX_CLIENTS) {
        epoll_mod(tcp_listen_fd, EPOLLIN|EPOLLET, TAG_TCP_LISTEN);
        tcp_paused = 0;
    }
}

/* ---- try to dispatch any pending (unpaired) clients ---- */
static void flush_pending(void) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].tcp_fd >= 0 && clients[i].worker_idx < 0) {
            try_dispatch(i);
        }
    }
}

/* ---- write response back to TCP client (may be partial) ---- */
static void worker_flush_response(int wi) {
    Worker *w = &workers[wi];
    int cidx  = w->client_idx;
    Client *c = &clients[cidx];

    if (c->client_gone) {
        /* client already hung up — close its fd, free slot, free worker */
        if (c->tcp_fd >= 0) {
            epoll_del(c->tcp_fd);
            close(c->tcp_fd);
            c->tcp_fd = -1;
        }
        w->state = W_IDLE;
        w->client_idx = -1;
        flush_pending();
        return;
    }

    uint64_t ctag = TAG_TCP_CLIENT | ((uint32_t)cidx << 24);

    /* write HTTP response body directly to TCP client (no framing prefix) */
    while (w->write_off < w->resp_len) {
        ssize_t n = write(c->tcp_fd, w->resp_buf + w->write_off, w->resp_len - w->write_off);
        if (n < 0) {
            if (errno == EAGAIN) {
                epoll_mod(c->tcp_fd, EPOLLOUT|EPOLLRDHUP|EPOLLET, ctag);
                return;
            }
            goto done;
        }
        w->write_off += (uint32_t)n;
    }

done:
    /* response fully written (or client errored) */
    epoll_del(c->tcp_fd);
    close(c->tcp_fd);
    c->tcp_fd = -1;
    /* client slot freed — but client_free's pending decrement already happened at dispatch */
    w->state = W_IDLE;
    w->client_idx = -1;
    flush_pending();
}

/* ---- handle readable UDS worker (response coming in) ---- */
static void handle_worker_read(int wi) {
    Worker *w = &workers[wi];
    if (w->state != W_BUSY) return; /* spurious */

    /* read 4-byte length header */
    while (w->hdr_have < 4) {
        ssize_t n = read(w->uds_fd, w->hdr_buf + w->hdr_have, 4 - w->hdr_have);
        if (n < 0 && errno == EAGAIN) return;
        if (n <= 0) goto worker_disconnect;
        w->hdr_have += (uint8_t)n;
    }
    if (w->resp_len == 0) {
        w->resp_len = le32(w->hdr_buf);
        if (w->resp_len == 0 || w->resp_len > BUF_SIZE) goto worker_disconnect;
    }

    /* read response body */
    while (w->resp_read < w->resp_len) {
        ssize_t n = read(w->uds_fd, w->resp_buf + w->resp_read, w->resp_len - w->resp_read);
        if (n < 0 && errno == EAGAIN) return;
        if (n <= 0) goto worker_disconnect;
        w->resp_read += (uint32_t)n;
    }

    /* full response received — forward to client */
    worker_flush_response(wi);
    return;

worker_disconnect:
    {
        int cidx = w->client_idx;
        if (cidx >= 0 && clients[cidx].tcp_fd >= 0) {
            write(clients[cidx].tcp_fd, RESP_503, sizeof(RESP_503)-1);
            epoll_del(clients[cidx].tcp_fd);
            close(clients[cidx].tcp_fd);
            clients[cidx].tcp_fd = -1;
        }
        if (w->uds_fd >= 0) { epoll_del(w->uds_fd); close(w->uds_fd); w->uds_fd = -1; }
        w->state = W_WAIT;
        w->client_idx = -1;
        /* re-arm UDS listen so worker can reconnect */
        epoll_mod(w->uds_listen_fd, EPOLLIN|EPOLLET, TAG_UDS_LISTEN | (uint32_t)wi << 24);
    }
}

/* ---- handle TCP client readability ---- */
static void handle_client_read(int cidx) {
    Client *c = &clients[cidx];
    while (1) {
        ssize_t n = read(c->tcp_fd,
                         c->req_buf + c->req_have,
                         BUF_SIZE   - c->req_have);
        if (n < 0) {
            if (errno == EAGAIN) break;
            client_free(cidx);
            return;
        }
        if (n == 0) {
            client_free(cidx);
            return;
        }
        c->req_have += (uint32_t)n;
        if (c->req_need < 0) client_try_parse(cidx);
        /* check if parse errored (tcp_fd becomes -1) */
        if (c->tcp_fd < 0) return;
        if (c->req_need >= 0 && (int32_t)c->req_have >= c->req_need) {
            try_dispatch(cidx);
            return;
        }
        if (c->req_have >= BUF_SIZE) {
            client_send_error(cidx, RESP_400, sizeof(RESP_400)-1);
            return;
        }
    }
}

/* ---- handle TCP client EPOLLOUT (response buffered due to EAGAIN) ---- */
static void handle_client_write(int cidx) {
    Client *c = &clients[cidx];
    if (c->worker_idx < 0) return;
    worker_flush_response(c->worker_idx);
}

/* ---- handle new TCP connection ---- */
static void handle_tcp_accept(void) {
    while (1) {
        int fd = accept4(tcp_listen_fd, NULL, NULL, SOCK_NONBLOCK|SOCK_CLOEXEC);
        if (fd < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            break;
        }
        /* TCP_NODELAY for low-latency responses */
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

        if (pending_count >= MAX_CLIENTS) {
            /* queue full — reject */
            write(fd, RESP_503, sizeof(RESP_503)-1);
            close(fd);
            continue;
        }

        int cidx = client_alloc(fd);
        if (cidx < 0) {
            write(fd, RESP_503, sizeof(RESP_503)-1);
            close(fd);
            continue;
        }
        pending_count++;
        epoll_add(fd, EPOLLIN|EPOLLRDHUP|EPOLLET, TAG_TCP_CLIENT | (uint32_t)cidx << 24);
        /* try to read immediately (edge-triggered: data might already be in kernel buffer) */
        handle_client_read(cidx);
    }
}

/* ---- handle new UDS worker connection ---- */
static void handle_uds_accept(int wi) {
    Worker *w = &workers[wi];
    int fd = accept4(w->uds_listen_fd, NULL, NULL, SOCK_NONBLOCK|SOCK_CLOEXEC);
    if (fd < 0) return;
    if (w->uds_fd >= 0) { close(w->uds_fd); }
    w->uds_fd = fd;
    w->state  = W_IDLE;
    w->client_idx = -1;
    epoll_add(fd, EPOLLIN|EPOLLRDHUP|EPOLLET, TAG_UDS_WORKER | (uint32_t)wi << 24);
    /* pause UDS listen — only one worker per upstream slot */
    epoll_mod(w->uds_listen_fd, 0, TAG_UDS_LISTEN | (uint32_t)wi << 24);
    fprintf(stderr, "[lb] worker %d connected\n", wi);
    flush_pending();
}

/* ---- setup ---- */
static int make_tcp_listen(int port) {
    int fd = socket(AF_INET, SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC, 0);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = INADDR_ANY;
    sa.sin_port = htons((uint16_t)port);
    if (bind(fd, (struct sockaddr*)&sa, sizeof(sa)) < 0) {
        perror("bind TCP"); exit(1);
    }
    listen(fd, 128);
    return fd;
}

static int make_uds_listen(const char *path) {
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC, 0);
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, path, sizeof(sa.sun_path)-1);
    if (bind(fd, (struct sockaddr*)&sa, sizeof(sa)) < 0) {
        perror("bind UDS"); fprintf(stderr, "path: %s\n", path); exit(1);
    }
    listen(fd, 8);
    return fd;
}

int main(void) {
    /* init client slots */
    for (int i = 0; i < MAX_CLIENTS; i++) clients[i].tcp_fd = -1;
    for (int i = 0; i < MAX_WORKERS; i++) { workers[i].uds_fd = -1; workers[i].state = W_WAIT; workers[i].client_idx = -1; }

    int port = 9999;
    const char *port_env = getenv("PORT");
    if (port_env) port = atoi(port_env);

    const char *upstreams_env = getenv("UPSTREAMS");
    if (!upstreams_env) upstreams_env = "/run/sockets/api1.sock,/run/sockets/api2.sock";

    /* parse comma-separated UPSTREAMS */
    char upstreams_buf[512];
    strncpy(upstreams_buf, upstreams_env, sizeof(upstreams_buf)-1);
    char *p = upstreams_buf;
    while (p && nworkers < MAX_WORKERS) {
        char *comma = strchr(p, ',');
        if (comma) *comma = '\0';
        /* ensure directory exists */
        char dir[256]; strncpy(dir, p, sizeof(dir)-1);
        char *slash = strrchr(dir, '/');
        if (slash) { *slash = '\0'; mkdir(dir, 0755); }
        workers[nworkers].uds_listen_fd = make_uds_listen(p);
        fprintf(stderr, "[lb] listening on UDS %s\n", p);
        nworkers++;
        p = comma ? comma + 1 : NULL;
    }

    epfd = epoll_create1(EPOLL_CLOEXEC);

    tcp_listen_fd = make_tcp_listen(port);
    fprintf(stderr, "[lb] TCP listen on :%d\n", port);
    epoll_add(tcp_listen_fd, EPOLLIN|EPOLLET, TAG_TCP_LISTEN);

    for (int i = 0; i < nworkers; i++) {
        epoll_add(workers[i].uds_listen_fd, EPOLLIN|EPOLLET,
                  TAG_UDS_LISTEN | (uint32_t)i << 24);
    }

    struct epoll_event evs[64];
    while (1) {
        int n = epoll_wait(epfd, evs, 64, -1);
        for (int i = 0; i < n; i++) {
            uint64_t tag  = evs[i].data.u64;
            uint32_t kind = (uint32_t)(tag >> 32);
            uint32_t meta = (uint32_t)((tag >> 24) & 0xFF);

            if (kind == 0x01) {
                handle_tcp_accept();
            } else if (kind == 0x02) {
                handle_uds_accept((int)meta);
            } else if (kind == 0x03) {
                int cidx = (int)meta;
                uint32_t ev = evs[i].events;
                if (ev & (EPOLLRDHUP|EPOLLHUP|EPOLLERR)) {
                    Client *c = &clients[cidx];
                    if (c->worker_idx >= 0) {
                        c->client_gone = 1;
                    } else {
                        client_free(cidx);
                    }
                } else {
                    if (ev & EPOLLIN)  handle_client_read(cidx);
                    if (ev & EPOLLOUT) handle_client_write(cidx);
                }
            } else if (kind == 0x04) {
                int wi = (int)meta;
                uint32_t ev = evs[i].events;
                if (ev & (EPOLLRDHUP|EPOLLHUP|EPOLLERR)) {
                    handle_worker_read(wi); /* will detect n==0 and disconnect */
                } else if (ev & EPOLLIN) {
                    handle_worker_read(wi);
                }
            }
        }
    }
    return 0;
}
