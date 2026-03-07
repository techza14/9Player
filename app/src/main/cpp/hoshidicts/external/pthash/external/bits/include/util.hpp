#pragma once

#include <cassert>
#include <cstdint>

#if defined(__x86_64__)
#include <immintrin.h>
#endif

namespace bits::util {

/*
    Good reference for built-in functions:
    http://gcc.gnu.org/onlinedocs/gcc/Other-Builtins.html
*/

/* return the position of the most significant bit (msb) */
static inline uint64_t msb(uint32_t x) {
    assert(x > 0);                 // if x is 0, the result is undefined
    return 31 - __builtin_clz(x);  // count leading zeros (clz)
}
static inline uint64_t msbll(uint64_t x) {
    assert(x > 0);                   // if x is 0, the result is undefined
    return 63 - __builtin_clzll(x);  // count leading zeros (clz)
}
static inline bool msbll(uint64_t x, uint64_t& ret) {
    if (x) {
        ret = 63 - __builtin_clzll(x);
        return true;
    }
    return false;
}

static inline uint64_t ceil_log2_uint32(uint32_t x) { return (x > 1) ? msb(x - 1) + 1 : 0; }
static inline uint64_t ceil_log2_uint64(uint64_t x) { return (x > 1) ? msbll(x - 1) + 1 : 0; }

/* return the position of the least significant bit (lsb) */
static inline uint64_t lsb(uint32_t x) {
    assert(x > 0);            // if x is 0, the result is undefined
    return __builtin_ctz(x);  // count trailing zeros (ctz)
}
static inline uint64_t lsbll(uint64_t x) {
    assert(x > 0);              // if x is 0, the result is undefined
    return __builtin_ctzll(x);  // count trailing zeros (ctz)
}
static inline bool lsbll(uint64_t x, uint64_t& ret) {
    if (x) {
        ret = __builtin_ctzll(x);
        return true;
    }
    return false;
}

static inline uint64_t popcount(uint64_t x) {
#ifdef __SSE4_2__
    return static_cast<uint64_t>(_mm_popcnt_u64(x));
#elif __cplusplus >= 202002L
    return std::popcount(x);
#else
    return static_cast<uint64_t>(__builtin_popcountll(x));
#endif
}

/*
    Return the position of the i-th 1 in the word,
    assuming i < popcount(word).
*/
static inline uint64_t select_in_word(uint64_t word, uint64_t i) {
    assert(i < popcount(word));
#ifndef __BMI2__
    // Modified from: Bit Twiddling Hacks
    // https://graphics.stanford.edu/~seander/bithacks.html#SelectPosFromMSBRank
    unsigned int s;       // Output: Resulting position of bit with rank r [1-64]
    uint64_t a, b, c, d;  // Intermediate temporaries for bit count.
    unsigned int t;       // Bit count temporary.
    uint64_t k = popcount(word) - i;
    a = word - ((word >> 1) & ~0UL / 3);
    b = (a & ~0UL / 5) + ((a >> 2) & ~0UL / 5);
    c = (b + (b >> 4)) & ~0UL / 0x11;
    d = (c + (c >> 8)) & ~0UL / 0x101;
    t = (d >> 32) + (d >> 48);
    s = 64;
    s -= ((t - k) & 256) >> 3;
    k -= (t & ((t - k) >> 8));
    t = (d >> (s - 16)) & 0xff;
    s -= ((t - k) & 256) >> 4;
    k -= (t & ((t - k) >> 8));
    t = (c >> (s - 8)) & 0xf;
    s -= ((t - k) & 256) >> 5;
    k -= (t & ((t - k) >> 8));
    t = (b >> (s - 4)) & 0x7;
    s -= ((t - k) & 256) >> 6;
    k -= (t & ((t - k) >> 8));
    t = (a >> (s - 2)) & 0x3;
    s -= ((t - k) & 256) >> 7;
    k -= (t & ((t - k) >> 8));
    t = (word >> (s - 1)) & 0x1;
    s -= ((t - k) & 256) >> 8;
    return s - 1;
#else
    uint64_t k = 1ULL << i;
    asm("pdep %[word], %[mask], %[word]" : [ word ] "+r"(word) : [ mask ] "r"(k));
    asm("tzcnt %[bit], %[index]" : [ index ] "=r"(k) : [ bit ] "g"(word) : "cc");
    return k;
#endif
}

}  // namespace bits::util
