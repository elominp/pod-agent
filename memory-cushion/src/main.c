#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/mman.h>

#define ERROR_MESSAGE_MAX_LENGTH 1024

int main(int argc, char *argv[]) {
  static char error_message[ERROR_MESSAGE_MAX_LENGTH] = {0};
  
  if (argc < 2) {
    fprintf(stderr, "Usage: %s minimal_amount_of_resident_memory_in_bytes_to_lock\n", argv[0]);
    return EXIT_FAILURE;
  }
  
  size_t memory_to_reserve = atol(argv[1]);
  void * memory = calloc(memory_to_reserve, 1);

  if (mlock(memory, memory_to_reserve) < 0) {
    int error = errno;
    strerror_r(error, error_message, ERROR_MESSAGE_MAX_LENGTH - 2);
    error_message[ERROR_MESSAGE_MAX_LENGTH - 1] = '\0';
    fprintf(stderr, "Failed to lock the requested amount of resident memory, the call returned the error %d - %s\n", error, error_message);
    return EXIT_FAILURE;
  }
  while (1) {
    sleep(1);
  }
  return EXIT_SUCCESS;
}