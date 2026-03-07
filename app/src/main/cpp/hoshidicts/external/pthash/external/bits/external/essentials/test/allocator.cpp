#include <iostream>
#include <vector>
#include <cassert>

#include "../include/essentials.hpp"

using namespace essentials;

template <typename T1, typename T2>
struct basic {
    basic()
        : x(0)
        , y(0)
        , z(0) {}

    void reserve(size_t n) {
        m_data1.reserve(n);
        m_data2.reserve(n);
    }

    void push_back(T1 const& val1, T2 const& val2) {
        x = 12;
        y = 90;
        z = 723725;
        m_data1.push_back(val1);
        m_data2.push_back(val2);
    }

    void print() const {
        std::cout << "x = " << x << "; y = " << y << "; z = " << z << std::endl;
        for (auto val : m_data1) std::cout << val << " ";
        std::cout << std::endl;
        for (auto val : m_data2) std::cout << val << " ";
        std::cout << std::endl;
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
    int x;
    int y;
    uint64_t z;
    std::vector<T1> m_data1;
    std::vector<T2> m_data2;

    template <typename Visitor, typename F>
    static void visit(Visitor& visitor, F&& t) {
        visitor.visit(t.x);
        visitor.visit(t.y);
        visitor.visit(t.z);
        visitor.visit(t.m_data1);
        visitor.visit(t.m_data2);
    }
};

struct complex {
    typedef basic<int, double> value_type;

    complex() {}
    complex(size_t n) {
        m_data.reserve(n);
        for (size_t i = 0; i != n; ++i) {
            value_type obj;
            obj.reserve(n);
            for (size_t j = 0; j != n; ++j) {
                obj.push_back(j, j + 3.45);
            }
            m_data.push_back(obj);
        }
    }

    void print() const {
        for (auto const& obj : m_data) obj.print();
    }

    // contiguous_memory_allocator& get_allocator() {
    //     return m_allocator;
    // }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit_impl(visitor, *this);
    }
    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit_impl(visitor, *this);
    }

private:
    // contiguous_memory_allocator m_allocator;
    std::vector<value_type
                // allocator<value_type>
                >
        m_data;

    template <typename Visitor, typename F>
    static void visit_impl(Visitor& visitor, F&& t) {
        visitor.visit(t.m_data);
    }
};

struct wrapper {
    wrapper() {}
    wrapper(size_t n) {
        m_data.reserve(n);
        for (size_t i = 0; i != n; ++i) {
            complex c(n);
            m_data.push_back(c);
        }
    }

    void print() const {
        for (auto const& obj : m_data) obj.print();
    }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit(visitor, *this);
    }

    /* This function and the allocator are the only two things to add
    to use a custom memory allocator. */
    contiguous_memory_allocator& get_allocator() {
        return m_allocator;
    }

private:
    contiguous_memory_allocator m_allocator;
    /****************************************************************/

    std::vector<complex, allocator<complex>> m_data;

    template <typename Visitor, typename F>
    static void visit(Visitor& visitor, F&& t) {
        visitor.visit(t.m_data);
    }
};

int main() {
    static const size_t n = 10;
    char const* filename = "./.tmp.bin";
    size_t bytes = 0;

    // {
    //     complex c(n);
    //     bytes = save(c, filename);
    //     std::cout << "written bytes = " << bytes << std::endl;
    //     c.print();
    // }

    // complex c;
    // {
    //     std::cout << "====" << std::endl;
    //     bytes = load_with_custom_memory_allocation(c, filename);
    //     // bytes = load(c, filename);
    //     std::cout << "read bytes = " << bytes << std::endl;
    //     std::remove(filename);
    // }
    // c.print();

    {
        wrapper w(n);
        bytes = save(w, filename);
        std::cout << "written bytes = " << bytes << std::endl;
        w.print();
    }

    wrapper w;
    {
        std::cout << "====" << std::endl;
        bytes = load_with_custom_memory_allocation(w, filename);
        // bytes = load(w, filename);
        std::cout << "read bytes = " << bytes << std::endl;
        std::remove(filename);
    }
    w.print();

    return 0;
}