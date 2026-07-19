cmake_minimum_required(VERSION 3.22.1)

project(viewshed_vulkan)

add_library(viewshed_vulkan SHARED
    viewshed_vulkan.cpp
)

target_link_libraries(viewshed_vulkan
    android
    log
    vulkan
)

set_target_properties(viewshed_vulkan PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
