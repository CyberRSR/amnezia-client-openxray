#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <sys/syscall.h>
#include <unistd.h>

static int read_urandom(unsigned char *buffer, size_t length)
{
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    size_t offset = 0;
    while (offset < length) {
        ssize_t count = read(fd, buffer + offset, length - offset);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            int saved_errno = errno;
            close(fd);
            errno = saved_errno;
            return -1;
        }
        if (count == 0) {
            close(fd);
            errno = EIO;
            return -1;
        }
        offset += (size_t) count;
    }

    close(fd);
    return 0;
}

int getentropy(void *buffer, size_t length)
{
    if (buffer == NULL && length != 0) {
        errno = EFAULT;
        return -1;
    }
    if (length > 256) {
        errno = EIO;
        return -1;
    }

    unsigned char *bytes = (unsigned char *) buffer;

#if defined(__NR_getrandom)
    size_t offset = 0;
    while (offset < length) {
        long count = syscall(__NR_getrandom, bytes + offset, length - offset, 0);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == ENOSYS) {
                break;
            }
            return -1;
        }
        offset += (size_t) count;
    }
    if (offset == length) {
        return 0;
    }
#endif

    return read_urandom(bytes, length);
}
