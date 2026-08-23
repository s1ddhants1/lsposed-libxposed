cmake_minimum_required(VERSION 3.22.1)
project("native_hook")

add_library(native_hook SHARED
    native_hook.cpp
)

find_library(log-lib log)

target_link_libraries(native_hook
    ${log-lib}
)

target_compile_options(native_hook PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
)
