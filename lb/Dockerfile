FROM alpine:3.20 AS build
RUN apk add --no-cache musl-dev gcc make
WORKDIR /src
COPY Makefile .
COPY src ./src
RUN make CFLAGS="-O2 -static -Wall -Wextra -D_GNU_SOURCE"

FROM scratch
COPY --from=build /src/lb /lb
ENTRYPOINT ["/lb"]
