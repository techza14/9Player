#pragma once

#include <cassert>

#include "bit_vector.hpp"

namespace bits::util {

/* fixed width (32 bits) */
static void write_32bits(bit_vector::builder& builder, uint64_t x) {
    assert(x < (uint64_t(1) << 32));  // must fit in 32 bits
    builder.append_bits(x, 32);
}
static uint64_t read_32bits(bit_vector::iterator& it) { return it.take(32); }
/***/

/* Unary */
static void write_unary(bit_vector::builder& builder, uint64_t x) {
    assert(x < 64);
    uint64_t u = uint64_t(1) << x;
    builder.append_bits(u, x + 1);
}
static uint64_t read_unary(bit_vector::iterator& it) { return it.skip_zeros(); }
/***/

/* Binary */
/* write the integer x <= u using b=ceil(log2(u+1)) bits */
static void write_binary(bit_vector::builder& builder, uint64_t x, uint64_t u) {
    assert(u > 0);
    assert(x <= u);
    uint64_t b = msbll(u) + 1;
    assert(b <= 64);
    builder.append_bits(x, b);
}
/* read b=ceil(log2(u+1)) bits and interprets them as the integer x */
static uint64_t read_binary(bit_vector::iterator& it, uint64_t u) {
    assert(u > 0);
    uint64_t b = msbll(u) + 1;
    assert(b <= 64);
    uint64_t x = it.take(b);
    assert(x <= u);
    return x;
}
/***/

/* Gamma */
static void write_gamma(bit_vector::builder& builder, uint64_t x) {
    uint64_t xx = x + 1;
    uint64_t b = msbll(xx);
    write_unary(builder, b);
    uint64_t mask = (uint64_t(1) << b) - 1;
    builder.append_bits(xx & mask, b);
}
static uint64_t read_gamma(bit_vector::iterator& it) {
    uint64_t b = read_unary(it);
    return (it.take(b) | (uint64_t(1) << b)) - 1;
}
/***/

/* Delta */
static void write_delta(bit_vector::builder& builder, uint64_t x) {
    uint64_t xx = x + 1;
    uint64_t b = msbll(xx);
    write_gamma(builder, b);
    uint64_t mask = (uint64_t(1) << b) - 1;
    builder.append_bits(xx & mask, b);
}
static uint64_t read_delta(bit_vector::iterator& it) {
    uint64_t b = read_gamma(it);
    return (it.take(b) | (uint64_t(1) << b)) - 1;
}
/***/

/* Rice */
static void write_rice(bit_vector::builder& builder, uint64_t x, const uint64_t k) {
    assert(k > 0);
    uint64_t q = x >> k;
    uint64_t r = x - (q << k);
    write_gamma(builder, q);
    builder.append_bits(r, k);
}
static uint64_t read_rice(bit_vector::iterator& it, const uint64_t k) {
    assert(k > 0);
    uint64_t q = read_gamma(it);
    uint64_t r = it.take(k);
    return r + (q << k);
}
/***/

}  // namespace bits::util
