cmake_minimum_required(VERSION 3.22.1)

project("viewshed")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        native-lib
        SHARED
        native-lib.cpp
        # TODO: Add more source files here, e.g. Renderer.cpp, Shader.cpp, etc.
)

find_library(
        log-lib
        log
)

find_library(
        android-lib
        android
)

find_library(
        glesv3-lib
        GLESv3
)

# Uncomment the line below if/when you switch to Vulkan
# find_library(vulkan-lib vulkan)

target_link_libraries(
        native-lib
        ${log-lib}
        ${android-lib}
        ${glesv3-lib}
        # ${vulkan-lib}
)

# Make headers in this folder visible
target_include_directories(native-lib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Sensible compiler flags for Android native code
target_compile_options(native-lib PRIVATE
        -Wall
        -Wextra
        -O2
)
