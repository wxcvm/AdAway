#include <openssl/ssl.h>
#include <stdio.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
static unsigned destroyed;
static void on_ctx_free(void *parent, void *ptr, CRYPTO_EX_DATA *ad,
                        int idx, long argl, void *argp) {
    (void)parent; (void)ad; (void)idx; (void)argl; (void)argp;
    if (ptr == &destroyed) ++destroyed;
}
int main(void) {
    int idx = SSL_CTX_get_ex_new_index(0, NULL, NULL, NULL, on_ctx_free);
    CHECK(idx >= 0);
    SSL_CTX *base = SSL_CTX_new(TLS_server_method());
    SSL_CTX *cached = SSL_CTX_new(TLS_server_method());
    CHECK(base && cached);
    CHECK(SSL_CTX_set_ex_data(cached, idx, &destroyed) == 1);
    SSL *a = SSL_new(base), *b = SSL_new(base);
    CHECK(a && b);
    CHECK(SSL_set_SSL_CTX(a, cached) == cached);
    CHECK(SSL_set_SSL_CTX(b, cached) == cached);
    SSL_CTX_free(cached); /* release cache ownership, not SSL ownership */
    CHECK(destroyed == 0 && SSL_get_SSL_CTX(a) == cached && SSL_get_SSL_CTX(b) == cached);
    SSL_free(a);
    CHECK(destroyed == 0);
    SSL_free(b);
    CHECK(destroyed == 1);
    SSL_CTX_free(base);
    puts("PASS: evicted SNI context stays alive until last SSL reference closes");
    return 0;
}
