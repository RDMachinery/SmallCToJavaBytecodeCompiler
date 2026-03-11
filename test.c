/* test.c - Tests arithmetic, loops, functions, arrays in Small-C */

int counter;
int result;

int add(int a, int b) {
    return a + b;
}

int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

void main() {
    /* Test basic arithmetic */
    result = add(10, 32);
    printf(result);

    /* Test while loop with putchar */
    putchar('A');
    putchar('B');
    putchar('C');
    putchar('\n');

    /* Test for loop counting */
    counter = 0;
    int i;
    for (i = 0; i < 5; i++) {
        counter = counter + i;
    }
    printf(counter);

    /* Test if/else */
    if (counter > 5) {
        putchar('Y');
    } else {
        putchar('N');
    }
    putchar('\n');

    /* Test recursion */
    result = factorial(5);
    printf(result);
    putchar('\n');
}
