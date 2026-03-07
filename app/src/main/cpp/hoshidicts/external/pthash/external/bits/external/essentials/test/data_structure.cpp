#include <iostream>

#include "../include/essentials.hpp"

using namespace essentials;

template <typename T>
struct basic {
    static_assert(std::is_pod<T>::value);

    basic() {}

    void reserve(size_t n) {
        m_data.reserve(n);
    }

    void push_back(T const& val) {
        m_data.push_back(val);
    }

    size_t size() const {
        return m_data.size();
    }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit(visitor, *this);
    }

private:
    int x = 10;
    std::vector<T> m_data;

    template <typename Visitor, typename F>
    static void visit(Visitor& visitor, F&& t) {
        visitor.visit(t.x);
        visitor.visit(t.m_data);
    }
};

template <typename T>
struct collection {
    collection() {
        int n = 13;
        x.reserve(n);
        for (int i = 0; i != n; ++i) {
            x.push_back(i);
        }

        m_data2.resize(n);
        for (auto& d : m_data2) {
            d.resize(5, 0);
        }
    }

    void resize(size_t n) {
        m_data.resize(n);
    }

    void reserve(uint32_t index, size_t n) {
        assert(index < size());
        m_data[index].reserve(n);
    }

    void push_back(uint32_t index, T const& val) {
        assert(index < size());
        m_data[index].push_back(val);
    }

    size_t size() const {
        return m_data.size();
    }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit(visitor, *this);
    }

private:
    basic<uint32_t> x;
    std::vector<basic<T>> m_data;
    std::vector<std::vector<uint64_t>> m_data2;

    template <typename Visitor, typename F>
    static void visit(Visitor& visitor, F&& t) {
        visitor.visit(t.x);
        visitor.visit(t.m_data);
        visitor.visit(t.m_data2);
    }
};

int main() {
    std::cout << "max resident set size: " << essentials::maxrss_in_bytes() << " bytes\n";

    const size_t universe = 1000;
    const size_t n = 10;

    {
        collection<uint64_t> my_ds;
        uniform_int_rng<uint64_t> r(0, universe);
        my_ds.resize(n);

        for (size_t i = 0, size = 4; i != n; ++i, size *= 2) {
            my_ds.reserve(i, size);
            for (size_t k = 0; k != size; ++k) {
                my_ds.push_back(i, r.gen());
            }
        }

        char const* output_filename = "./my_ds.bin";
        size_t written_bytes = save(my_ds, output_filename);
        std::cout << "written bytes = " << written_bytes << std::endl;
    }

    {
        collection<uint64_t> my_ds;
        char const* input_filename = "./my_ds.bin";
        size_t read_bytes = load(my_ds, input_filename);
        std::cout << "read bytes = " << read_bytes << std::endl;
        std::cout << convert(read_bytes, KB) << " [KB]" << std::endl;
        std::cout << convert(read_bytes, KiB) << " [KiB]" << std::endl;
        std::remove(input_filename);
        size_t bytes = print_size(my_ds, std::cout);
        std::cout << "computed bytes during parsing = " << bytes << std::endl;
    }

    std::cout << "max resident set size: " << essentials::maxrss_in_bytes() << " bytes\n";

    return 0;
}
