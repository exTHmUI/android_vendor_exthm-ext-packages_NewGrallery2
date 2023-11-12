LOCAL_PATH := $(call my-dir)

#jpegstream
include $(CLEAR_VARS)
ifeq ($(strip $(TARGET_ARCH)),arm)
    LIB_PATH := libs/armeabi-v7a
else ifeq ($(strip $(TARGET_ARCH)),arm64)
    LIB_PATH := libs/arm64-v8a
endif

LOCAL_CHECK_ELF_FILES := false
LOCAL_MODULE := libsprdjni_jpeg
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_TAGS := optional
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := libsprdjni_jpeg.so
LOCAL_MODULE_STEM_64 := libsprdjni_jpeg.so
LOCAL_SRC_FILES_32 := $(LIB_PATH)/libsprdjni_jpeg.so
LOCAL_SRC_FILES_64 := $(LIB_PATH)/libsprdjni_jpeg.so
include $(BUILD_PREBUILT)


include $(CLEAR_VARS)
LOCAL_SRC_FILES := \
        jni/inputstream_wrapper.cpp \
        jni/jerr_hook.cpp \
        jni/jpeg_hook.cpp \
        jni/jpeg_reader.cpp \
        jni/jpeg_writer.cpp \
        jni/jpegstream.cpp \
        jni/outputstream_wrapper.cpp \
        jni/stream_wrapper.cpp
LOCAL_MODULE := libsprdjni_jpegstream2
LOCAL_LDLIBS := -llog
LOCAL_HEADER_LIBRARIES := jni_headers
LOCAL_SHARED_LIBRARIES := libsprdjni_jpeg
include $(BUILD_SHARED_LIBRARY)