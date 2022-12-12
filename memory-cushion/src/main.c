#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char *argv[]) {
  long memoryToReserve = atol(argv[1]);
  void * memory = malloc(memoryToReserve);
  unsigned char canaryValue = 0;
  while (1) {
    memset(memory, canaryValue++, memoryToReserve);
    sleep(1);
  }
  return 0;
}