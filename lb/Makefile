CC     = gcc
CFLAGS = -O2 -static -Wall -Wextra -D_GNU_SOURCE
TARGET = lb

$(TARGET): src/lb.c
	$(CC) $(CFLAGS) -o $(TARGET) src/lb.c

clean:
	rm -f $(TARGET)
