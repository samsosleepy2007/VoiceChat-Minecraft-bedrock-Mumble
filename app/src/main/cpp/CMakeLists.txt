cmake_minimum_required(VERSION 3.22.1)
project(vc_mumble_server_smoketest LANGUAGES CXX)

# The real Mumble core is built as its own top-level CMake project and staged
# into the APK as libvcserver.so. This local CMake target exists only for the
# lightweight development/smoke-test APK.
add_library(vcserver SHARED transport_smoketest_jni.cpp)
target_compile_features(vcserver PRIVATE cxx_std_20)
target_link_libraries(vcserver android log)
